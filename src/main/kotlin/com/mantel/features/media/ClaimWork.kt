package com.mantel.features.media

import com.mantel.kernel.Clock
import com.mantel.kernel.Config
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.db
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Serializable
data class ClaimRequest(val limit: Int)

@Serializable
data class ClaimedItem(
    val itemId: String,
    val kind: String,
    val attempt: Int,
    val originalKey: String,
    val thumbKey: String,
    val displayWebpKey: String,
    val displayAvifKey: String,
)

/**
 * The worker holds no database credentials (SDD.md 6.3), so the claim runs here and the worker asks
 * for work over HTTP. The statement is the one from SDD.md 3.4: SKIP LOCKED so two workers never
 * take the same row, and a claim older than the timeout returns to the queue, which is the only
 * thing that recovers an item from a worker that died holding it.
 */
suspend fun claimWork(
    call: ApplicationCall,
    config: Config,
    clock: Clock,
) {
    requireWorker(call, config)
    val limit = call.receive<ClaimRequest>().limit.coerceIn(1, 50)
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    val staleBefore = now.minus(config.worker.claimTimeout)

    val claimed =
        db {
            val sql =
                """
                   UPDATE media_item
                      SET status = 'processing', claimed_at = ?, attempts = attempts + 1
                    WHERE id IN (
                          SELECT id FROM media_item
                           WHERE (status = 'uploaded' AND (next_attempt_at IS NULL OR next_attempt_at <= ?))
                              OR (status = 'processing' AND claimed_at < ?)
                           ORDER BY created_at
                           LIMIT ?
                             FOR UPDATE SKIP LOCKED
                          )
                RETURNING id, kind, attempts, original_key
                """.trimIndent()

            val connection = TransactionManager.current().connection.connection as java.sql.Connection
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, now)
                statement.setObject(2, now)
                statement.setObject(3, staleBefore)
                statement.setInt(4, limit)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val originalKey = rows.getString("original_key")
                            val prefix = originalKey.substringBeforeLast('/')
                            add(
                                ClaimedItem(
                                    itemId = rows.getString("id"),
                                    kind = rows.getString("kind"),
                                    attempt = rows.getInt("attempts"),
                                    originalKey = originalKey,
                                    thumbKey = "$prefix/thumb.webp",
                                    displayWebpKey = "$prefix/display.webp",
                                    displayAvifKey = "$prefix/display.avif",
                                ),
                            )
                        }
                    }
                }
            }
        }

    call.respond(claimed)
}

fun requireWorker(
    call: ApplicationCall,
    config: Config,
) {
    val expected =
        config.worker.token
            ?: throw DomainException(ErrorCode.UNAUTHENTICATED, "Worker access is not configured")
    val presented = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
    if (presented != expected) {
        throw DomainException(ErrorCode.UNAUTHENTICATED, "Worker access is not configured")
    }
}
