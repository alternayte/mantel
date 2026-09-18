package com.mantel.features.album

import com.mantel.features.agent.Caller
import com.mantel.features.agent.Scope
import com.mantel.features.agent.requireScope
import com.mantel.features.media.ItemState
import com.mantel.features.media.MediaItems
import com.mantel.kernel.db
import com.mantel.storage.ObjectStorage
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import java.time.Duration

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
    val filename: String? = null,
    /** The creator's own thumbnail. Signed by the hour, like the viewer's (SDD.md 3.2). */
    val thumbUrl: String? = null,
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

private val THUMB_LIFETIME: Duration = Duration.ofHours(6)

fun ResultRow.toItemView(storage: ObjectStorage? = null) =
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
        filename = this[MediaItems.filename],
        lastError = this[MediaItems.lastError],
        thumbUrl =
            this[MediaItems.thumbKey]
                ?.takeIf { this[MediaItems.status] == ItemState.READY }
                ?.let { key -> storage?.presignGetForThisHour(key, THUMB_LIFETIME) },
    )

suspend fun listAlbums(call: ApplicationCall) {
    call.respond(listAlbumsFor(requireScope(call, Scope.ALBUMS_READ)))
}

suspend fun listAlbumsFor(caller: Caller): List<AlbumSummary> {
    val accountId = caller.demand(Scope.ALBUMS_READ).accountId
    return db {
        Albums.selectAll()
            .where { (Albums.accountId eq accountId) and Albums.archivedAt.isNull() }
            .orderBy(Albums.updatedAt, SortOrder.DESC)
            .map { it.toSummary() }
    }
}

suspend fun getAlbum(
    call: ApplicationCall,
    storage: ObjectStorage,
) {
    call.respond(readAlbumFor(requireScope(call, Scope.ALBUMS_READ), albumIdFrom(call), storage))
}

suspend fun readAlbumFor(
    caller: Caller,
    albumId: AlbumId,
    storage: ObjectStorage,
): AlbumView {
    val album = requireOwnAlbumFor(caller.demand(Scope.ALBUMS_READ), albumId)
    val rows =
        db {
            MediaItems.selectAll()
                .where { MediaItems.albumId eq album[Albums.id] }
                .orderBy(MediaItems.position to SortOrder.ASC)
                .toList()
        }
    val items = withContext(Dispatchers.IO) { rows.map { it.toItemView(storage) } }
    val summary = album.toSummary()
    return AlbumView(
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
suspend fun getAlbumProgress(
    call: ApplicationCall,
    storage: ObjectStorage,
) {
    val album = requireOwnAlbum(call, albumIdFrom(call), Scope.ALBUMS_READ)
    val rows =
        db {
            MediaItems.selectAll()
                .where { MediaItems.albumId eq album[Albums.id] }
                .orderBy(MediaItems.position to SortOrder.ASC)
                .toList()
        }
    val items = withContext(Dispatchers.IO) { rows.map { it.toItemView(storage) } }
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
