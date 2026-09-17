package com.mantel.features.album

import com.mantel.features.auth.requireAccountId
import com.mantel.features.media.ItemState
import com.mantel.features.media.MediaItems
import com.mantel.kernel.db
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

@Serializable
data class ItemView(
    val id: String,
    val position: Int,
    val kind: String,
    val status: String,
    val byteSize: Long,
    val caption: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Int? = null,
    val lastError: String? = null,
)

@Serializable
data class AlbumView(
    val id: String,
    val title: String,
    val description: String? = null,
    val status: String,
    val itemCount: Int,
    val totalBytes: Long,
    val coverItemId: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val items: List<ItemView>,
)

fun ResultRow.toSummary() =
    AlbumSummary(
        id = this[Albums.id].toString(),
        title = this[Albums.title].value,
        description = this[Albums.description],
        status = this[Albums.status].wire,
        itemCount = this[Albums.itemCount],
        totalBytes = this[Albums.totalBytes].value,
        coverItemId = this[Albums.coverItemId]?.toString(),
        createdAt = this[Albums.createdAt].toInstant().toString(),
        updatedAt = this[Albums.updatedAt].toInstant().toString(),
    )

fun ResultRow.toItemView() =
    ItemView(
        id = this[MediaItems.id].toString(),
        position = this[MediaItems.position],
        kind = this[MediaItems.kind].wire,
        status = this[MediaItems.status].wire,
        byteSize = this[MediaItems.byteSize].value,
        caption = this[MediaItems.caption],
        width = this[MediaItems.width],
        height = this[MediaItems.height],
        durationMs = this[MediaItems.durationMs],
        lastError = this[MediaItems.lastError],
    )

suspend fun listAlbums(call: ApplicationCall) {
    val accountId = requireAccountId(call)
    val albums =
        db {
            Albums.selectAll()
                .where { (Albums.accountId eq accountId) and Albums.archivedAt.isNull() }
                .orderBy(Albums.updatedAt, SortOrder.DESC)
                .map { it.toSummary() }
        }
    call.respond(albums)
}

suspend fun getAlbum(call: ApplicationCall) {
    val album = requireOwnAlbum(call, albumIdFrom(call))
    val items =
        db {
            MediaItems.selectAll()
                .where { MediaItems.albumId eq album[Albums.id] }
                .orderBy(MediaItems.position to SortOrder.ASC)
                .map { it.toItemView() }
        }
    val summary = album.toSummary()
    call.respond(
        AlbumView(
            id = summary.id,
            title = summary.title,
            description = summary.description,
            status = summary.status,
            itemCount = summary.itemCount,
            totalBytes = summary.totalBytes,
            coverItemId = summary.coverItemId,
            createdAt = summary.createdAt,
            updatedAt = summary.updatedAt,
            items = items,
        ),
    )
}

@Serializable
data class AlbumProgress(
    val status: String,
    val total: Int,
    val ready: Int,
    val failed: Int,
    val pending: Int,
    val items: List<ItemView>,
)

/**
 * Per-item processing progress. The creator watches this while assembling, and the viewer's
 * manifest reads the same rows, because the job state and the item state are the same data.
 */
suspend fun getAlbumProgress(call: ApplicationCall) {
    val album = requireOwnAlbum(call, albumIdFrom(call))
    val items =
        db {
            MediaItems.selectAll()
                .where { MediaItems.albumId eq album[Albums.id] }
                .orderBy(MediaItems.position to SortOrder.ASC)
                .map { it.toItemView() }
        }
    val states = items.map { ItemState.fromWire(it.status) }
    call.respond(
        AlbumProgress(
            status = album[Albums.status].wire,
            total = items.size,
            ready = states.count { it == ItemState.READY },
            failed = states.count { it == ItemState.FAILED },
            pending = states.count { it != ItemState.READY && it != ItemState.FAILED },
            items = items,
        ),
    )
}
