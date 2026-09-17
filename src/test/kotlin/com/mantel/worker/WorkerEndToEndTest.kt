package com.mantel.worker

import com.mantel.features.album.AlbumProgress
import com.mantel.features.album.AlbumSummary
import com.mantel.features.media.UploadIntentResponse
import com.mantel.support.Harness
import com.mantel.support.TEST_WORKER_TOKEN
import com.mantel.support.createAlbum
import com.mantel.support.signedIn
import com.mantel.support.testConfig
import com.mantel.support.uploadIntent
import com.mantel.support.withApp
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The whole path in one test: an uploaded photo, a real claim over HTTP, the real libvips pipeline,
 * derivatives written back to storage, and the item reported ready. Everything below is real except
 * the storage bucket and the browser.
 */
class WorkerEndToEndTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixtureBytes(): ByteArray = WorkerEndToEndTest::class.java.getResourceAsStream("/exif-gps.jpg")!!.use { it.readBytes() }

    private suspend fun ApplicationTestBuilder.workerAgainstThisApp(harness: Harness): Worker {
        val client =
            createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        // publicBaseUrl is empty so the test client's own host is used.
        val config = testConfig().copy(publicBaseUrl = "")
        return Worker(config, TEST_WORKER_TOKEN, client, harness.storage, PhotoPipeline())
    }

    @Test
    fun `a photo goes from uploaded to ready with stripped derivatives in storage`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.createAlbum().body<AlbumSummary>()
            val photo = fixtureBytes()
            val intent =
                json.decodeFromString<UploadIntentResponse>(
                    creator.uploadIntent(
                        album.id,
                        """{"files":[{"filename":"beach.jpg","contentType":"image/jpeg","sizeBytes":${photo.size}}]}""",
                    ).bodyAsText(),
                )
            val itemId = intent.items.single().itemId
            val originalKey = harness.storage.presigns.single().key
            harness.storage.objects[originalKey] = photo
            creator.post("/api/albums/${album.id}/uploads/complete") {
                contentType(ContentType.Application.Json)
                setBody("""{"itemIds":["$itemId"]}""")
            }

            val processed = workerAgainstThisApp(harness).tick()
            assertEquals(1, processed)

            val progress = creator.get("/api/albums/${album.id}/status").body<AlbumProgress>()
            assertEquals(1, progress.ready)
            assertEquals("ready", progress.status)
            assertEquals(2400, progress.items.single().width)
            assertEquals(1600, progress.items.single().height)

            val prefix = originalKey.substringBeforeLast('/')
            listOf("$prefix/thumb.webp", "$prefix/display.webp", "$prefix/display.avif").forEach { key ->
                val bytes = harness.storage.objects[key]
                assertTrue(bytes != null && bytes.isNotEmpty(), "missing derivative $key")
                assertFalse(
                    bytes!!.toString(Charsets.ISO_8859_1).contains("MantelTestCam"),
                    "$key carries the camera name",
                )
            }
            assertEquals(
                listOf("image/webp", "image/webp", "image/avif"),
                harness.storage.uploaded.map { it.second },
            )
        }

    @Test
    fun `a file that is not an image is reported as a failure, not left in the queue`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.createAlbum().body<AlbumSummary>()
            val intent =
                json.decodeFromString<UploadIntentResponse>(
                    creator.uploadIntent(
                        album.id,
                        """{"files":[{"filename":"broken.jpg","contentType":"image/jpeg","sizeBytes":24}]}""",
                    ).bodyAsText(),
                )
            val itemId = intent.items.single().itemId
            harness.storage.objects[harness.storage.presigns.single().key] = "this is not a photograph".toByteArray()
            creator.post("/api/albums/${album.id}/uploads/complete") {
                contentType(ContentType.Application.Json)
                setBody("""{"itemIds":["$itemId"]}""")
            }

            workerAgainstThisApp(harness).tick()

            val progress = creator.get("/api/albums/${album.id}/status").body<AlbumProgress>()
            // First attempt: back on the queue with the error recorded, not failed and not lost.
            assertEquals(0, progress.failed)
            assertEquals(1, progress.pending)
            assertTrue(progress.items.single().lastError!!.contains("vips"), progress.items.single().lastError!!)
        }
}
