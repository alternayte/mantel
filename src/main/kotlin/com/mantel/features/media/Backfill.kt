package com.mantel.features.media

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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.vendors.ForUpdateOption
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Serializable
data class BackfillClaim(val limit: Int)

@Serializable
data class BackfillItem(
    val itemId: String,
    val originalKey: String,
    val displayWebpKey: String,
)

@Serializable
data class DisplayWritten(
    val displayWebpKey: String,
    /** When the file says it was taken, if it says. */
    val takenAt: String? = null,
)

/**
 * A photograph backed up before every photograph got a display WebP has only a thumbnail, and a
 * phone cannot open a 300 px thumbnail as a photograph. The worker renders the missing display
 * WebP for each of them when it has nothing else to do.
 *
 * This is not a state transition. The item is backed up before and after, and stays in the library
 * grid throughout; it gains one derivative. The claim uses `claimed_at` the way the processing
 * queue does, so two workers never render the same photograph, and a worker that dies holding one
 * loses it when the claim times out.
 */
suspend fun claimBackfill(
    call: ApplicationCall,
    config: Config,
    clock: Clock,
) {
    requireWorker(call, config)
    val limit = call.receive<BackfillClaim>().limit.coerceIn(1, 50)
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    val staleBefore = now.minus(config.worker.claimTimeout)

    val claimed =
        db {
            val rows =
                MediaItems.selectAll()
                    .where {
                        (MediaItems.status eq ItemState.BACKED_UP) and
                            (MediaItems.kind eq MediaKind.PHOTO) and
                            MediaItems.displayWebpKey.isNull() and
                            (MediaItems.claimedAt.isNull() or (MediaItems.claimedAt less staleBefore)) and
                            (MediaItems.nextAttemptAt.isNull() or (MediaItems.nextAttemptAt lessEq now))
                    }
                    .orderBy(MediaItems.createdAt to SortOrder.ASC)
                    .limit(limit)
                    .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                    .toList()
            if (rows.isNotEmpty()) {
                MediaItems.update({ MediaItems.id inList rows.map { it[MediaItems.id] } }) { it[claimedAt] = now }
            }
            rows.map {
                val originalKey = it[MediaItems.originalKey]
                BackfillItem(
                    itemId = it[MediaItems.id].toString(),
                    originalKey = originalKey,
                    displayWebpKey = "${originalKey.substringBeforeLast('/')}/display.webp",
                )
            }
        }
    call.respond(claimed)
}

/**
 * The display WebP exists. It is written only to a row that still wants it: an item that joined an
 * album meanwhile is rendered whole by the processing queue, and one removed meanwhile is gone.
 */
suspend fun reportDisplay(
    call: ApplicationCall,
    config: Config,
    clock: Clock,
) {
    requireWorker(call, config)
    val itemId = backfillItemOf(call)
    val report = call.receive<DisplayWritten>()
    val takenAt = report.takenAt?.let { parseTakenAt(it) }

    db {
        MediaItems.selectAll().where { MediaItems.id eq itemId }.singleOrNull()
            ?: throw DomainException(ErrorCode.NOT_FOUND, "No such item")
        MediaItems.update({
            (MediaItems.id eq itemId) and (MediaItems.status eq ItemState.BACKED_UP) and MediaItems.displayWebpKey.isNull()
        }) {
            it[displayWebpKey] = report.displayWebpKey
            it[claimedAt] = null
            takenAt?.let { value -> it[MediaItems.takenAt] = value }
        }
    }
    call.respond(HttpStatusCode.NoContent)
}

/**
 * The display WebP could not be rendered. The photograph produced a thumbnail once, so this is
 * rare, and it is tried again a day later rather than on every tick.
 */
suspend fun reportDisplayFailure(
    call: ApplicationCall,
    config: Config,
    clock: Clock,
) {
    requireWorker(call, config)
    val itemId = backfillItemOf(call)
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    db {
        MediaItems.selectAll().where { MediaItems.id eq itemId }.singleOrNull()
            ?: throw DomainException(ErrorCode.NOT_FOUND, "No such item")
        MediaItems.update({ (MediaItems.id eq itemId) and (MediaItems.status eq ItemState.BACKED_UP) }) {
            it[claimedAt] = null
            it[nextAttemptAt] = now.plus(BACKFILL_RETRY)
        }
    }
    call.respond(HttpStatusCode.NoContent)
}

private val BACKFILL_RETRY: Duration = Duration.ofDays(1)

private fun backfillItemOf(call: ApplicationCall): ItemId =
    call.parameters["itemId"]?.let { runCatching { ItemId(UUID.fromString(it)) }.getOrNull() }
        ?: throw DomainException(ErrorCode.NOT_FOUND, "No such item")
