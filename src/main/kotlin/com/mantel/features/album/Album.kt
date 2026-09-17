package com.mantel.features.album

import com.mantel.features.account.Accounts
import com.mantel.features.auth.requireAccountId
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.db
import io.ktor.server.application.ApplicationCall
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

object Albums : Table("album") {
    val id = uuid("id")
    val accountId = reference("account_id", Accounts.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION)
    val title = text("title")
    val description = text("description").nullable()
    val coverItemId = uuid("cover_item_id").nullable()
    val status = text("status")
    val itemCount = integer("item_count")
    val totalBytes = long("total_bytes")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val publishedAt = timestampWithTimeZone("published_at").nullable()
    val archivedAt = timestampWithTimeZone("archived_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * An album is assembled as a draft, becomes ready when every item has finished processing, and
 * becomes published when a live share link exists (M5). Publishing is not a separate action.
 */
enum class AlbumStatus {
    DRAFT,
    READY,
    PUBLISHED,
    ARCHIVED,
    ;

    val wire: String get() = name.lowercase()
}

/**
 * The album, if this account owns it. Anything else is a 404: a creator learns nothing about an
 * album belonging to someone else, not even that it exists.
 */
suspend fun requireOwnAlbum(
    call: ApplicationCall,
    albumId: UUID,
): ResultRow {
    val accountId = requireAccountId(call)
    return db {
        Albums.selectAll()
            .where { (Albums.id eq albumId) and (Albums.accountId eq accountId) and Albums.archivedAt.isNull() }
            .singleOrNull()
    } ?: throw DomainException(ErrorCode.NOT_FOUND, "No such album")
}

fun albumIdFrom(call: ApplicationCall): UUID =
    call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw DomainException(ErrorCode.NOT_FOUND, "No such album")

fun itemIdFrom(call: ApplicationCall): UUID =
    call.parameters["itemId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw DomainException(ErrorCode.NOT_FOUND, "No such item")
