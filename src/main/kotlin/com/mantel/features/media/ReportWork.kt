package com.mantel.features.media

import com.mantel.features.album.AlbumItems
import com.mantel.features.album.Albums
import com.mantel.features.album.albumIdFrom
import com.mantel.features.album.itemIdFrom
import com.mantel.features.album.requireOwnAlbum
import com.mantel.features.album.settleAlbumsHolding
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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Serializable
data class DerivativesWrittenRequest(
    val thumbKey: String,
    val displayWebpKey: String? = null,
    val displayAvifKey: String? = null,
    val posterKey: String? = null,
    val mp4Key: String? = null,
    val width: Int,
    val height: Int,
    val durationMs: Int? = null,
)

@Serializable
data class WorkFailedRequest(val error: String)

@Serializable
data class WorkOutcome(val status: String, val attempts: Int, val nextAttemptAt: String? = null)

private fun itemIdOf(call: ApplicationCall): ItemId =
    call.parameters["itemId"]?.let { runCatching { ItemId(UUID.fromString(it)) }.getOrNull() }
        ?: throw DomainException(ErrorCode.NOT_FOUND, "No such item")

/**
 * The worker reports what it wrote; the API decides what that means for the row. Keeping the
 * transition here is what lets the worker stay credential-free and stateless.
 */
suspend fun reportDerivatives(
    call: ApplicationCall,
    config: Config,
    clock: Clock,
) {
    requireWorker(call, config)
    val itemId = itemIdOf(call)
    val report = call.receive<DerivativesWrittenRequest>()
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    val outcome =
        db {
            val item =
                MediaItems.selectAll().where { MediaItems.id eq itemId }.singleOrNull()
                    ?: throw DomainException(ErrorCode.NOT_FOUND, "No such item")
            // What the report contains decides the state, not what the claim asked for. A worker
            // that wrote a thumbnail and nothing else leaves the item backed up, whatever the
            // album membership was when the claim went out.
            val everything = report.displayWebpKey != null || report.mp4Key != null
            val next =
                transition(
                    item[MediaItems.status],
                    if (everything) ItemEvent.DerivativesWritten else ItemEvent.ThumbnailWritten,
                )

            MediaItems.update({ MediaItems.id eq itemId }) {
                it[status] = next
                it[thumbKey] = report.thumbKey
                it[displayWebpKey] = report.displayWebpKey
                it[displayAvifKey] = report.displayAvifKey
                it[posterKey] = report.posterKey
                it[mp4Key] = report.mp4Key
                it[width] = report.width
                it[height] = report.height
                it[durationMs] = report.durationMs
                it[lastError] = null
                it[nextAttemptAt] = null
                it[readyAt] = now
            }
            // An item that joined an album while its thumbnail was rendering goes back on the
            // queue for the rest, rather than sitting in an album that cannot publish.
            val wanted =
                AlbumItems.selectAll().where { AlbumItems.mediaItemId eq itemId }.any()
            if (next == ItemState.BACKED_UP && wanted) {
                MediaItems.update({ MediaItems.id eq itemId }) {
                    it[status] = transition(ItemState.BACKED_UP, ItemEvent.AlbumJoined)
                    it[attempts] = 0
                }
            }
            settleAlbumsHolding(itemId, now)
            WorkOutcome(next.wire, item[MediaItems.attempts])
        }
    call.respond(outcome)
}

/**
 * A worker holding a long job says so. A 4K transcode outlasts the claim timeout on slow hardware,
 * and without this the item would be handed to a second worker while the first is still encoding:
 * two workers, one file, twice the cost and a race over the derivative keys.
 *
 * It answers 404 when the item is gone, which is the signal to stop working on it.
 */
suspend fun reportHeartbeat(
    call: ApplicationCall,
    config: Config,
    clock: Clock,
) {
    requireWorker(call, config)
    val itemId = itemIdOf(call)
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    val status =
        db {
            val item =
                MediaItems.selectAll().where { MediaItems.id eq itemId }.singleOrNull()
                    ?: throw DomainException(ErrorCode.NOT_FOUND, "No such item")
            if (item[MediaItems.status] == ItemState.PROCESSING) {
                MediaItems.update({ MediaItems.id eq itemId }) { it[claimedAt] = now }
            }
            item[MediaItems.status]
        }
    call.respond(WorkOutcome(status.wire, 0))
}

/**
 * A failure is retried with a widening gap until the attempts run out, then the item is failed and
 * the creator can retry or remove it. It never disappears (SDD.md 4.4).
 */
suspend fun reportFailure(
    call: ApplicationCall,
    config: Config,
    clock: Clock,
) {
    requireWorker(call, config)
    val itemId = itemIdOf(call)
    val error = call.receive<WorkFailedRequest>().error.take(1000)
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    val outcome =
        db {
            val item =
                MediaItems.selectAll().where { MediaItems.id eq itemId }.singleOrNull()
                    ?: throw DomainException(ErrorCode.NOT_FOUND, "No such item")
            val attempts = item[MediaItems.attempts]
            val exhausted = attempts >= config.worker.maxAttempts
            val next =
                if (exhausted) {
                    transition(item[MediaItems.status], ItemEvent.Exhausted(error))
                } else {
                    transition(item[MediaItems.status], ItemEvent.Requeued)
                }
            val retryAt = if (exhausted) null else now.plus(backoffFor(attempts))

            MediaItems.update({ MediaItems.id eq itemId }) {
                it[status] = next
                it[lastError] = error
                it[nextAttemptAt] = retryAt
                it[claimedAt] = null
            }
            if (exhausted) settleAlbumsHolding(itemId, now)
            WorkOutcome(next.wire, attempts, retryAt?.toInstant()?.toString())
        }
    call.respond(outcome)
}

/** A creator's second chance at a failed item. */
suspend fun retryItem(
    call: ApplicationCall,
    clock: Clock,
) {
    val album = requireOwnAlbum(call, albumIdFrom(call))
    val itemId = itemIdFrom(call)
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    db {
        val item =
            MediaItems.selectAll()
                .where { (MediaItems.id eq itemId) and (MediaItems.accountId eq album[Albums.accountId]) }
                .singleOrNull()
                ?: throw DomainException(ErrorCode.NOT_FOUND, "No such item")
        val next =
            runCatching { transition(item[MediaItems.status], ItemEvent.RetryRequested) }.getOrElse {
                throw DomainException(ErrorCode.CONFLICT, "Only a failed item can be retried")
            }
        MediaItems.update({ MediaItems.id eq itemId }) {
            it[status] = next
            it[attempts] = 0
            it[lastError] = null
            it[nextAttemptAt] = null
        }
        settleAlbumsHolding(itemId, now)
    }
    call.respond(HttpStatusCode.NoContent)
}

/** Exponential, so a permanently broken file stops costing attempts quickly. */
fun backoffFor(attempts: Int): Duration = Duration.ofSeconds(60L * (4.0.pow(attempts - 1)).toLong())

private fun Double.pow(exponent: Int): Double = Math.pow(this, exponent.toDouble())
