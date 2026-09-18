package com.mantel.features.share

import com.mantel.features.album.AlbumId
import com.mantel.features.album.AlbumItems
import com.mantel.features.album.Albums
import com.mantel.features.album.itemsOf
import com.mantel.features.media.ItemState
import com.mantel.features.media.MediaItems
import com.mantel.kernel.sha256Hex
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

@JvmInline
value class BundleId(val value: UUID) {
    override fun toString() = value.toString()
}

/** Display quality is the default; originals are an explicit choice with a warning (SDD.md 4.5). */
enum class BundleVariant {
    DISPLAY,
    ORIGINALS,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String): BundleVariant = entries.firstOrNull { it.wire == value } ?: error("unknown bundle variant: $value")

        fun of(originals: Boolean) = if (originals) ORIGINALS else DISPLAY
    }
}

enum class BundleStatus {
    BUILDING,
    READY,
    FAILED,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String): BundleStatus = entries.firstOrNull { it.wire == value } ?: error("unknown bundle status: $value")
    }
}

object AlbumBundles : Table("album_bundle") {
    val id = uuid("id").transform({ BundleId(it) }, { it.value })
    val albumId =
        reference("album_id", Albums.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION)
    val variant = text("variant").transform({ BundleVariant.fromWire(it) }, { it.wire })
    val fingerprint = text("fingerprint")
    val status = text("status").transform({ BundleStatus.fromWire(it) }, { it.wire })
    val key = text("key").nullable()
    val byteSize = long("byte_size").nullable()
    val attempts = integer("attempts")
    val lastError = text("last_error").nullable()
    val claimedAt = timestampWithTimeZone("claimed_at").nullable()
    val nextAttemptAt = timestampWithTimeZone("next_attempt_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val readyAt = timestampWithTimeZone("ready_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * What the album looked like when a bundle was built: the title, and every item's identity, order,
 * caption and derivative keys. A caption edited or a photograph reordered changes this, so the next
 * download rebuilds rather than handing over yesterday's album.
 */
fun fingerprintOf(
    albumId: AlbumId,
    variant: BundleVariant,
): String {
    val album = Albums.selectAll().where { Albums.id eq albumId }.single()
    val items =
        itemsOf(albumId)
            .joinToString("|") { row ->
                listOf(
                    row[MediaItems.id].toString(),
                    row[AlbumItems.position].toString(),
                    row[MediaItems.status].wire,
                    row[AlbumItems.caption].orEmpty(),
                    row[MediaItems.displayWebpKey].orEmpty(),
                    row[MediaItems.mp4Key].orEmpty(),
                    row[MediaItems.originalKey],
                ).joinToString(",")
            }
    return sha256Hex("${variant.wire}|${album[Albums.title].value}|${album[Albums.description].orEmpty()}|$items")
}

fun bundleKeyFor(id: BundleId): String = "bundles/$id.zip"

/**
 * `007-harbour-boats.webp`: the creator's own name, carrying the derivative's extension, prefixed so
 * a file manager sorting by name shows the album in its order. A name that arrived from a browser
 * is not trusted to be a path.
 */
fun bundleFilename(
    position: Int,
    original: String?,
    key: String,
): String {
    val extension = key.substringAfterLast('.', "bin")
    val stem =
        original
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.substringBeforeLast('.')
            ?.replace(Regex("[^A-Za-z0-9 ._-]"), "")
            // A stem of dots is how ".." survives everything above it.
            ?.trim(' ', '.', '_', '-')
            ?.replace(' ', '-')
            ?.take(60)
            ?.ifBlank { null }
            ?: "photo"
    return "%03d-%s.%s".format(position, stem, extension)
}

/** An album with nothing rendered has nothing to bundle. */
fun readyItemCount(albumId: AlbumId): Long =
    (AlbumItems innerJoin MediaItems)
        .selectAll()
        .where { (AlbumItems.albumId eq albumId) and (MediaItems.status eq ItemState.SHAREABLE) }
        .count()
