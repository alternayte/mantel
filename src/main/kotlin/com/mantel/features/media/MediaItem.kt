package com.mantel.features.media

import com.mantel.features.album.Albums
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object MediaItems : Table("media_item") {
    val id = uuid("id")
    val albumId = reference("album_id", Albums.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION)
    val position = integer("position")
    val kind = text("kind")
    val originalKey = text("original_key")
    val thumbKey = text("thumb_key").nullable()
    val displayWebpKey = text("display_webp_key").nullable()
    val displayAvifKey = text("display_avif_key").nullable()
    val posterKey = text("poster_key").nullable()
    val mp4Key = text("mp4_key").nullable()
    val width = integer("width").nullable()
    val height = integer("height").nullable()
    val durationMs = integer("duration_ms").nullable()
    val byteSize = long("byte_size")
    val caption = text("caption").nullable()
    val status = text("status")
    val attempts = integer("attempts")
    val lastError = text("last_error").nullable()
    val claimedAt = timestampWithTimeZone("claimed_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val readyAt = timestampWithTimeZone("ready_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

enum class MediaKind {
    PHOTO,
    VIDEO,
    ;

    val wire: String get() = name.lowercase()
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
