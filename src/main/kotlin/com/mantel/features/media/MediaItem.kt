package com.mantel.features.media

import com.mantel.kernel.Bytes
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

/**
 * Comparable because the library pages by id. Ids are UUIDv7 and sort by creation time, so "the
 * next page" is "ids below this one" and stays correct while new media arrives at the front.
 */
@JvmInline
value class ItemId(val value: UUID) : Comparable<ItemId> {
    override fun toString() = value.toString()

    override fun compareTo(other: ItemId) = value.compareTo(other.value)
}

@JvmInline
value class Caption private constructor(val value: String) {
    override fun toString() = value

    companion object {
        const val MAX = 500

        /** Null for an absent or emptied caption, which is a caption's normal state. */
        fun of(raw: String?): Caption? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            if (trimmed.length > MAX) {
                throw DomainException(ErrorCode.VALIDATION_FAILED, "A caption is at most $MAX characters")
            }
            return Caption(trimmed)
        }
    }
}

enum class MediaKind {
    PHOTO,
    VIDEO,

    /** Kept, not rendered: a raw file, a GIF, anything the pipelines cannot open. */
    FILE,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String): MediaKind = entries.firstOrNull { it.wire == value } ?: error("unknown media kind: $value")
    }
}

object MediaItems : Table("media_item") {
    val id = uuid("id").transform({ ItemId(it) }, { it.value })
    val accountId =
        uuid("account_id").transform({ com.mantel.features.account.AccountId(it) }, { it.value })

    /** SHA-256 of the original bytes. Null for media that arrived before the hash existed. */
    val contentHash = text("content_hash").nullable()

    /** Whether anything in this product can turn the original into a derivative. */
    val renderable = bool("renderable")
    val kind = text("kind").transform({ MediaKind.fromWire(it) }, { it.wire })
    val filename = text("filename").nullable()
    val uploadId = text("upload_id").nullable()
    val originalKey = text("original_key")
    val thumbKey = text("thumb_key").nullable()
    val displayWebpKey = text("display_webp_key").nullable()
    val displayAvifKey = text("display_avif_key").nullable()
    val posterKey = text("poster_key").nullable()
    val mp4Key = text("mp4_key").nullable()
    val width = integer("width").nullable()
    val height = integer("height").nullable()
    val durationMs = integer("duration_ms").nullable()
    val byteSize = long("byte_size").transform({ Bytes(it) }, { it.value })
    val status = text("status").transform({ ItemState.fromWire(it) }, { it.wire })
    val attempts = integer("attempts")
    val lastError = text("last_error").nullable()
    val claimedAt = timestampWithTimeZone("claimed_at").nullable()
    val nextAttemptAt = timestampWithTimeZone("next_attempt_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val readyAt = timestampWithTimeZone("ready_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** The media item an account already holds with these bytes, or null. */
fun held(
    accountId: com.mantel.features.account.AccountId,
    contentHash: String,
): org.jetbrains.exposed.sql.ResultRow? =
    MediaItems.selectAll()
        .where { (MediaItems.accountId eq accountId) and (MediaItems.contentHash eq contentHash) }
        .singleOrNull()

/** The extension of a file the pipelines cannot classify. The name is all there is to go on. */
fun extensionOf(filename: String): String =
    filename.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }

/**
 * What this product can render. The library keeps a file outside this set and marks it unrenderable;
 * an album takes only what a viewer could be shown.
 */
val ACCEPTED_TYPES: Map<String, Pair<MediaKind, String>> =
    mapOf(
        "image/jpeg" to (MediaKind.PHOTO to "jpg"),
        "image/png" to (MediaKind.PHOTO to "png"),
        "image/webp" to (MediaKind.PHOTO to "webp"),
        "image/heic" to (MediaKind.PHOTO to "heic"),
        "image/heif" to (MediaKind.PHOTO to "heif"),
        "video/mp4" to (MediaKind.VIDEO to "mp4"),
        "video/quicktime" to (MediaKind.VIDEO to "mov"),
    )
