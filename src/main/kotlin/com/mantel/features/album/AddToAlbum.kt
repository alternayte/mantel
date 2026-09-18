package com.mantel.features.album

import com.mantel.features.agent.Caller
import com.mantel.features.agent.Scope
import com.mantel.features.agent.requireScope
import com.mantel.features.media.ItemId
import com.mantel.features.media.MediaItems
import com.mantel.kernel.Clock
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.db
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Serializable
data class AddItemsRequest(val mediaItemIds: List<String>)

/**
 * Putting media that is already in the library into an album. This is what an album is: a
 * selection. Nothing moves and nothing is copied, so it costs no quota and no upload.
 */
suspend fun addItemsToAlbum(
    call: ApplicationCall,
    clock: Clock = Clock.system,
) {
    val caller = requireScope(call, Scope.ALBUMS_WRITE)
    val request = call.receive<AddItemsRequest>()
    addItemsToAlbumFor(caller, albumIdFrom(call), request.mediaItemIds, clock)
    call.respond(HttpStatusCode.NoContent)
}

/** The command. The route above and the MCP tool both call this and nothing else. */
suspend fun addItemsToAlbumFor(
    caller: Caller,
    albumIdValue: AlbumId,
    rawItemIds: List<String>,
    clock: Clock = Clock.system,
) {
    val album = requireOwnAlbumFor(caller.demand(Scope.ALBUMS_WRITE), albumIdValue)
    val albumId = album[Albums.id]
    if (rawItemIds.isEmpty()) throw DomainException(ErrorCode.VALIDATION_FAILED, "No items named")

    val itemIds =
        rawItemIds.map { raw ->
            runCatching { ItemId(UUID.fromString(raw)) }.getOrNull()
                ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "$raw is not an item id")
        }

    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    db {
        // Only this account's own media. An id from somewhere else is a not-found, not a forbidden:
        // the answer must not confirm that the item exists.
        val owned =
            MediaItems.selectAll()
                .where { (MediaItems.id inList itemIds) and (MediaItems.accountId eq album[Albums.accountId]) }
                .map { it[MediaItems.id] }
                .toSet()
        val missing = itemIds.filterNot { it in owned }
        if (missing.isNotEmpty()) {
            throw DomainException(
                ErrorCode.NOT_FOUND,
                "No such item",
                mapOf("missing" to missing.joinToString(", ")),
            )
        }

        itemIds.forEach { addToAlbum(albumId, it, now) }
        Albums.update({ Albums.id eq albumId }) {
            it[itemCount] = albumSizeOf(albumId)
            it[totalBytes] = albumBytesOf(albumId)
            it[updatedAt] = now
        }
        settleAlbum(albumId, now)
    }
}
