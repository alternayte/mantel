package com.mantel.features.media

import com.mantel.features.album.Albums
import com.mantel.kernel.Bytes
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import java.util.UUID

@JvmInline
value class ItemId(val value: UUID) {
    override fun toString() = value.toString()
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
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String): MediaKind = entries.firstOrNull { it.wire == value } ?: error("unknown media kind: $value")
    }
}

object MediaItems : Table("media_item") {
    val id = uuid("id").transform({ ItemId(it) }, { it.value })
    val albumId =
        reference("album_id", Albums.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION)
    val position = integer("position")
    val kind = text("kind").transform({ MediaKind.fromWire(it) }, { it.wire })
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
    val caption = text("caption").nullable()
    val status = text("status").transform({ ItemState.fromWire(it) }, { it.wire })
    val attempts = integer("attempts")
    val lastError = text("last_error").nullable()
    val claimedAt = timestampWithTimeZone("claimed_at").nullable()
    val nextAttemptAt = timestampWithTimeZone("next_attempt_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val readyAt = timestampWithTimeZone("ready_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * What an upload may be. A type outside this set is refused at intent, before a presigned URL
 * exists, because the worker can only render what it can read.
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
