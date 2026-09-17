package com.mantel.features.media

import com.mantel.features.account.Accounts
import com.mantel.features.account.storagePrefixFor
import com.mantel.features.album.Albums
import com.mantel.features.album.albumIdFrom
import com.mantel.features.album.requireOwnAlbum
import com.mantel.kernel.Clock
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
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Serializable
data class UploadIntentRequest(val files: List<DeclaredFile>)

@Serializable
data class DeclaredFile(val filename: String, val contentType: String, val sizeBytes: Long)

@Serializable
data class UploadIntentResponse(val items: List<PresignedUpload>, val expiresInSeconds: Long)

@Serializable
data class PresignedUpload(
    val itemId: String,
    val filename: String,
    val uploadUrl: String,
    val contentType: String,
    val sizeBytes: Long,
)

private val PRESIGN_LIFETIME: Duration = Duration.ofHours(1)
private const val MAX_BATCH = 200

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
    request.files.forEach { file ->
        if (file.sizeBytes <= 0) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "${file.filename} declares no bytes")
        }
        if (file.contentType !in ACCEPTED_TYPES) {
            throw DomainException(
                ErrorCode.VALIDATION_FAILED,
                "${file.contentType} is not a photo or video this can render",
                mapOf("accepted" to ACCEPTED_TYPES.keys.joinToString(", ")),
            )
        }
    }

    val declaredTotal = request.files.sumOf { it.sizeBytes }
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    // Reserve inside the same transaction that reads the quota, so two batches cannot both fit in
    // the same remaining space.
    val items =
        db {
            val account = Accounts.selectAll().where { Accounts.id eq accountId }.single()
            val remaining = account[Accounts.storageQuotaBytes] - account[Accounts.storageUsedBytes]
            if (declaredTotal > remaining) {
                throw DomainException(
                    ErrorCode.QUOTA_EXCEEDED,
                    "This batch needs more space than the account has left",
                    mapOf(
                        "requestedBytes" to declaredTotal.toString(),
                        "remainingBytes" to remaining.coerceAtLeast(0).toString(),
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
                request.files.mapIndexed { index, file ->
                    val itemId = UUID.randomUUID()
                    val (kind, extension) = ACCEPTED_TYPES.getValue(file.contentType)
                    val key = "${storagePrefixFor(accountId)}albums/$albumId/$itemId/original.$extension"
                    MediaItems.insert {
                        it[id] = itemId
                        it[MediaItems.albumId] = albumId
                        it[position] = startPosition + index
                        it[MediaItems.kind] = kind.wire
                        it[originalKey] = key
                        it[byteSize] = file.sizeBytes
                        it[status] = ItemState.PENDING_UPLOAD.wire
                        it[attempts] = 0
                        it[createdAt] = now
                    }
                    Triple(itemId, key, file)
                }

            Accounts.update({ Accounts.id eq accountId }) {
                it[storageUsedBytes] = account[Accounts.storageUsedBytes] + declaredTotal
            }
            Albums.update({ Albums.id eq albumId }) {
                it[itemCount] = album[Albums.itemCount] + created.size
                it[totalBytes] = album[Albums.totalBytes] + declaredTotal
                it[updatedAt] = now
            }
            created
        }

    val presigned =
        withContext(Dispatchers.IO) {
            items.map { (itemId, key, file) ->
                PresignedUpload(
                    itemId = itemId.toString(),
                    filename = file.filename,
                    uploadUrl = storage.presignPut(key, file.contentType, file.sizeBytes, PRESIGN_LIFETIME),
                    contentType = file.contentType,
                    sizeBytes = file.sizeBytes,
                )
            }
        }

    call.respond(UploadIntentResponse(presigned, PRESIGN_LIFETIME.seconds))
}
