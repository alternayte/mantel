package com.mantel.features.library

import com.mantel.features.account.AccountId
import com.mantel.features.agent.Scope
import com.mantel.features.agent.requireScope
import com.mantel.features.album.toItemView
import com.mantel.features.media.ItemId
import com.mantel.features.media.MediaItems
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.db
import com.mantel.storage.ObjectStorage
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * The library: every media item an account owns, newest taken first. The trash is not in it.
 *
 * It pages on (taken_at, id) rather than an offset. A backup adds photographs anywhere in the
 * timeline, not only at the front, and an offset would then show one photograph twice or skip one;
 * a position in the order stays where it was whatever arrives around it. The id breaks a tie
 * between two photographs taken in the same instant.
 */
@Serializable
data class LibraryPage(
    val items: List<com.mantel.features.album.ItemView>,
    val next: String? = null,
    val totalItems: Long,
)

private const val DEFAULT_PAGE = 100
private const val MAX_PAGE = 500

/**
 * A place in an order of (timestamp, id), newest first. Opaque to a client, which hands back the
 * `next` it was given.
 */
internal data class Cursor(val at: OffsetDateTime, val id: ItemId) {
    fun encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString("${at.toInstant()}|${id.value}".toByteArray())

    /** Rows after this one in `column DESC, id DESC` order. */
    fun after(column: Column<OffsetDateTime>): Op<Boolean> = (column less at) or ((column eq at) and (MediaItems.id less id))

    companion object {
        fun decode(raw: String): Cursor? =
            runCatching {
                val (at, id) = String(Base64.getUrlDecoder().decode(raw)).split('|', limit = 2)
                Cursor(OffsetDateTime.ofInstant(Instant.parse(at), ZoneOffset.UTC), ItemId(UUID.fromString(id)))
            }.getOrNull()
    }
}

internal data class PageRequest(val limit: Int, val after: String?)

internal fun pageRequestOf(call: ApplicationCall) =
    PageRequest(
        limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PAGE).coerceIn(1, MAX_PAGE),
        after = call.request.queryParameters["after"],
    )

suspend fun getLibrary(
    call: ApplicationCall,
    storage: ObjectStorage,
) {
    val accountId = requireScope(call, Scope.ALBUMS_READ).accountId
    val page = pageRequestOf(call)
    val after = page.after?.let { libraryCursorOf(accountId, it) }

    val rows =
        db {
            MediaItems.selectAll()
                .where {
                    val mine = (MediaItems.accountId eq accountId) and MediaItems.trashedAt.isNull()
                    if (after == null) mine else mine and after.after(MediaItems.takenAt)
                }
                .orderBy(MediaItems.takenAt to SortOrder.DESC, MediaItems.id to SortOrder.DESC)
                .limit(page.limit + 1)
                .toList()
        }
    val total =
        db { MediaItems.selectAll().where { (MediaItems.accountId eq accountId) and MediaItems.trashedAt.isNull() }.count() }

    val shown = rows.take(page.limit)
    val items = withContext(Dispatchers.IO) { shown.map { it.toItemView(storage) } }
    call.respond(
        LibraryPage(
            items = items,
            next =
                if (rows.size > page.limit) {
                    shown.last().let { Cursor(it[MediaItems.takenAt], it[MediaItems.id]).encode() }
                } else {
                    null
                },
            totalItems = total,
        ),
    )
}

/**
 * A cursor, or the bare item id a client released before the cursor existed sends. The phone app
 * already installed pages that way, and it keeps working: the id names a row, and the row has a
 * place in the order.
 */
private suspend fun libraryCursorOf(
    accountId: AccountId,
    raw: String,
): Cursor {
    Cursor.decode(raw)?.let { return it }
    val legacy =
        runCatching { ItemId(UUID.fromString(raw)) }.getOrNull()
            ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "That is not a page of the library")
    return db {
        MediaItems.selectAll()
            .where { (MediaItems.id eq legacy) and (MediaItems.accountId eq accountId) }
            .singleOrNull()
            ?.let { Cursor(it[MediaItems.takenAt], it[MediaItems.id]) }
    } ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "That is not a page of the library")
}
