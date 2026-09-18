package com.mantel.features.media

import com.mantel.features.album.AlbumItems
import com.mantel.features.album.Albums
import com.mantel.features.album.albumBytesOf
import com.mantel.features.album.albumIdFrom
import com.mantel.features.album.albumSizeOf
import com.mantel.features.album.demand
import com.mantel.features.album.itemIdFrom
import com.mantel.features.album.requireOwnAlbum
import com.mantel.features.album.settleAlbum
import com.mantel.kernel.Clock
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.db
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Serializable
data class ReorderRequest(val itemIds: List<String>)

@Serializable
data class CaptionRequest(val caption: String? = null)

/**
 * The client sends the album's items in their new order, all of them. A partial reorder would need
 * the server to guess what happened to the rest.
 */
suspend fun reorderItems(
    call: ApplicationCall,
    clock: Clock = Clock.system,
) {
    val caller = com.mantel.features.agent.requireScope(call, com.mantel.features.agent.Scope.ALBUMS_WRITE)
    val body = call.receive<ReorderRequest>()
    reorderItemsFor(caller, albumIdFrom(call), body.itemIds, clock)
    call.respond(HttpStatusCode.NoContent)
}

/** The command. The route above and the MCP tool both call this and nothing else. */
suspend fun reorderItemsFor(
    caller: com.mantel.features.agent.Caller,
    albumIdValue: com.mantel.features.album.AlbumId,
    requestedIds: List<String>,
    clock: Clock = Clock.system,
) {
    val album =
        com.mantel.features.album.requireOwnAlbumFor(
            caller.demand(com.mantel.features.agent.Scope.ALBUMS_WRITE),
            albumIdValue,
        )
    val albumId = album[Albums.id]
    val requested =
        requestedIds.map { raw ->
            runCatching { ItemId(UUID.fromString(raw)) }.getOrNull()
                ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "$raw is not an item id")
        }

    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    db {
        val existing =
            AlbumItems.selectAll().where { AlbumItems.albumId eq albumId }.map { it[AlbumItems.mediaItemId] }.toSet()
        if (requested.toSet() != existing) {
            throw DomainException(
                ErrorCode.VALIDATION_FAILED,
                "A reorder names every item in the album exactly once",
                mapOf("expected" to existing.size.toString(), "received" to requested.size.toString()),
            )
        }
        requested.forEachIndexed { index, itemId ->
            AlbumItems.update({ (AlbumItems.albumId eq albumId) and (AlbumItems.mediaItemId eq itemId) }) {
                it[position] = index
            }
        }
        Albums.update({ Albums.id eq albumId }) { it[updatedAt] = now }
    }
}

suspend fun setCaption(
    call: ApplicationCall,
    clock: Clock = Clock.system,
) {
    val caller = com.mantel.features.agent.requireScope(call, com.mantel.features.agent.Scope.ALBUMS_WRITE)
    val body = call.receive<CaptionRequest>()
    setCaptionFor(caller, albumIdFrom(call), itemIdFrom(call).toString(), body.caption, clock)
    call.respond(HttpStatusCode.NoContent)
}

/** The command. The route above and the MCP tool both call this and nothing else. */
suspend fun setCaptionFor(
    caller: com.mantel.features.agent.Caller,
    albumIdValue: com.mantel.features.album.AlbumId,
    rawItemId: String,
    rawCaption: String?,
    clock: Clock = Clock.system,
) {
    val album =
        com.mantel.features.album.requireOwnAlbumFor(
            caller.demand(com.mantel.features.agent.Scope.ALBUMS_WRITE),
            albumIdValue,
        )
    val itemId =
        runCatching { ItemId(UUID.fromString(rawItemId)) }.getOrElse {
            throw DomainException(ErrorCode.NOT_FOUND, "No such item")
        }
    val caption = Caption.of(rawCaption)

    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    val changed =
        db {
            // The caption belongs to the membership: the same photograph carries different words
            // in a different album.
            val updated =
                AlbumItems.update({
                    (AlbumItems.mediaItemId eq itemId) and (AlbumItems.albumId eq album[Albums.id])
                }) {
                    it[AlbumItems.caption] = caption?.value
                }
            if (updated > 0) Albums.update({ Albums.id eq album[Albums.id] }) { it[updatedAt] = now }
            updated
        }
    if (changed == 0) throw DomainException(ErrorCode.NOT_FOUND, "No such item")
}

/**
 * Taking an item out of an album. The photograph stays in the library, because an album is a
 * selection and unselecting is not deleting. `DELETE /api/library/{itemId}` removes the bytes.
 */
suspend fun removeFromAlbum(
    call: ApplicationCall,
    clock: Clock = Clock.system,
) {
    val album = requireOwnAlbum(call, albumIdFrom(call))
    val albumId = album[Albums.id]
    val itemId = itemIdFrom(call)
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    val removed =
        db {
            val gone =
                AlbumItems.deleteWhere {
                    (AlbumItems.albumId eq albumId) and (AlbumItems.mediaItemId eq itemId)
                }
            if (gone > 0) {
                AlbumItems.selectAll()
                    .where { AlbumItems.albumId eq albumId }
                    .orderBy(AlbumItems.position)
                    .map { it[AlbumItems.mediaItemId] }
                    .forEachIndexed { index, id ->
                        AlbumItems.update({ (AlbumItems.albumId eq albumId) and (AlbumItems.mediaItemId eq id) }) {
                            it[position] = index
                        }
                    }
                Albums.update({ Albums.id eq albumId }) {
                    it[itemCount] = albumSizeOf(albumId)
                    it[totalBytes] = albumBytesOf(albumId)
                    it[updatedAt] = now
                    if (album[Albums.coverItemId] == itemId) it[coverItemId] = null
                }
                settleAlbum(albumId, now)
            }
            gone
        }
    if (removed == 0) throw DomainException(ErrorCode.NOT_FOUND, "No such item")
    call.respond(HttpStatusCode.NoContent)
}
