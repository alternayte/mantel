package com.mantel.features.share

import com.mantel.features.album.Albums
import com.mantel.features.media.ItemState
import com.mantel.features.media.MediaItems
import com.mantel.features.media.backoffFor
import com.mantel.features.media.requireWorker
import com.mantel.kernel.Clock
import com.mantel.kernel.Config
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.db
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Serializable
data class BundleEntry(
    val filename: String,
    val key: String,
    val kind: String,
    val caption: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class ClaimedBundle(
    val bundleId: String,
    val variant: String,
    val albumTitle: String,
    val albumDescription: String? = null,
    val targetKey: String,
    val includesOriginals: Boolean,
    val entries: List<BundleEntry>,
    val heartbeatSeconds: Long,
)

@Serializable
data class BundleBuiltRequest(val key: String, val byteSize: Long)

/**
 * The worker asks for bundles the same way it asks for photographs, and for the same reason: it
 * holds no database credentials, so the claim runs here (SDD.md 6.3).
 *
 * Everything the worker needs to write the ZIP is in the answer — the file names, the storage keys
 * and the captions — because the worker cannot look anything up.
 */
suspend fun claimBundles(
    call: ApplicationCall,
    config: Config,
    clock: Clock,
) {
    requireWorker(call, config)
    val limit = call.receive<com.mantel.features.media.ClaimRequest>().limit.coerceIn(1, 5)
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    val staleBefore = now.minus(config.worker.claimTimeout)

    val claimed =
        db {
            val sql =
                """
                   UPDATE album_bundle
                      SET claimed_at = ?, attempts = attempts + 1
                    WHERE id IN (
                          SELECT id FROM album_bundle
                           WHERE status = 'building'
                             AND (claimed_at IS NULL OR claimed_at < ?)
                             AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                           ORDER BY created_at
                           LIMIT ?
                             FOR UPDATE SKIP LOCKED
                          )
                RETURNING id, album_id, variant, attempts
                """.trimIndent()

            val connection = TransactionManager.current().connection.connection as java.sql.Connection
            val rows =
                connection.prepareStatement(sql).use { statement ->
                    statement.setObject(1, now)
                    statement.setObject(2, staleBefore)
                    statement.setObject(3, now)
                    statement.setInt(4, limit)
                    statement.executeQuery().use { result ->
                        buildList {
                            while (result.next()) {
                                add(
                                    Triple(
                                        UUID.fromString(result.getString("id")),
                                        UUID.fromString(result.getString("album_id")),
                                        BundleVariant.fromWire(result.getString("variant")),
                                    ),
                                )
                            }
                        }
                    }
                }

            rows.map { (bundleId, albumId, variant) ->
                val album = Albums.selectAll().where { Albums.id eq com.mantel.features.album.AlbumId(albumId) }.single()
                val items =
                    MediaItems.selectAll()
                        .where {
                            (MediaItems.albumId eq com.mantel.features.album.AlbumId(albumId)) and
                                (MediaItems.status eq ItemState.READY)
                        }
                        .orderBy(MediaItems.position to SortOrder.ASC)
                        .toList()

                ClaimedBundle(
                    bundleId = bundleId.toString(),
                    variant = variant.wire,
                    albumTitle = album[Albums.title].value,
                    albumDescription = album[Albums.description],
                    targetKey = bundleKeyFor(BundleId(bundleId)),
                    includesOriginals = variant == BundleVariant.ORIGINALS,
                    heartbeatSeconds = config.worker.claimTimeout.seconds / 3,
                    entries =
                        items.mapIndexed { index, row ->
                            val isVideo = row[MediaItems.kind].wire == "video"
                            val key =
                                when {
                                    variant == BundleVariant.ORIGINALS -> row[MediaItems.originalKey]
                                    isVideo -> row[MediaItems.mp4Key] ?: row[MediaItems.originalKey]
                                    else -> row[MediaItems.displayWebpKey] ?: row[MediaItems.originalKey]
                                }
                            BundleEntry(
                                // Numbered so the album's order survives a file manager sorting by
                                // name, and named as the creator named it, because "001-display.webp"
                                // is nobody's album.
                                filename = bundleFilename(index + 1, row[MediaItems.filename], key),
                                key = key,
                                kind = row[MediaItems.kind].wire,
                                caption = row[MediaItems.caption],
                                width = row[MediaItems.width],
                                height = row[MediaItems.height],
                            )
                        },
                )
            }
        }

    call.respond(claimed)
}

suspend fun reportBundleBuilt(
    call: ApplicationCall,
    config: Config,
    clock: Clock,
) {
    requireWorker(call, config)
    val id = bundleIdOf(call)
    val report = call.receive<BundleBuiltRequest>()
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    db {
        AlbumBundles.update({ AlbumBundles.id eq id }) {
            it[status] = BundleStatus.READY
            it[key] = report.key
            it[byteSize] = report.byteSize
            it[lastError] = null
            it[claimedAt] = null
            it[readyAt] = now
        }
    }
    call.respond(HttpStatusCode.NoContent)
}

suspend fun reportBundleFailure(
    call: ApplicationCall,
    config: Config,
    clock: Clock,
) {
    requireWorker(call, config)
    val id = bundleIdOf(call)
    val error = call.receive<com.mantel.features.media.WorkFailedRequest>().error.take(1000)
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    db {
        val bundle =
            AlbumBundles.selectAll().where { AlbumBundles.id eq id }.singleOrNull()
                ?: throw DomainException(ErrorCode.NOT_FOUND, "No such bundle")
        val attempts = bundle[AlbumBundles.attempts]
        val exhausted = attempts >= config.worker.maxAttempts
        AlbumBundles.update({ AlbumBundles.id eq id }) {
            it[status] = if (exhausted) BundleStatus.FAILED else BundleStatus.BUILDING
            it[lastError] = error
            it[claimedAt] = null
            it[nextAttemptAt] = if (exhausted) null else now.plus(backoffFor(attempts))
        }
    }
    call.respond(HttpStatusCode.NoContent)
}

suspend fun heartbeatBundle(
    call: ApplicationCall,
    config: Config,
    clock: Clock,
) {
    requireWorker(call, config)
    val id = bundleIdOf(call)
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    val updated = db { AlbumBundles.update({ AlbumBundles.id eq id }) { it[claimedAt] = now } }
    if (updated == 0) throw DomainException(ErrorCode.NOT_FOUND, "No such bundle")
    call.respond(HttpStatusCode.NoContent)
}

private fun bundleIdOf(call: ApplicationCall): BundleId =
    call.parameters["bundleId"]?.let { runCatching { BundleId(UUID.fromString(it)) }.getOrNull() }
        ?: throw DomainException(ErrorCode.NOT_FOUND, "No such bundle")
