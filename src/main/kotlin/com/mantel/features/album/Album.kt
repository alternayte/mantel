package com.mantel.features.album

import com.mantel.features.account.AccountId
import com.mantel.features.account.Accounts
import com.mantel.features.auth.requireAccountId
import com.mantel.features.media.ItemId
import com.mantel.kernel.Bytes
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.db
import io.ktor.server.application.ApplicationCall
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

@JvmInline
value class AlbumId(val value: UUID) {
    override fun toString() = value.toString()
}

/**
 * A title exists once, here, rather than being re-checked in every handler that accepts one.
 */
@JvmInline
value class AlbumTitle private constructor(val value: String) {
    override fun toString() = value

    companion object {
        const val MAX = 200

        fun of(raw: String): AlbumTitle {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX) {
                throw DomainException(ErrorCode.VALIDATION_FAILED, "A title is between 1 and $MAX characters")
            }
            return AlbumTitle(trimmed)
        }
    }
}

/**
 * An album is assembled as a draft, becomes ready when every item has finished processing, and
 * becomes published when a live share link exists (M5). Publishing is not a separate action.
 */
enum class AlbumStatus {
    DRAFT,
    READY,
    PUBLISHED,
    ARCHIVED,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String): AlbumStatus = entries.firstOrNull { it.wire == value } ?: error("unknown album status: $value")
    }
}

object Albums : Table("album") {
    val id = uuid("id").transform({ AlbumId(it) }, { it.value })
    val accountId =
        reference("account_id", Accounts.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION)
    val title = text("title").transform({ AlbumTitle.of(it) }, { it.value })
    val description = text("description").nullable()
    val coverItemId = uuid("cover_item_id").transform({ ItemId(it) }, { it.value }).nullable()
    val status = text("status").transform({ AlbumStatus.fromWire(it) }, { it.wire })
    val itemCount = integer("item_count")
    val totalBytes = long("total_bytes").transform({ Bytes(it) }, { it.value })
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val publishedAt = timestampWithTimeZone("published_at").nullable()
    val archivedAt = timestampWithTimeZone("archived_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * The album, if this account owns it. Anything else is a 404: a creator learns nothing about an
 * album belonging to someone else, not even that it exists.
 */
suspend fun requireOwnAlbum(
    call: ApplicationCall,
    albumId: AlbumId,
): ResultRow {
    val accountId: AccountId = requireAccountId(call)
    return db {
        Albums.selectAll()
            .where { (Albums.id eq albumId) and (Albums.accountId eq accountId) and Albums.archivedAt.isNull() }
            .singleOrNull()
    } ?: throw DomainException(ErrorCode.NOT_FOUND, "No such album")
}

fun albumIdFrom(call: ApplicationCall): AlbumId =
    call.parameters["id"]?.let { runCatching { AlbumId(UUID.fromString(it)) }.getOrNull() }
        ?: throw DomainException(ErrorCode.NOT_FOUND, "No such album")

fun itemIdFrom(call: ApplicationCall): ItemId =
    call.parameters["itemId"]?.let { runCatching { ItemId(UUID.fromString(it)) }.getOrNull() }
        ?: throw DomainException(ErrorCode.NOT_FOUND, "No such item")
