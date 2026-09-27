package com.mantel.features.library

import com.mantel.features.account.Accounts
import com.mantel.features.account.quota
import com.mantel.features.agent.Scope
import com.mantel.features.agent.requireScope
import com.mantel.features.album.AlbumItems
import com.mantel.features.album.itemIdFrom
import com.mantel.features.album.recountAlbum
import com.mantel.features.album.toItemView
import com.mantel.features.media.MediaItems
import com.mantel.features.media.requireWorker
import com.mantel.kernel.Clock
import com.mantel.kernel.Config
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.db
import com.mantel.storage.ObjectStorage
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * How long a deleted media item waits before its bytes go. Until then it can be restored, and it
 * counts against quota, because its bytes are still in storage.
 */
val TRASH_KEEPS: Duration = Duration.ofDays(30)

/**
 * Deleting from the library moves the item to the trash. It leaves the timeline and every album
 * that holds it, and a share link to one of those albums keeps working without it: a recipient sees
 * the album as it is. Its memberships are kept, so a restore puts it back where it was.
 *
 * Delete stays the one verb and the trash is its undo. No client can remove a photograph for good
 * in one step; that is what `DELETE /api/library/trash/{itemId}` is for.
 */
suspend fun trashItem(
    call: ApplicationCall,
    clock: Clock = Clock.system,
) {
    val accountId = requireScope(call, Scope.ALBUMS_WRITE).accountId
    val itemId = itemIdFrom(call)
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    db {
        val item =
            MediaItems.selectAll()
                .where { (MediaItems.id eq itemId) and (MediaItems.accountId eq accountId) }
                .singleOrNull()
                ?: throw DomainException(ErrorCode.NOT_FOUND, "No such item")
        // Deleting twice is one deletion, and the 30 days run from the first.
        if (item[MediaItems.trashedAt] == null) {
            MediaItems.update({ MediaItems.id eq itemId }) { it[trashedAt] = now }
            albumsHolding(item).forEach { recountAlbum(it, now) }
        }
    }
    call.respond(HttpStatusCode.NoContent)
}

/** Out of the trash, back into the timeline and into every album it was in, at its old place. */
suspend fun restoreItem(
    call: ApplicationCall,
    clock: Clock = Clock.system,
) {
    val accountId = requireScope(call, Scope.ALBUMS_WRITE).accountId
    val itemId = itemIdFrom(call)
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    db {
        val item =
            MediaItems.selectAll()
                .where { (MediaItems.id eq itemId) and (MediaItems.accountId eq accountId) }
                .singleOrNull()
                ?: throw DomainException(ErrorCode.NOT_FOUND, "No such item")
        if (item[MediaItems.trashedAt] != null) {
            MediaItems.update({ MediaItems.id eq itemId }) { it[trashedAt] = null }
            albumsHolding(item).forEach { recountAlbum(it, now) }
        }
    }
    call.respond(HttpStatusCode.NoContent)
}

/** The trash, newest deletion first. Pages on (trashed_at, id) the way the library pages on taken_at. */
suspend fun getTrash(
    call: ApplicationCall,
    storage: ObjectStorage,
) {
    val accountId = requireScope(call, Scope.ALBUMS_READ).accountId
    val page = pageRequestOf(call)
    val after =
        page.after?.let {
            Cursor.decode(it) ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "That is not a page of the trash")
        }

    val rows =
        db {
            MediaItems.selectAll()
                .where {
                    val trashed = (MediaItems.accountId eq accountId) and MediaItems.trashedAt.isNotNull()
                    // A trashed row always has the column, so the cursor may treat it as present.

                    @Suppress("UNCHECKED_CAST")
                    val column = MediaItems.trashedAt as org.jetbrains.exposed.sql.Column<OffsetDateTime>
                    if (after == null) trashed else trashed and after.after(column)
                }
                .orderBy(MediaItems.trashedAt to SortOrder.DESC, MediaItems.id to SortOrder.DESC)
                .limit(page.limit + 1)
                .toList()
        }
    val total =
        db { MediaItems.selectAll().where { (MediaItems.accountId eq accountId) and MediaItems.trashedAt.isNotNull() }.count() }

    val shown = rows.take(page.limit)
    val items = withContext(Dispatchers.IO) { shown.map { it.toItemView(storage) } }
    call.respond(
        LibraryPage(
            items = items,
            next =
                if (rows.size > page.limit) {
                    shown.last().let { Cursor(it[MediaItems.trashedAt]!!, it[MediaItems.id]).encode() }
                } else {
                    null
                },
            totalItems = total,
        ),
    )
}

/** Removes one item from the trash now rather than in 30 days: its bytes, and the quota they held. */
suspend fun removeFromTrash(
    call: ApplicationCall,
    storage: ObjectStorage,
    clock: Clock = Clock.system,
) {
    val accountId = requireScope(call, Scope.ALBUMS_WRITE).accountId
    val itemId = itemIdFrom(call)

    // Only from the trash. A photograph in the library goes there first, so no client removes one
    // for good in a single step.
    val item =
        db {
            MediaItems.selectAll()
                .where {
                    (MediaItems.id eq itemId) and (MediaItems.accountId eq accountId) and MediaItems.trashedAt.isNotNull()
                }
                .singleOrNull()
        } ?: throw DomainException(ErrorCode.NOT_FOUND, "No such item in the trash")

    removeItem(item, storage, OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC))
    call.respond(HttpStatusCode.NoContent)
}

@Serializable
data class TrashSweep(val removed: Int)

/** The worker's sweep: whatever has been in the trash longer than [TRASH_KEEPS] goes for good. */
suspend fun sweepTrash(
    call: ApplicationCall,
    storage: ObjectStorage,
    config: Config,
    clock: Clock,
) {
    requireWorker(call, config)
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    val trashedBefore = now.minus(TRASH_KEEPS)

    val expired =
        db {
            MediaItems.selectAll()
                .where { MediaItems.trashedAt.isNotNull() and (MediaItems.trashedAt less trashedBefore) }
                .toList()
        }
    expired.forEach { removeItem(it, storage, now) }
    call.respond(TrashSweep(removed = expired.size))
}

private fun albumsHolding(item: ResultRow) =
    AlbumItems.selectAll().where { AlbumItems.mediaItemId eq item[MediaItems.id] }.map { it[AlbumItems.albumId] }

/**
 * Takes one item out of the library for good: its bytes, its place in any album, and the quota it
 * held.
 *
 * The trash sweep and "remove now" are a person's deletion finishing; the abandoned-upload sweep
 * does the same thing to a row whose bytes never arrived. All of them have to clean the same
 * things, so all of them call this.
 */
suspend fun removeItem(
    item: ResultRow,
    storage: ObjectStorage,
    now: OffsetDateTime,
) {
    val itemId = item[MediaItems.id]
    val prefix = item[MediaItems.originalKey].substringBeforeLast('/') + "/"
    withContext(Dispatchers.IO) {
        // An unfinished multipart upload holds bytes that no listing shows and no row points at.
        item[MediaItems.uploadId]?.let { storage.abortMultipartUpload(item[MediaItems.originalKey], it) }
        storage.deletePrefix(prefix)
    }

    db {
        val affected = albumsHolding(item)
        MediaItems.deleteWhere { MediaItems.id eq itemId }

        affected.forEach { albumId ->
            AlbumItems.selectAll()
                .where { AlbumItems.albumId eq albumId }
                .orderBy(AlbumItems.position)
                .map { it[AlbumItems.mediaItemId] }
                .forEachIndexed { index, id ->
                    AlbumItems.update({ (AlbumItems.albumId eq albumId) and (AlbumItems.mediaItemId eq id) }) {
                        it[position] = index
                    }
                }
            recountAlbum(albumId, now)
        }

        val account = Accounts.selectAll().where { Accounts.id eq item[MediaItems.accountId] }.single()
        Accounts.update({ Accounts.id eq item[MediaItems.accountId] }) {
            it[storageUsedBytes] = account.quota().release(item[MediaItems.byteSize]).used
        }
    }
}
