package com.mantel.features.media

import com.mantel.features.account.Accounts
import com.mantel.features.library.removeItem
import com.mantel.features.share.AlbumBundles
import com.mantel.features.share.ShareLinks
import com.mantel.features.share.ogKeyFor
import com.mantel.kernel.Bytes
import com.mantel.kernel.Clock
import com.mantel.kernel.Config
import com.mantel.kernel.db
import com.mantel.storage.ObjectStorage
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Serializable
data class ObservedObject(val key: String, val sizeBytes: Long, val ageSeconds: Long)

@Serializable
data class ClassifyRequest(val objects: List<ObservedObject>)

@Serializable
data class ClassifyResponse(val orphans: List<String>, val keptCount: Int)

@Serializable
data class QuotaRepair(val accountsChecked: Int, val accountsCorrected: Int)

/**
 * Object storage is the authoritative place for the bytes and the database is the record of what
 * should exist; neither can see the other, so something has to walk both (SDD.md 3.3).
 *
 * The worker lists storage and asks this what it is looking at. Nothing is deleted on the strength
 * of a listing alone, and nothing young is deleted at all: an object written a minute ago may
 * belong to a row that is a second from being committed.
 */
private val GRACE: Duration = Duration.ofHours(24)

suspend fun classifyObjects(
    call: ApplicationCall,
    config: Config,
) {
    requireWorker(call, config)
    val request = call.receive<ClassifyRequest>()

    val known =
        db {
            val media =
                MediaItems.selectAll().flatMap { row ->
                    listOfNotNull(
                        row[MediaItems.originalKey],
                        row[MediaItems.thumbKey],
                        row[MediaItems.displayWebpKey],
                        row[MediaItems.displayAvifKey],
                        row[MediaItems.posterKey],
                        row[MediaItems.mp4Key],
                    )
                }
            val bundles = AlbumBundles.selectAll().mapNotNull { it[AlbumBundles.key] }
            // A live link's preview image is public and belongs to nobody's media row.
            val previews = ShareLinks.selectAll().map { ogKeyFor(it[ShareLinks.token]) }
            (media + bundles + previews).toSet()
        }

    val orphans =
        request.objects
            .filter { it.key !in known && it.ageSeconds > GRACE.seconds }
            .map { it.key }

    call.respond(ClassifyResponse(orphans = orphans, keptCount = request.objects.size - orphans.size))
}

/**
 * Storage used, recomputed from the rows that own it. Drift comes from a crash between reserving
 * quota and writing the row, or from a bug; either way the creator should not pay for it for ever.
 */
suspend fun repairQuota(
    call: ApplicationCall,
    config: Config,
) {
    requireWorker(call, config)

    val repair =
        db {
            val accounts = Accounts.selectAll().where { Accounts.deletedAt.isNull() }.toList()
            var corrected = 0

            accounts.forEach { account ->
                // Counted from the library, so a photograph in three albums counts once and a
                // photograph in none still counts.
                val total =
                    MediaItems
                        .select(MediaItems.byteSize.sum())
                        .where { MediaItems.accountId eq account[Accounts.id] }
                        .single()[MediaItems.byteSize.sum()]
                        ?: Bytes.NONE

                if (total != account[Accounts.storageUsedBytes]) {
                    Accounts.update({ Accounts.id eq account[Accounts.id] }) {
                        it[storageUsedBytes] = total
                    }
                    corrected += 1
                }
            }
            QuotaRepair(accountsChecked = accounts.size, accountsCorrected = corrected)
        }

    call.respond(repair)
}

@Serializable
data class AbandonedSweep(val removed: Int)

/**
 * Rows that were declared and never sent.
 *
 * `upload-intent` creates the row and reserves its quota before any byte moves, so a client that
 * stops between declaring and sending leaves a row nothing will ever finish. It shows in the
 * library as an item that is permanently waiting, and it holds space the account cannot use.
 *
 * Storage aborts an incomplete multipart upload after a day (SDD.md 4.6), so after that the bytes
 * cannot arrive even if the client comes back. A day is therefore the age at which a declaration is
 * no longer a promise, and the row goes the same way a deleted one does.
 */
suspend fun sweepAbandonedUploads(
    call: ApplicationCall,
    storage: ObjectStorage,
    config: Config,
    clock: Clock,
) {
    requireWorker(call, config)
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    val declaredBefore = now.minus(ABANDONED_AFTER)

    val abandoned =
        db {
            MediaItems.selectAll()
                .where { (MediaItems.status eq ItemState.PENDING_UPLOAD) and (MediaItems.createdAt less declaredBefore) }
                .toList()
        }
    abandoned.forEach { removeItem(it, storage, now) }
    call.respond(AbandonedSweep(removed = abandoned.size))
}

/** A day: what storage waits before it aborts an incomplete upload of its own accord. */
private val ABANDONED_AFTER: Duration = Duration.ofDays(1)
