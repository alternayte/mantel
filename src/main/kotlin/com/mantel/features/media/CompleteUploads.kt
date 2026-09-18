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

private data class PendingUpload(val id: ItemId, val key: String, val uploadId: String?, val declared: Long)

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
            runCatching { ItemId(UUID.fromString(raw)) }.getOrNull()
                ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "$raw is not an item id")
        }
    if (itemIds.isEmpty()) throw DomainException(ErrorCode.VALIDATION_FAILED, "No items named")

    val pending =
        db {
            MediaItems.selectAll()
                .where {
                    (MediaItems.albumId eq albumId) and
                        (MediaItems.id inList itemIds) and
                        (MediaItems.status eq ItemState.PENDING_UPLOAD)
                }
                .map {
                    PendingUpload(
                        id = it[MediaItems.id],
                        key = it[MediaItems.originalKey],
                        uploadId = it[MediaItems.uploadId],
                        declared = it[MediaItems.byteSize].value,
                    )
                }
        }

    // A part upload is finished here rather than by the client, so the client never has to keep
    // ETags. Storage lists what it holds, and an incomplete set stays pending and resumable.
    val arrived =
        withContext(Dispatchers.IO) {
            pending.filter { item ->
                if (item.uploadId == null) {
                    storage.sizeOf(item.key) != null
                } else {
                    val parts = storage.listParts(item.key, item.uploadId)
                    val complete = parts.sumOf { it.sizeBytes } == item.declared
                    if (complete) {
                        storage.completeMultipartUpload(item.key, item.uploadId, parts)
                    }
                    complete && storage.sizeOf(item.key) != null
                }
            }
        }

    db {
        arrived.forEach { item ->
            MediaItems.update({ MediaItems.id eq item.id }) {
                it[status] = transition(ItemState.PENDING_UPLOAD, ItemEvent.UploadObserved)
                it[uploadId] = null
            }
        }
    }

    val uploaded = arrived.map { it.id }
    call.respond(
        CompleteUploadsResponse(
            uploaded = uploaded.map { it.toString() },
            missing = itemIds.filterNot { it in uploaded }.map { it.toString() },
        ),
    )
}
