package com.mantel.features.account

import com.mantel.features.agent.requirePerson
import com.mantel.features.album.AlbumItems
import com.mantel.features.album.Albums
import com.mantel.features.album.itemsOf
import com.mantel.features.media.MediaItems
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.db
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.selectAll

/**
 * Everything the product holds about an account, as one JSON document.
 *
 * The library is exported whole, not album by album: a photograph that is in no album is still the
 * person's, and an export that only walked albums would quietly leave it out.
 */
@Serializable
data class AccountExport(
    val email: String,
    val displayName: String? = null,
    val createdAt: String,
    val storageQuotaBytes: Long,
    val storageUsedBytes: Long,
    val library: List<ExportedItem> = emptyList(),
    val albums: List<ExportedAlbum> = emptyList(),
)

@Serializable
data class ExportedItem(
    val id: String,
    val filename: String? = null,
    val kind: String,
    val status: String,
    val byteSize: Long,
    val renderable: Boolean,
    val createdAt: String,
)

@Serializable
data class ExportedAlbum(
    val id: String,
    val title: String,
    val description: String? = null,
    val status: String,
    val createdAt: String,
    val items: List<ExportedAlbumItem>,
)

@Serializable
data class ExportedAlbumItem(val id: String, val position: Int, val caption: String? = null)

suspend fun exportAccount(call: ApplicationCall) {
    val accountId = requirePerson(call)
    val export =
        db {
            Accounts.selectAll().where { Accounts.id eq accountId }.singleOrNull()?.let { account ->
                val library =
                    MediaItems.selectAll()
                        .where { MediaItems.accountId eq accountId }
                        .orderBy(MediaItems.createdAt)
                        .map { row ->
                            ExportedItem(
                                id = row[MediaItems.id].toString(),
                                filename = row[MediaItems.filename],
                                kind = row[MediaItems.kind].wire,
                                status = row[MediaItems.status].wire,
                                byteSize = row[MediaItems.byteSize].value,
                                renderable = row[MediaItems.renderable],
                                createdAt = row[MediaItems.createdAt].toInstant().toString(),
                            )
                        }
                val albums =
                    Albums.selectAll()
                        .where { Albums.accountId eq accountId }
                        .orderBy(Albums.createdAt)
                        .map { album ->
                            ExportedAlbum(
                                id = album[Albums.id].toString(),
                                title = album[Albums.title].value,
                                description = album[Albums.description],
                                status = album[Albums.status].wire,
                                createdAt = album[Albums.createdAt].toInstant().toString(),
                                items =
                                    itemsOf(album[Albums.id]).map { row ->
                                        ExportedAlbumItem(
                                            id = row[MediaItems.id].toString(),
                                            position = row[AlbumItems.position],
                                            caption = row[AlbumItems.caption],
                                        )
                                    },
                            )
                        }
                AccountExport(
                    email = account[Accounts.email],
                    displayName = account[Accounts.displayName],
                    createdAt = account[Accounts.createdAt].toInstant().toString(),
                    storageQuotaBytes = account[Accounts.storageQuotaBytes].value,
                    storageUsedBytes = account[Accounts.storageUsedBytes].value,
                    library = library,
                    albums = albums,
                )
            }
        } ?: throw DomainException(ErrorCode.NOT_FOUND, "No such account")

    call.response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "mantel-export.json")
            .toString(),
    )
    call.respond(export)
}
