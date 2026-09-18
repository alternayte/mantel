package com.mantel.features.share

import com.mantel.features.viewer.hasUnlocked
import com.mantel.features.viewer.resolveToken
import com.mantel.features.viewer.tokenFrom
import com.mantel.kernel.Clock
import com.mantel.kernel.Config
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.Ids
import com.mantel.kernel.db
import com.mantel.storage.ObjectStorage
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Serializable
data class BundleProgress(val status: String, val itemCount: Long, val message: String)

private val DOWNLOAD_LIFETIME: Duration = Duration.ofHours(6)

/**
 * The bundle is built by the worker into object storage, and this endpoint hands back a URL to it.
 * The API never assembles the ZIP: a two gigabyte album would otherwise be read, zipped and written
 * by the one process that also answers every other request (SDD.md 3.2).
 *
 * A fingerprint of the album decides whether the stored bundle is still the album. If it is not,
 * this asks for a new one rather than handing over yesterday's photographs.
 */
suspend fun requestBundle(
    call: ApplicationCall,
    config: Config,
    storage: ObjectStorage,
    clock: Clock = Clock.system,
) {
    val token = tokenFrom(call)
    val linked = resolveToken(token, clock)
    if (linked.needsPin && !call.hasUnlocked(token, config.cookieSecret, clock)) {
        throw DomainException(ErrorCode.PIN_REQUIRED, "This album is protected by a PIN")
    }

    val variant = BundleVariant.of(call.request.queryParameters["originals"]?.toBoolean() ?: false)
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    val outcome =
        db {
            val ready = readyItemCount(linked.albumId)
            if (ready == 0L) {
                throw DomainException(ErrorCode.CONFLICT, "There is nothing to download yet")
            }
            val fingerprint = fingerprintOf(linked.albumId, variant)
            val existing =
                AlbumBundles.selectAll()
                    .where { (AlbumBundles.albumId eq linked.albumId) and (AlbumBundles.variant eq variant) }
                    .singleOrNull()

            when {
                existing != null &&
                    existing[AlbumBundles.fingerprint] == fingerprint &&
                    existing[AlbumBundles.status] == BundleStatus.READY ->
                    Ready(existing[AlbumBundles.key]!!, ready)

                // Being built, and still for this album: wait rather than queue a second copy.
                existing != null &&
                    existing[AlbumBundles.fingerprint] == fingerprint &&
                    existing[AlbumBundles.status] == BundleStatus.BUILDING -> Building(ready)

                else -> {
                    val id = existing?.get(AlbumBundles.id) ?: BundleId(Ids.uuidV7(clock))
                    if (existing == null) {
                        AlbumBundles.insert {
                            it[AlbumBundles.id] = id
                            it[albumId] = linked.albumId
                            it[AlbumBundles.variant] = variant
                            it[AlbumBundles.fingerprint] = fingerprint
                            it[status] = BundleStatus.BUILDING
                            it[attempts] = 0
                            it[createdAt] = now
                        }
                    } else {
                        AlbumBundles.update({ AlbumBundles.id eq id }) {
                            it[AlbumBundles.fingerprint] = fingerprint
                            it[status] = BundleStatus.BUILDING
                            it[attempts] = 0
                            it[lastError] = null
                            it[claimedAt] = null
                            it[nextAttemptAt] = null
                            it[key] = null
                            it[byteSize] = null
                        }
                    }
                    Building(ready)
                }
            }
        }

    when (outcome) {
        is Ready -> {
            val url = withContext(Dispatchers.IO) { storage.presignGetForThisHour(outcome.key, DOWNLOAD_LIFETIME) }
            call.respondRedirect(url)
        }
        is Building ->
            call.respond(
                HttpStatusCode.Accepted,
                BundleProgress(
                    status = "building",
                    itemCount = outcome.itemCount,
                    message = "The album is being packed. Ask again in a moment.",
                ),
            )
    }
}

private sealed interface Outcome

private data class Ready(val key: String, val itemCount: Long) : Outcome

private data class Building(val itemCount: Long) : Outcome
