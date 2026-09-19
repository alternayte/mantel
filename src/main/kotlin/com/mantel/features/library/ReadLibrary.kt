package com.mantel.features.library

import com.mantel.features.account.Accounts
import com.mantel.features.account.quota
import com.mantel.features.agent.Scope
import com.mantel.features.agent.requireScope
import com.mantel.features.album.AlbumItems
import com.mantel.features.album.Albums
import com.mantel.features.album.albumBytesOf
import com.mantel.features.album.albumSizeOf
import com.mantel.features.album.settleAlbum
import com.mantel.features.album.toItemView
import com.mantel.features.media.ItemId
import com.mantel.features.media.MediaItems
import com.mantel.kernel.Clock
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The library: every media item an account owns, newest first.
 *
 * It pages by the item id rather than an offset. Ids are UUIDv7 and sort by creation time, so a
 * page boundary stays where it was while new media arrives at the front — an offset would show the
 * same photograph twice or skip one.
 */
@Serializable
data class LibraryPage(
    val items: List<com.mantel.features.album.ItemView>,
    val next: String? = null,
    val totalItems: Long,
)

private const val DEFAULT_PAGE = 100
private const val MAX_PAGE = 500

suspend fun getLibrary(
    call: ApplicationCall,
    storage: ObjectStorage,
) {
    val caller = requireScope(call, Scope.ALBUMS_READ)
    val accountId = caller.accountId
    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PAGE).coerceIn(1, MAX_PAGE)
    val after =
        call.request.queryParameters["after"]?.let {
            runCatching { ItemId(UUID.fromString(it)) }.getOrNull()
                ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "That is not an item id")
        }

    val rows =
        db {
            MediaItems.selectAll()
                .where {
                    if (after == null) {
                        MediaItems.accountId eq accountId
                    } else {
                        (MediaItems.accountId eq accountId) and (MediaItems.id.less(after))
                    }
                }
                .orderBy(MediaItems.id to SortOrder.DESC)
                .limit(limit + 1)
                .toList()
        }
    val total = db { MediaItems.selectAll().where { MediaItems.accountId eq accountId }.count() }

    val page = rows.take(limit)
    val items = withContext(Dispatchers.IO) { page.map { it.toItemView(storage) } }
    call.respond(
        LibraryPage(
            items = items,
            next = if (rows.size > limit) page.last()[MediaItems.id].toString() else null,
            totalItems = total,
        ),
    )
}

/**
 * Deleting from the library is the only deletion that removes bytes. It takes the item out of every
 * album that holds it, because an album cannot point at a photograph that no longer exists.
 */
suspend fun deleteFromLibrary(
    call: ApplicationCall,
    storage: ObjectStorage,
    clock: Clock = Clock.system,
) {
    val accountId = requireScope(call, Scope.ALBUMS_WRITE).accountId
    val itemId =
        call.parameters["itemId"]?.let { runCatching { ItemId(UUID.fromString(it)) }.getOrNull() }
            ?: throw DomainException(ErrorCode.NOT_FOUND, "No such item")

    val item =
        db {
            MediaItems.selectAll()
                .where { (MediaItems.id eq itemId) and (MediaItems.accountId eq accountId) }
                .singleOrNull()
        } ?: throw DomainException(ErrorCode.NOT_FOUND, "No such item")

    removeItem(item, storage, OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC))
    call.respond(HttpStatusCode.NoContent)
}

/**
 * Takes one item out of the library: its bytes, its place in any album, and the quota it held.
 *
 * `deleteFromLibrary` is a person doing this deliberately; the abandoned-upload sweep does the same
 * thing to a row whose bytes never arrived. Both have to clean the same things, so both call this.
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
        val affected =
            AlbumItems.selectAll().where { AlbumItems.mediaItemId eq itemId }.map { it[AlbumItems.albumId] }
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
            Albums.update({ Albums.id eq albumId }) {
                it[itemCount] = albumSizeOf(albumId)
                it[totalBytes] = albumBytesOf(albumId)
                it[updatedAt] = now
            }
            settleAlbum(albumId, now)
        }

        val account = Accounts.selectAll().where { Accounts.id eq item[MediaItems.accountId] }.single()
        Accounts.update({ Accounts.id eq item[MediaItems.accountId] }) {
            it[storageUsedBytes] = account.quota().release(item[MediaItems.byteSize]).used
        }
    }
}
