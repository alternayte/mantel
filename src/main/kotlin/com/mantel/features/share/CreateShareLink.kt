package com.mantel.features.share

import com.mantel.features.agent.Scope
import com.mantel.features.album.AlbumStatus
import com.mantel.features.album.Albums
import com.mantel.features.album.albumIdFrom
import com.mantel.features.album.demand
import com.mantel.features.media.ItemState
import com.mantel.features.media.MediaItems
import com.mantel.kernel.Clock
import com.mantel.kernel.Config
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.Ids
import com.mantel.kernel.db
import com.mantel.storage.ObjectStorage
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Serializable
data class CreateShareLinkRequest(val pin: String? = null, val expiresInDays: Int? = null)

@Serializable
data class ShareLinkView(
    val id: String,
    val url: String,
    val token: String,
    val hasPin: Boolean,
    val expiresAt: String? = null,
    val revokedAt: String? = null,
    val createdAt: String,
    val live: Boolean,
)

private val ALLOWED_EXPIRY_DAYS = setOf(7, 30, 90)

/**
 * Creating the first live link is what publishes an album; there is no separate publish action
 * (SDD.md 4.2).
 *
 * A link without a PIN gets a public copy of the cover thumbnail for link previews. A link with a
 * PIN does not: a preview is fetched by crawlers with no credentials, so showing the cover would
 * hand out a picture of the album to anyone holding a URL the creator deliberately protected.
 */
suspend fun createShareLink(
    call: ApplicationCall,
    config: Config,
    storage: ObjectStorage,
    clock: Clock = Clock.system,
) {
    val caller = com.mantel.features.agent.requireScope(call, Scope.SHARE_WRITE)
    val request = call.receive<CreateShareLinkRequest>()
    call.respond(
        HttpStatusCode.Created,
        createShareLinkFor(caller, albumIdFrom(call), request.pin, request.expiresInDays, config, storage, clock),
    )
}

/** The command. The route above and the MCP tool both call this and nothing else. */
suspend fun createShareLinkFor(
    caller: com.mantel.features.agent.Caller,
    albumIdValue: com.mantel.features.album.AlbumId,
    rawPin: String?,
    expiresInDays: Int?,
    config: Config,
    storage: ObjectStorage,
    clock: Clock = Clock.system,
): ShareLinkView {
    val album =
        com.mantel.features.album.requireOwnAlbumFor(caller.demand(Scope.SHARE_WRITE), albumIdValue)
    val pin = Pin.of(rawPin)

    expiresInDays?.let {
        if (it !in ALLOWED_EXPIRY_DAYS) {
            throw DomainException(
                ErrorCode.VALIDATION_FAILED,
                "A link expires after 7, 30 or 90 days, or never",
                mapOf("allowed" to ALLOWED_EXPIRY_DAYS.joinToString(", ")),
            )
        }
    }

    // An item that is only backed up has no derivative a viewer could open, and nothing is
    // rendering one. An item that is still uploading or processing is a different case: it becomes
    // shareable on its own, and the viewer shows a placeholder until it does (SDD.md 4.4).
    //
    // The refusal names the item, because "not ready" with four thousand photographs behind it is
    // not an answer.
    db {
        val waiting =
            (com.mantel.features.album.AlbumItems innerJoin MediaItems)
                .selectAll()
                .where {
                    (com.mantel.features.album.AlbumItems.albumId eq album[Albums.id]) and
                        (MediaItems.status eq ItemState.BACKED_UP)
                }
                .orderBy(com.mantel.features.album.AlbumItems.position)
                .firstOrNull()
        if (waiting != null) {
            throw DomainException(
                ErrorCode.CONFLICT,
                "${waiting[MediaItems.filename] ?: "An item"} is not ready to be shared yet",
                mapOf(
                    "itemId" to waiting[MediaItems.id].toString(),
                    "status" to waiting[MediaItems.status].wire,
                ),
            )
        }
    }

    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    val token = ShareToken(Ids.token())
    val id = ShareLinkId(Ids.uuidV7(clock))

    val coverKey =
        db {
            ShareLinks.insert {
                it[ShareLinks.id] = id
                it[albumId] = album[Albums.id]
                it[ShareLinks.token] = token
                it[pinHash] = pin?.let { value -> PinHash.hash(value) }
                it[expiresAt] = expiresInDays?.let { days -> now.plusDays(days.toLong()) }
                it[createdAt] = now
            }
            // The album is published the moment a live link exists.
            Albums.update({ Albums.id eq album[Albums.id] }) {
                it[status] = AlbumStatus.PUBLISHED
                it[publishedAt] = album[Albums.publishedAt] ?: now
                it[updatedAt] = now
            }
            if (pin == null) coverThumbFor(album[Albums.id], album[Albums.coverItemId]) else null
        }

    coverKey?.let { source ->
        withContext(Dispatchers.IO) {
            runCatching { storage.copy(source, ogKeyFor(token)) }
        }
    }

    return ShareLinkView(
        id = id.toString(),
        url = "${config.publicBaseUrl}/a/$token",
        token = token.value,
        hasPin = pin != null,
        expiresAt = expiresInDays?.let { now.plusDays(it.toLong()).toInstant().toString() },
        createdAt = now.toInstant().toString(),
        live = true,
    )
}

/** The album's cover thumbnail, or the first ready item's, or nothing to show yet. */
internal fun coverThumbFor(
    albumId: com.mantel.features.album.AlbumId,
    coverItemId: com.mantel.features.media.ItemId?,
): String? {
    val chosen =
        coverItemId?.let { id ->
            MediaItems.selectAll()
                .where { (MediaItems.id eq id) and (MediaItems.status eq ItemState.SHAREABLE) }
                .singleOrNull()
        }
            ?: com.mantel.features.album.itemsOf(albumId)
                .firstOrNull { it[MediaItems.status] == ItemState.SHAREABLE }
    return chosen?.get(MediaItems.thumbKey)
}
