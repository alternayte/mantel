package com.mantel.features.share

import com.mantel.features.agent.Scope
import com.mantel.features.agent.requireScope
import com.mantel.features.album.AlbumStatus
import com.mantel.features.album.Albums
import com.mantel.features.album.albumIdFrom
import com.mantel.features.album.demand
import com.mantel.features.album.requireOwnAlbum
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
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

fun ResultRow.toShareLinkView(
    config: Config,
    now: OffsetDateTime,
) = ShareLinkView(
    id = this[ShareLinks.id].toString(),
    url = "${config.publicBaseUrl}/a/${this[ShareLinks.token]}",
    token = this[ShareLinks.token].value,
    hasPin = this[ShareLinks.pinHash] != null,
    expiresAt = this[ShareLinks.expiresAt]?.toInstant()?.toString(),
    revokedAt = this[ShareLinks.revokedAt]?.toInstant()?.toString(),
    createdAt = this[ShareLinks.createdAt].toInstant().toString(),
    live = this.isLive(now),
)

fun ResultRow.isLive(now: OffsetDateTime): Boolean =
    this[ShareLinks.revokedAt] == null && (this[ShareLinks.expiresAt]?.isAfter(now) ?: true)

suspend fun listShareLinks(
    call: ApplicationCall,
    config: Config,
    clock: Clock = Clock.system,
) {
    val album = requireOwnAlbum(call, albumIdFrom(call), Scope.SHARE_WRITE)
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    val links =
        db {
            ShareLinks.selectAll()
                .where { ShareLinks.albumId eq album[Albums.id] }
                .orderBy(ShareLinks.createdAt to SortOrder.DESC)
                .map { it.toShareLinkView(config, now) }
        }
    call.respond(links)
}

/**
 * Revocation is immediate and takes the public preview image with it. Revoking the last live link
 * returns the album to ready: published means a live link exists, not that one once did.
 */
suspend fun revokeShareLink(
    call: ApplicationCall,
    storage: ObjectStorage,
    clock: Clock = Clock.system,
) {
    val caller = requireScope(call, Scope.SHARE_WRITE)
    revokeShareLinkFor(caller, call.parameters["id"].orEmpty(), storage, clock)
    call.respond(HttpStatusCode.NoContent)
}

/** The command. The route above and the MCP tool both call this and nothing else. */
suspend fun revokeShareLinkFor(
    caller: com.mantel.features.agent.Caller,
    rawLinkId: String,
    storage: ObjectStorage,
    clock: Clock = Clock.system,
) {
    val accountId = caller.demand(Scope.SHARE_WRITE).accountId
    val linkId =
        runCatching { ShareLinkId(UUID.fromString(rawLinkId)) }.getOrNull()
            ?: throw DomainException(ErrorCode.NOT_FOUND, "No such share link")
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    val token =
        db {
            val link =
                (ShareLinks innerJoin Albums)
                    .selectAll()
                    .where { (ShareLinks.id eq linkId) and (Albums.accountId eq accountId) }
                    .singleOrNull()
                    ?: throw DomainException(ErrorCode.NOT_FOUND, "No such share link")

            if (link[ShareLinks.revokedAt] == null) {
                ShareLinks.update({ ShareLinks.id eq linkId }) { it[revokedAt] = now }
            }

            val albumId = link[Albums.id]
            val anyLive =
                ShareLinks.selectAll()
                    .where {
                        (ShareLinks.albumId eq albumId) and
                            ShareLinks.revokedAt.isNull() and
                            (ShareLinks.expiresAt.isNull() or (ShareLinks.expiresAt greater now))
                    }
                    .any()
            if (!anyLive && link[Albums.status] == AlbumStatus.PUBLISHED) {
                Albums.update({ Albums.id eq albumId }) {
                    it[status] = AlbumStatus.READY
                    it[updatedAt] = now
                }
            }
            link[ShareLinks.token]
        }

    // The preview image is public, so it has to go when the link does.
    withContext(Dispatchers.IO) { runCatching { storage.delete(listOf(ogKeyFor(token))) } }
}
