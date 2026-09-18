package com.mantel.features.media

import com.mantel.features.account.Accounts
import com.mantel.features.account.quota
import com.mantel.features.album.Albums
import com.mantel.features.album.albumIdFrom
import com.mantel.features.album.demand
import com.mantel.features.album.itemIdFrom
import com.mantel.features.album.requireOwnAlbum
import com.mantel.kernel.Clock
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.db
import com.mantel.storage.ObjectStorage
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            MediaItems.selectAll().where { MediaItems.albumId eq albumId }.map { it[MediaItems.id] }.toSet()
        if (requested.toSet() != existing) {
            throw DomainException(
                ErrorCode.VALIDATION_FAILED,
                "A reorder names every item in the album exactly once",
                mapOf("expected" to existing.size.toString(), "received" to requested.size.toString()),
            )
        }
        requested.forEachIndexed { index, itemId ->
            MediaItems.update({ MediaItems.id eq itemId }) { it[position] = index }
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
            val updated =
                MediaItems.update({ (MediaItems.id eq itemId) and (MediaItems.albumId eq album[Albums.id]) }) {
                    it[MediaItems.caption] = caption?.value
                }
            if (updated > 0) Albums.update({ Albums.id eq album[Albums.id] }) { it[updatedAt] = now }
            updated
        }
    if (changed == 0) throw DomainException(ErrorCode.NOT_FOUND, "No such item")
}

/**
 * Removing an item returns its reserved bytes to the account and takes every object it owns with
 * it, derivatives included, by deleting the item's own storage prefix.
 */
suspend fun deleteItem(
    call: ApplicationCall,
    storage: ObjectStorage,
    clock: Clock = Clock.system,
) {
    val album = requireOwnAlbum(call, albumIdFrom(call))
    val albumId = album[Albums.id]
    val itemId = itemIdFrom(call)

    val item =
        db {
            MediaItems.selectAll()
                .where { (MediaItems.id eq itemId) and (MediaItems.albumId eq albumId) }
                .singleOrNull()
        } ?: throw DomainException(ErrorCode.NOT_FOUND, "No such item")

    val prefix = item[MediaItems.originalKey].substringBeforeLast('/') + "/"
    withContext(Dispatchers.IO) {
        // An unfinished multipart upload holds bytes that no listing shows and no row points at.
        item[MediaItems.uploadId]?.let { storage.abortMultipartUpload(item[MediaItems.originalKey], it) }
        storage.deletePrefix(prefix)
    }

    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    db {
        MediaItems.deleteWhere { MediaItems.id eq itemId }
        MediaItems.selectAll()
            .where { MediaItems.albumId eq albumId }
            .orderBy(MediaItems.position)
            .forEachIndexed { index, row ->
                MediaItems.update({ MediaItems.id eq row[MediaItems.id] }) { it[position] = index }
            }
        Albums.update({ Albums.id eq albumId }) {
            it[itemCount] = album[Albums.itemCount] - 1
            it[totalBytes] = album[Albums.totalBytes] - item[MediaItems.byteSize]
            it[updatedAt] = now
        }
        val account = Accounts.selectAll().where { Accounts.id eq album[Albums.accountId] }.single()
        Accounts.update({ Accounts.id eq album[Albums.accountId] }) {
            it[storageUsedBytes] = account.quota().release(item[MediaItems.byteSize]).used
        }
    }
    call.respond(HttpStatusCode.NoContent)
}
