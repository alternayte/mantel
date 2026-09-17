package com.mantel.features.media

import com.mantel.features.album.Albums
import com.mantel.features.album.albumIdFrom
import com.mantel.features.album.requireOwnAlbum
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.db
import com.mantel.storage.ObjectStorage
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.UUID

@Serializable
data class CompleteUploadsRequest(val itemIds: List<String>)

@Serializable
data class CompleteUploadsResponse(val uploaded: List<String>, val missing: List<String>)

/**
 * One call for the whole batch: forty photos are one request, not forty. Storage is asked whether
 * each object actually arrived, because a client that says it uploaded is not evidence, and an item
 * marked uploaded with no bytes behind it becomes a failed job later for no reason.
 */
suspend fun completeUploads(
    call: ApplicationCall,
    storage: ObjectStorage,
) {
    val album = requireOwnAlbum(call, albumIdFrom(call))
    val albumId = album[Albums.id]
    val request = call.receive<CompleteUploadsRequest>()
    val itemIds =
        request.itemIds.map { raw ->
            runCatching { UUID.fromString(raw) }.getOrNull()
                ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "$raw is not an item id")
        }
    if (itemIds.isEmpty()) throw DomainException(ErrorCode.VALIDATION_FAILED, "No items named")

    val pending =
        db {
            MediaItems.selectAll()
                .where {
                    (MediaItems.albumId eq albumId) and
                        (MediaItems.id inList itemIds) and
                        (MediaItems.status eq ItemState.PENDING_UPLOAD.wire)
                }
                .associate { it[MediaItems.id] to it[MediaItems.originalKey] }
        }

    val arrived = withContext(Dispatchers.IO) { pending.filterValues { storage.sizeOf(it) != null } }

    db {
        arrived.keys.forEach { itemId ->
            MediaItems.update({ MediaItems.id eq itemId }) {
                it[status] = transition(ItemState.PENDING_UPLOAD, ItemEvent.UploadObserved).wire
            }
        }
    }

    call.respond(
        CompleteUploadsResponse(
            uploaded = arrived.keys.map { it.toString() },
            missing = itemIds.filterNot { it in arrived.keys }.map { it.toString() },
        ),
    )
}
