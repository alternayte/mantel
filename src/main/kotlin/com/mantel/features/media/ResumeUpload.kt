package com.mantel.features.media

import com.mantel.features.album.Albums
import com.mantel.features.album.albumIdFrom
import com.mantel.features.album.itemIdFrom
import com.mantel.features.album.requireOwnAlbum
import com.mantel.kernel.Config
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.db
import com.mantel.storage.ObjectStorage
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import java.time.Duration

@Serializable
data class UploadProgress(
    val itemId: String,
    val uploadId: String,
    val sizeBytes: Long,
    val received: List<ReceivedPart>,
    val remaining: List<PresignedPart>,
)

@Serializable
data class ReceivedPart(val partNumber: Int, val etag: String, val sizeBytes: Long)

private val PRESIGN_LIFETIME: Duration = Duration.ofHours(1)

/**
 * What storage already holds for an interrupted upload, and fresh URLs for what it does not.
 *
 * This is the whole point of multipart here: a client that lost its connection, its page or its
 * laptop asks what arrived and sends only the rest. Storage is the source of truth, because it is
 * the only party that knows which bytes landed.
 */
suspend fun getUploadProgress(
    call: ApplicationCall,
    config: Config,
    storage: ObjectStorage,
) {
    val album = requireOwnAlbum(call, albumIdFrom(call))
    respondWithProgress(call, album[Albums.accountId], itemIdFrom(call), config, storage)
}

/**
 * The same answer for media that has no album. An interrupted backup is the common case for a
 * large file, and it must not depend on the file having been selected into something.
 */
suspend fun getLibraryUploadProgress(
    call: ApplicationCall,
    config: Config,
    storage: ObjectStorage,
) {
    val accountId =
        com.mantel.features.agent.requireScope(call, com.mantel.features.agent.Scope.ALBUMS_WRITE).accountId
    val itemId =
        call.parameters["itemId"]?.let { runCatching { ItemId(java.util.UUID.fromString(it)) }.getOrNull() }
            ?: throw DomainException(ErrorCode.NOT_FOUND, "No such item")
    respondWithProgress(call, accountId, itemId, config, storage)
}

private suspend fun respondWithProgress(
    call: ApplicationCall,
    accountId: com.mantel.features.account.AccountId,
    itemId: ItemId,
    config: Config,
    storage: ObjectStorage,
) {
    val item =
        db {
            MediaItems.selectAll()
                .where { (MediaItems.id eq itemId) and (MediaItems.accountId eq accountId) }
                .singleOrNull()
        } ?: throw DomainException(ErrorCode.NOT_FOUND, "No such item")

    val uploadId =
        item[MediaItems.uploadId]
            ?: throw DomainException(ErrorCode.CONFLICT, "That upload is not in parts")
    if (item[MediaItems.status] != ItemState.PENDING_UPLOAD) {
        throw DomainException(ErrorCode.CONFLICT, "That upload has already finished")
    }

    val key = item[MediaItems.originalKey]
    val total = item[MediaItems.byteSize].value
    val sizes = partsFor(total, config.storage.partSize.value)

    val received = withContext(Dispatchers.IO) { storage.listParts(key, uploadId) }
    val arrived = received.associateBy { it.partNumber }

    val remaining =
        withContext(Dispatchers.IO) {
            sizes.mapIndexed { index, size -> (index + 1) to size }
                .filter { (number, size) -> arrived[number]?.sizeBytes != size }
                .map { (number, size) ->
                    PresignedPart(
                        partNumber = number,
                        uploadUrl = storage.presignPart(key, uploadId, number, size, PRESIGN_LIFETIME),
                        sizeBytes = size,
                    )
                }
        }

    call.respond(
        UploadProgress(
            itemId = itemId.toString(),
            uploadId = uploadId,
            sizeBytes = total,
            received = received.map { ReceivedPart(it.partNumber, it.etag, it.sizeBytes) },
            remaining = remaining,
        ),
    )
}
