package com.mantel.features.album

import com.mantel.features.media.ItemEvent
import com.mantel.features.media.ItemId
import com.mantel.features.media.ItemState
import com.mantel.features.media.MediaItems
import com.mantel.features.media.transition
import com.mantel.kernel.Bytes
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

/**
 * A media item's membership of an album.
 *
 * Position and caption live here rather than on the media item, because they are properties of the
 * membership: the same photograph sits in two albums at different places with different words under
 * it. Removing this row takes the photograph out of the album and leaves it in the library.
 */
object AlbumItems : Table("album_item") {
    val albumId = reference("album_id", Albums.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION)
    val mediaItemId =
        reference("media_item_id", MediaItems.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION)
    val position = integer("position")
    val caption = text("caption").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(albumId, mediaItemId)
}

/**
 * Puts a media item at the end of an album, and asks for the derivatives a viewer needs when the
 * item has only been backed up until now. Adding an item that is already there changes nothing.
 */
fun addToAlbum(
    albumId: AlbumId,
    itemId: ItemId,
    now: OffsetDateTime,
) {
    val item = MediaItems.selectAll().where { MediaItems.id eq itemId }.single()
    if (!item[MediaItems.renderable]) {
        throw DomainException(
            ErrorCode.VALIDATION_FAILED,
            "${item[MediaItems.filename] ?: "That file"} is kept in the library but cannot be shown in an album",
            mapOf("itemId" to itemId.toString()),
        )
    }

    val present =
        AlbumItems.selectAll()
            .where { (AlbumItems.albumId eq albumId) and (AlbumItems.mediaItemId eq itemId) }
            .any()
    if (present) return

    val next =
        (
            AlbumItems.select(AlbumItems.position.max()).where { AlbumItems.albumId eq albumId }
                .single()[AlbumItems.position.max()] ?: -1
        ) + 1
    AlbumItems.insert {
        it[AlbumItems.albumId] = albumId
        it[mediaItemId] = itemId
        it[position] = next
        it[createdAt] = now
    }

    if (item[MediaItems.status] == ItemState.BACKED_UP) {
        MediaItems.update({ MediaItems.id eq itemId }) {
            it[status] = transition(ItemState.BACKED_UP, ItemEvent.AlbumJoined)
            it[nextAttemptAt] = null
            it[attempts] = 0
        }
    }
}

/** An album's size and weight, counted from its membership rather than kept in step by hand. */
fun albumSizeOf(albumId: AlbumId): Int = AlbumItems.selectAll().where { AlbumItems.albumId eq albumId }.count().toInt()

fun albumBytesOf(albumId: AlbumId): Bytes =
    (AlbumItems innerJoin MediaItems)
        .selectAll()
        .where { AlbumItems.albumId eq albumId }
        .fold(Bytes.NONE) { total, row -> total + row[MediaItems.byteSize] }
