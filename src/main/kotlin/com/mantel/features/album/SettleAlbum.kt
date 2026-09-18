package com.mantel.features.album

import com.mantel.features.media.ItemState
import com.mantel.features.media.MediaItems
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

/**
 * One media item sits in any number of albums, so a change to it settles each of them. The worker
 * reports on an item, not on an album.
 */
fun settleAlbumsHolding(
    itemId: com.mantel.features.media.ItemId,
    now: OffsetDateTime,
) {
    AlbumItems.selectAll()
        .where { AlbumItems.mediaItemId eq itemId }
        .map { it[AlbumItems.albumId] }
        .forEach { settleAlbum(it, now) }
}

/**
 * An album is ready when every item has finished, one way or the other (SDD.md 4.2). It is called
 * after any change to an item's state rather than computed on read, because the viewer and the
 * creator both ask for album status far more often than items change.
 *
 * A published album stays published: a share link exists and revoking it is the only way back.
 */
fun settleAlbum(
    albumId: AlbumId,
    now: OffsetDateTime,
) {
    val album = Albums.selectAll().where { Albums.id eq albumId }.singleOrNull() ?: return
    if (album[Albums.status] !in setOf(AlbumStatus.DRAFT, AlbumStatus.READY)) return

    // An item that is only backed up is not finished as far as an album is concerned: the album
    // cannot publish until every derivative a viewer needs exists.
    val states =
        (AlbumItems innerJoin MediaItems)
            .selectAll()
            .where { AlbumItems.albumId eq albumId }
            .map { it[MediaItems.status] }
    val settled =
        states.isNotEmpty() && states.all { it == ItemState.SHAREABLE || it == ItemState.FAILED }
    val target = if (settled) AlbumStatus.READY else AlbumStatus.DRAFT

    if (album[Albums.status] != target) {
        Albums.update({ Albums.id eq albumId }) {
            it[status] = target
            it[updatedAt] = now
        }
    }
}
