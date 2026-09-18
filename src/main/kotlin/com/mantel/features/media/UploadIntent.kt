package com.mantel.features.media

import com.mantel.features.account.Accounts
import com.mantel.features.account.mediaPrefixFor
import com.mantel.features.account.quota
import com.mantel.features.album.Albums
import com.mantel.features.album.addToAlbum
import com.mantel.features.album.albumBytesOf
import com.mantel.features.album.albumIdFrom
import com.mantel.features.album.albumSizeOf
import com.mantel.features.album.demand
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
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Serializable
data class UploadIntentRequest(val files: List<DeclaredFile>)

/**
 * `contentHash` is the SHA-256 of the original bytes. A client that sends one is told when the
 * library already holds the file, and sends no bytes at all.
 */
@Serializable
data class DeclaredFile(
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val contentHash: String? = null,
)

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
    /** The library already holds these bytes. No URL is issued and nothing is reserved. */
    val alreadyHeld: Boolean = false,
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

private data class Reserved(val id: ItemId, val key: String, val file: DeclaredFile, val held: Boolean)

/** base64url and hex both appear in the wild; a SHA-256 is 64 hex characters here. */
private val HASH = Regex("^[0-9a-f]{64}$")

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
    val caller = com.mantel.features.agent.requireScope(call, com.mantel.features.agent.Scope.ALBUMS_WRITE)
    val request = call.receive<UploadIntentRequest>()
    call.respond(uploadIntentFor(caller, albumIdFrom(call), request.files, config, storage, clock))
}

/** The same intent with no album behind it: media goes to the library and is selected later. */
suspend fun createLibraryUploadIntent(
    call: ApplicationCall,
    config: Config,
    storage: ObjectStorage,
    clock: Clock = Clock.system,
) {
    val caller = com.mantel.features.agent.requireScope(call, com.mantel.features.agent.Scope.ALBUMS_WRITE)
    val request = call.receive<UploadIntentRequest>()
    call.respond(uploadIntentFor(caller, null, request.files, config, storage, clock))
}

/** The command. The route above and the MCP tool both call this and nothing else. */
suspend fun uploadIntentFor(
    caller: com.mantel.features.agent.Caller,
    albumIdValue: com.mantel.features.album.AlbumId?,
    files: List<DeclaredFile>,
    config: Config,
    storage: ObjectStorage,
    clock: Clock = Clock.system,
): UploadIntentResponse {
    val owner = caller.demand(com.mantel.features.agent.Scope.ALBUMS_WRITE)
    val album = albumIdValue?.let { com.mantel.features.album.requireOwnAlbumFor(owner, it) }
    val albumId = album?.get(Albums.id)
    val accountId = album?.get(Albums.accountId) ?: owner.accountId
    val request = UploadIntentRequest(files)

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
            // The format check used to refuse anything the pipelines cannot open. The library keeps
            // what the camera produced, so the ceiling is what refuses a file now.
            if (size > config.maxFileBytes) {
                throw DomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "${file.filename} is larger than this instance accepts",
                    mapOf("maxBytes" to config.maxFileBytes.value.toString()),
                )
            }
            // An album shows photographs. A file the product cannot open is kept in the library and
            // is not something a viewer could be shown.
            if (albumIdValue != null && file.contentType !in ACCEPTED_TYPES) {
                throw DomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "${file.contentType} is not a photo or video this can render",
                    mapOf("accepted" to ACCEPTED_TYPES.keys.joinToString(", ")),
                )
            }
            file.contentHash?.let {
                if (!HASH.matches(it)) {
                    throw DomainException(ErrorCode.VALIDATION_FAILED, "${file.filename} declares a malformed hash")
                }
            }
            file to size
        }

    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    // The account row is locked for the length of the reservation. One transaction is not enough on
    // its own: two of them read the same starting value and the second write overwrites the first,
    // so both batches fit in space for one. QuotaConcurrencyTest is that race.
    val reserved =
        db {
            val account = Accounts.selectAll().where { Accounts.id eq accountId }.forUpdate().single()
            val quota = account.quota()

            // Only bytes the account does not already hold cost anything. A photograph sent twice
            // counts once, whatever number of albums point at it.
            val alreadyHeld =
                declared.mapNotNull { (file, _) -> file.contentHash }
                    .distinct()
                    .mapNotNull { hash -> held(accountId, hash)?.let { hash to it } }
                    .toMap()
            val batchSize =
                declared.filterNot { (file, _) -> file.contentHash in alreadyHeld.keys }
                    .fold(Bytes.NONE) { total, (_, size) -> total + size }

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

            val created =
                declared.map { (file, size) ->
                    val existing = alreadyHeld[file.contentHash]
                    if (existing != null) {
                        albumId?.let { addToAlbum(it, existing[MediaItems.id], now) }
                        return@map Reserved(existing[MediaItems.id], existing[MediaItems.originalKey], file, held = true)
                    }
                    val itemId = ItemId(Ids.uuidV7(clock))
                    val classified = ACCEPTED_TYPES[file.contentType]
                    val extension = classified?.second ?: extensionOf(file.filename)
                    val key = "${mediaPrefixFor(itemId)}original.$extension"
                    MediaItems.insert {
                        it[id] = itemId
                        it[MediaItems.accountId] = accountId
                        it[contentHash] = file.contentHash
                        it[renderable] = classified != null
                        it[MediaItems.kind] = classified?.first ?: MediaKind.FILE
                        it[MediaItems.filename] = file.filename.take(200)
                        it[originalKey] = key
                        it[byteSize] = size
                        it[status] = ItemState.PENDING_UPLOAD
                        it[attempts] = 0
                        it[createdAt] = now
                    }
                    albumId?.let { addToAlbum(it, itemId, now) }
                    Reserved(itemId, key, file, held = false)
                }

            Accounts.update({ Accounts.id eq accountId }) {
                it[storageUsedBytes] = quota.reserve(batchSize).used
            }
            albumId?.let { id ->
                Albums.update({ Albums.id eq id }) {
                    it[itemCount] = albumSizeOf(id)
                    it[totalBytes] = albumBytesOf(id)
                    it[updatedAt] = now
                }
            }
            created
        }

    val presigned =
        withContext(Dispatchers.IO) {
            reserved.map { item ->
                if (item.held) {
                    PresignedUpload(
                        itemId = item.id.toString(),
                        filename = item.file.filename,
                        contentType = item.file.contentType,
                        sizeBytes = item.file.sizeBytes,
                        alreadyHeld = true,
                    )
                } else if (item.file.sizeBytes <= config.storage.multipartThreshold.value) {
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

    return UploadIntentResponse(presigned, PRESIGN_LIFETIME.seconds)
}
