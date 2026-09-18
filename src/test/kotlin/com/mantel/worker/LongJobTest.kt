package com.mantel.worker

import com.mantel.features.album.AlbumSummary
import com.mantel.features.media.ClaimedItem
import com.mantel.features.media.UploadIntentResponse
import com.mantel.support.Harness
import com.mantel.support.TEST_WORKER_TOKEN
import com.mantel.support.browser
import com.mantel.support.createAlbum
import com.mantel.support.signedIn
import com.mantel.support.uploadIntent
import com.mantel.support.withApp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * A 4K transcode can run longer than the claim timeout. Without a heartbeat the claim lapses while
 * the worker is still encoding, a second worker starts the same file, and both write the same keys.
 * This is the test for the mechanism that stops that.
 */
class LongJobTest {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun HttpClient.claim(): List<ClaimedItem> =
        json.decodeFromString(
            post("/api/worker/claim") {
                header(HttpHeaders.Authorization, "Bearer $TEST_WORKER_TOKEN")
                contentType(ContentType.Application.Json)
                setBody("""{"limit":4}""")
            }.bodyAsText(),
        )

    private suspend fun HttpClient.heartbeat(itemId: String) =
        post("/api/worker/items/$itemId/heartbeat") {
            header(HttpHeaders.Authorization, "Bearer $TEST_WORKER_TOKEN")
        }

    private suspend fun ApplicationTestBuilder.uploadedVideo(harness: Harness): String {
        val creator = signedIn(harness)
        val album = creator.createAlbum("Clips").body<AlbumSummary>()
        val intent =
            json.decodeFromString<UploadIntentResponse>(
                creator.uploadIntent(
                    album.id,
                    """{"files":[{"filename":"clip.mp4","contentType":"video/mp4","sizeBytes":10}]}""",
                ).bodyAsText(),
            )
        val itemId = intent.items.single().itemId
        harness.storage.objects[harness.storage.presigns.single().key] = "0123456789".toByteArray()
        creator.post("/api/albums/${album.id}/uploads/complete") {
            contentType(ContentType.Application.Json)
            setBody("""{"itemIds":["$itemId"]}""")
        }
        return itemId
    }

    @Test
    fun `a job that keeps saying it is running keeps its claim`() =
        withApp { harness ->
            val itemId = uploadedVideo(harness)
            val worker = browser()
            assertEquals(listOf(itemId), worker.claim().map { it.itemId })

            // Twenty minutes of encoding, with the worker reporting in every few minutes.
            repeat(4) {
                harness.clock.advance(Duration.ofMinutes(5))
                assertEquals(HttpStatusCode.OK, worker.heartbeat(itemId).status)
                assertEquals(emptyList<String>(), worker.claim().map { it.itemId }, "the claim lapsed")
            }
        }

    @Test
    fun `a job that stops saying anything loses its claim`() =
        withApp { harness ->
            val itemId = uploadedVideo(harness)
            val worker = browser()
            worker.claim()

            harness.clock.advance(Duration.ofMinutes(5))
            worker.heartbeat(itemId)

            // The worker dies here. The next timeout reclaims it.
            harness.clock.advance(Duration.ofMinutes(11))
            assertEquals(listOf(itemId), worker.claim().map { it.itemId })
        }

    @Test
    fun `the claim tells the worker how often to report in`() =
        withApp { harness ->
            uploadedVideo(harness)
            val claimed = browser().claim().single()
            // A third of the ten-minute claim timeout.
            assertEquals(200, claimed.heartbeatSeconds)
        }

    @Test
    fun `a heartbeat for an item that no longer exists says so`() =
        withApp { harness ->
            uploadedVideo(harness)
            val worker = browser()
            worker.claim()

            val gone = worker.heartbeat("01a0b17e-9cfe-7422-9c99-748a3223291e")
            assertEquals(HttpStatusCode.NotFound, gone.status)
        }

    @Test
    fun `a heartbeat needs the worker token`() =
        withApp { harness ->
            val itemId = uploadedVideo(harness)
            val response = browser().post("/api/worker/items/$itemId/heartbeat")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}
