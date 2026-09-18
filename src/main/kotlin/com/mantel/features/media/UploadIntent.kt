package com.mantel.features.media

import com.mantel.features.account.Accounts
import com.mantel.features.account.mediaPrefixFor
import com.mantel.features.account.quota
import com.mantel.features.album.Albums
import com.mantel.features.album.albumIdFrom
import com.mantel.features.album.requireOwnAlbum
import com.mantel.kernel.Bytes
import com.mantel.kernel.Clock
import com.mantel.kernel.Config
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.Ids
import com.mantel.kernel.db
import com.mantel.storage.ObjectStorage
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Serializable
data class UploadIntentRequest(val files: List<DeclaredFile>)

@Serializable
data class DeclaredFile(val filename: String, val contentType: String, val sizeBytes: Long)

@Serializable
data class UploadIntentResponse(val items: List<PresignedUpload>, val expiresInSeconds: Long)

@Serializable
data class PresignedPart(val partNumber: Int, val uploadUrl: String, val sizeBytes: Long)

/**
 * One presigned PUT for a small file, or a part list for a large one. A client that meets `parts`
 * uploads each separately and may retry any of them alone.
 */
@Serializable
data class PresignedUpload(
    val itemId: String,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val uploadUrl: String? = null,
    val uploadId: String? = null,
    val parts: List<PresignedPart>? = null,
)

/** The part boundaries for a file, all full except the last. */
fun partsFor(
    total: Long,
    partSize: Long,
): List<Long> {
    val whole = total / partSize
    val remainder = total % partSize
    return List(whole.toInt()) { partSize } + if (remainder > 0) listOf(remainder) else emptyList()
}

private val PRESIGN_LIFETIME: Duration = Duration.ofHours(1)
private const val MAX_BATCH = 200

private data class Reserved(val id: ItemId, val key: String, val file: DeclaredFile)

/**
 * Quota is checked here, before any presigned URL exists, and the declared size is reserved at the
 * same moment. Checking afterwards would mean the bytes are already in storage when the answer
 * arrives. Each presigned PUT is signed for exactly the declared length and type, so the storage
 * provider refuses anything else; the client's word is never the enforcement.
 *
 * Bytes go from the browser to storage and never through this server.
 */
suspend fun createUploadIntent(
    call: ApplicationCall,
    config: Config,
    storage: ObjectStorage,
    clock: Clock = Clock.system,
) {
    val album = requireOwnAlbum(call, albumIdFrom(call))
    val albumId = album[Albums.id]
    val accountId = album[Albums.accountId]
    val request = call.receive<UploadIntentRequest>()

    if (request.files.isEmpty()) throw DomainException(ErrorCode.VALIDATION_FAILED, "No files declared")
    if (request.files.size > MAX_BATCH) {
        throw DomainException(ErrorCode.VALIDATION_FAILED, "At most $MAX_BATCH files in one batch")
    }
    val declared =
        request.files.map { file ->
            val size =
                runCatching { Bytes.of(file.sizeBytes) }.getOrElse {
                    throw DomainException(ErrorCode.VALIDATION_FAILED, "${file.filename} declares no bytes")
                }
            if (file.contentType !in ACCEPTED_TYPES) {
                throw DomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "${file.contentType} is not a photo or video this can render",
                    mapOf("accepted" to ACCEPTED_TYPES.keys.joinToString(", ")),
                )
            }
            file to size
        }

    val batchSize = declared.fold(Bytes.NONE) { total, (_, size) -> total + size }
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    // The account row is locked for the length of the reservation. One transaction is not enough on
    // its own: two of them read the same starting value and the second write overwrites the first,
    // so both batches fit in space for one. QuotaConcurrencyTest is that race.
    val reserved =
        db {
            val account = Accounts.selectAll().where { Accounts.id eq accountId }.forUpdate().single()
            val quota = account.quota()
            if (!quota.fits(batchSize)) {
                throw DomainException(
                    ErrorCode.QUOTA_EXCEEDED,
                    "This batch needs more space than the account has left",
                    mapOf(
                        "requestedBytes" to batchSize.value.toString(),
                        "remainingBytes" to quota.remaining.value.toString(),
                    ),
                )
            }

            val startPosition =
                (
                    MediaItems.select(MediaItems.position.max())
                        .where { MediaItems.albumId eq albumId }
                        .single()[MediaItems.position.max()] ?: -1
                ) + 1

            val created =
                declared.mapIndexed { index, (file, size) ->
                    val itemId = ItemId(Ids.uuidV7(clock))
                    val (kind, extension) = ACCEPTED_TYPES.getValue(file.contentType)
                    val key = "${mediaPrefixFor(itemId)}original.$extension"
                    MediaItems.insert {
                        it[id] = itemId
                        it[MediaItems.albumId] = albumId
                        it[position] = startPosition + index
                        it[MediaItems.kind] = kind
                        it[originalKey] = key
                        it[byteSize] = size
                        it[status] = ItemState.PENDING_UPLOAD
                        it[attempts] = 0
                        it[createdAt] = now
                    }
                    Reserved(itemId, key, file)
                }

            Accounts.update({ Accounts.id eq accountId }) {
                it[storageUsedBytes] = quota.reserve(batchSize).used
            }
            Albums.update({ Albums.id eq albumId }) {
                it[itemCount] = album[Albums.itemCount] + created.size
                it[totalBytes] = album[Albums.totalBytes] + batchSize
                it[updatedAt] = now
            }
            created
        }

    val presigned =
        withContext(Dispatchers.IO) {
            reserved.map { item ->
                if (item.file.sizeBytes <= config.storage.multipartThreshold.value) {
                    PresignedUpload(
                        itemId = item.id.toString(),
                        filename = item.file.filename,
                        contentType = item.file.contentType,
                        sizeBytes = item.file.sizeBytes,
                        uploadUrl =
                            storage.presignPut(item.key, item.file.contentType, item.file.sizeBytes, PRESIGN_LIFETIME),
                    )
                } else {
                    val uploadId = storage.startMultipartUpload(item.key, item.file.contentType)
                    val sizes = partsFor(item.file.sizeBytes, config.storage.partSize.value)
                    val parts =
                        sizes.mapIndexed { index, size ->
                            PresignedPart(
                                partNumber = index + 1,
                                uploadUrl = storage.presignPart(item.key, uploadId, index + 1, size, PRESIGN_LIFETIME),
                                sizeBytes = size,
                            )
                        }
                    db {
                        MediaItems.update({ MediaItems.id eq item.id }) { it[MediaItems.uploadId] = uploadId }
                    }
                    PresignedUpload(
                        itemId = item.id.toString(),
                        filename = item.file.filename,
                        contentType = item.file.contentType,
                        sizeBytes = item.file.sizeBytes,
                        uploadId = uploadId,
                        parts = parts,
                    )
                }
            }
        }

    call.respond(UploadIntentResponse(presigned, PRESIGN_LIFETIME.seconds))
}
