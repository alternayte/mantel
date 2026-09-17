package com.mantel.worker

import com.mantel.features.album.AlbumProgress
import com.mantel.features.album.AlbumSummary
import com.mantel.features.media.ClaimedItem
import com.mantel.features.media.UploadIntentResponse
import com.mantel.features.media.WorkOutcome
import com.mantel.support.TEST_WORKER_TOKEN
import com.mantel.support.browser
import com.mantel.support.createAlbum
import com.mantel.support.signedIn
import com.mantel.support.uploadIntent
import com.mantel.support.withApp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Guarantee: a crashed worker's claimed item returns to the queue (SDD.md 12).
 *
 * A worker that dies mid-item leaves the row in processing with nobody working on it. Nothing in
 * the system notices except the claim timeout, so this is the only mechanism that stops an item
 * being stuck for ever, and it is worth its own test.
 */
class ReclaimStaleTest {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun ApplicationTestBuilder.worker(): HttpClient = browser()

    private suspend fun HttpClient.claim(limit: Int = 4): List<ClaimedItem> =
        json.decodeFromString(
            post("/api/worker/claim") {
                header(HttpHeaders.Authorization, "Bearer $TEST_WORKER_TOKEN")
                contentType(ContentType.Application.Json)
                setBody("""{"limit":$limit}""")
            }.bodyAsText(),
        )

    private suspend fun HttpClient.fail(
        itemId: String,
        error: String = "codec exploded",
    ): WorkOutcome =
        json.decodeFromString(
            post("/api/worker/items/$itemId/failure") {
                header(HttpHeaders.Authorization, "Bearer $TEST_WORKER_TOKEN")
                contentType(ContentType.Application.Json)
                setBody("""{"error":"$error"}""")
            }.bodyAsText(),
        )

    private suspend fun HttpClient.succeed(item: ClaimedItem): HttpResponse =
        post("/api/worker/items/${item.itemId}/derivatives") {
            header(HttpHeaders.Authorization, "Bearer $TEST_WORKER_TOKEN")
            contentType(ContentType.Application.Json)
            setBody(
                """{"thumbKey":"${item.thumbKey}","displayWebpKey":"${item.displayWebpKey}",
                   "displayAvifKey":"${item.displayAvifKey}","width":2400,"height":1600}""",
            )
        }

    /** An album with one uploaded item, ready to be claimed. */
    private suspend fun ApplicationTestBuilder.uploadedItem(harness: com.mantel.support.Harness): Pair<String, String> {
        val browser = signedIn(harness)
        val album = browser.createAlbum().body<AlbumSummary>()
        val intent =
            json.decodeFromString<UploadIntentResponse>(
                browser.uploadIntent(
                    album.id,
                    """{"files":[{"filename":"a.jpg","contentType":"image/jpeg","sizeBytes":10}]}""",
                ).bodyAsText(),
            )
        val itemId = intent.items.single().itemId
        harness.storage.objects[harness.storage.presigns.single().key] = "0123456789".toByteArray()
        browser.post("/api/albums/${album.id}/uploads/complete") {
            contentType(ContentType.Application.Json)
            setBody("""{"itemIds":["$itemId"]}""")
        }
        return album.id to itemId
    }

    @Test
    fun `an item held past the claim timeout is claimed again`() =
        withApp { harness ->
            val (albumId, itemId) = uploadedItem(harness)
            val worker = worker()

            val first = worker.claim()
            assertEquals(listOf(itemId), first.map { it.itemId })
            assertEquals(1, first.single().attempt)

            // A second worker must not take an item that is being worked on.
            assertEquals(emptyList<String>(), worker.claim().map { it.itemId })

            // The worker dies here. Nothing reports anything.
            harness.clock.advance(Duration.ofMinutes(11))

            val reclaimed = worker.claim()
            assertEquals(listOf(itemId), reclaimed.map { it.itemId })
            assertEquals(2, reclaimed.single().attempt, "the retry is counted")

            val progress = signedIn(harness).get("/api/albums/$albumId/status").body<AlbumProgress>()
            assertEquals(1, progress.pending)
        }

    @Test
    fun `a claimed item is invisible to other workers until it times out`() =
        withApp { harness ->
            uploadedItem(harness)
            val worker = worker()

            worker.claim()
            harness.clock.advance(Duration.ofMinutes(9))
            assertEquals(emptyList<String>(), worker.claim().map { it.itemId }, "claimed too early")

            harness.clock.advance(Duration.ofMinutes(2))
            assertEquals(1, worker.claim().size)
        }

    @Test
    fun `a failure waits before it is claimed again, and gives up after the attempts run out`() =
        withApp { harness ->
            val (_, itemId) = uploadedItem(harness)
            val worker = worker()

            worker.claim()
            val first = worker.fail(itemId)
            assertEquals("uploaded", first.status)
            assertNotNull(first.nextAttemptAt)

            // The backoff is real: claiming immediately finds nothing.
            assertEquals(emptyList<String>(), worker.claim().map { it.itemId })
            harness.clock.advance(Duration.ofMinutes(2))
            assertEquals(listOf(itemId), worker.claim().map { it.itemId })

            worker.fail(itemId)
            harness.clock.advance(Duration.ofHours(1))
            assertEquals(listOf(itemId), worker.claim().map { it.itemId })

            val last = worker.fail(itemId, "still broken")
            assertEquals("failed", last.status, "three attempts is the limit")
            assertEquals(emptyList<String>(), worker.claim().map { it.itemId }, "a failed item is not retried")
        }

    @Test
    fun `a failed item never disappears, and the creator can retry it`() =
        withApp { harness ->
            val (albumId, itemId) = uploadedItem(harness)
            val worker = worker()
            repeat(3) {
                worker.claim()
                worker.fail(itemId)
                harness.clock.advance(Duration.ofHours(1))
            }

            val creator = signedIn(harness)
            val progress = creator.get("/api/albums/$albumId/status").body<AlbumProgress>()
            assertEquals(1, progress.failed)
            assertEquals("codec exploded", progress.items.single().lastError)
            assertEquals("ready", progress.status, "an album whose items have all settled is ready")

            assertEquals(
                HttpStatusCode.NoContent,
                creator.post("/api/albums/$albumId/items/$itemId/retry").status,
            )
            assertEquals(listOf(itemId), worker.claim().map { it.itemId }, "retry puts it back on the queue")
        }

    @Test
    fun `a completed item leaves the queue and settles its album`() =
        withApp { harness ->
            val (albumId, itemId) = uploadedItem(harness)
            val worker = worker()
            val claimed = worker.claim().single()

            assertEquals(HttpStatusCode.OK, worker.succeed(claimed).status)
            assertEquals(emptyList<String>(), worker.claim().map { it.itemId })

            val progress = signedIn(harness).get("/api/albums/$albumId/status").body<AlbumProgress>()
            assertEquals(1, progress.ready)
            assertEquals("ready", progress.status)
            assertEquals(itemId, progress.items.single().id)
        }

    @Test
    fun `work endpoints are closed without the worker token`() =
        withApp { harness ->
            uploadedItem(harness)
            val stranger = browser()

            val noToken =
                stranger.post("/api/worker/claim") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"limit":4}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, noToken.status)

            val wrongToken =
                stranger.post("/api/worker/claim") {
                    header(HttpHeaders.Authorization, "Bearer not-the-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"limit":4}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, wrongToken.status)
            assertTrue(wrongToken.bodyAsText().contains("unauthenticated"))
        }
}
