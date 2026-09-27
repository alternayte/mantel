package com.mantel.worker

import com.mantel.features.album.AlbumProgress
import com.mantel.features.album.AlbumSummary
import com.mantel.features.media.ItemState
import com.mantel.features.media.MediaItems
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
import org.jetbrains.exposed.sql.update
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
    fun `a video goes from uploaded to ready with a poster, a thumbnail and an MP4`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.createAlbum("Clips").body<AlbumSummary>()
            val clip = shortClip()
            val intent =
                json.decodeFromString<UploadIntentResponse>(
                    creator.uploadIntent(
                        album.id,
                        """{"files":[{"filename":"clip.mp4","contentType":"video/mp4","sizeBytes":${clip.size}}]}""",
                    ).bodyAsText(),
                )
            val itemId = intent.items.single().itemId
            val originalKey = harness.storage.presigns.single().key
            harness.storage.objects[originalKey] = clip
            creator.post("/api/albums/${album.id}/uploads/complete") {
                contentType(ContentType.Application.Json)
                setBody("""{"itemIds":["$itemId"]}""")
            }

            assertEquals(1, workerAgainstThisApp(harness).tick())

            val progress = creator.get("/api/albums/${album.id}/status").body<AlbumProgress>()
            assertEquals(1, progress.ready)
            assertEquals("video", progress.items.single().kind)
            assertEquals(1280, progress.items.single().width)
            assertTrue(progress.items.single().durationMs!! in 1_500..2_500)
            assertEquals("2021-03-04T05:06:07Z", progress.items.single().takenAt)

            val prefix = originalKey.substringBeforeLast('/')
            listOf("$prefix/thumb.webp", "$prefix/poster.webp", "$prefix/display.mp4").forEach { key ->
                assertTrue(harness.storage.objects[key]?.isNotEmpty() == true, "missing derivative $key")
            }
            assertEquals(
                listOf("image/webp", "image/webp", "video/mp4"),
                harness.storage.uploaded.map { it.second },
            )
        }

    private fun fixture(name: String): ByteArray = WorkerEndToEndTest::class.java.getResourceAsStream("/$name")!!.use { it.readBytes() }

    /** The width libvips reads from a derivative the worker wrote. */
    private fun widthOf(bytes: ByteArray): Int {
        val file = java.nio.file.Files.createTempFile("mantel-derivative", ".webp")
        java.nio.file.Files.write(file, bytes)
        return PhotoPipeline().widthOf(file)
    }

    @Test
    fun `a photograph backed up from the web carries the date its file records, and a display WebP`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val photo = fixture("exif-taken.jpg")
            val intent =
                json.decodeFromString<UploadIntentResponse>(
                    creator.post("/api/library/upload-intent") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"files":[{"filename":"harbour.jpg","contentType":"image/jpeg","sizeBytes":${photo.size}}]}""")
                    }.bodyAsText(),
                )
            val itemId = intent.items.single().itemId
            val originalKey = harness.storage.presigns.single().key
            harness.storage.objects[originalKey] = photo
            creator.post("/api/library/uploads/complete") {
                contentType(ContentType.Application.Json)
                setBody("""{"itemIds":["$itemId"]}""")
            }

            assertEquals(1, workerAgainstThisApp(harness).tick())

            val item = creator.library().items.single()
            assertEquals("backed_up", item.status)
            // The camera wrote no offset, so the wall clock it recorded is the time shown.
            assertEquals("2019-07-14T16:20:05Z", item.takenAt)
            val prefix = originalKey.substringBeforeLast('/')
            assertEquals("https://storage.test/$prefix/display.webp?signed-for=hour", item.displayUrl)
            assertEquals(1600, widthOf(harness.storage.objects["$prefix/display.webp"]!!))
            // Nothing a recipient needs: the library item is in no album.
            assertEquals(null, harness.storage.objects["$prefix/display.avif"])
        }

    @Test
    fun `a photograph backed up before display WebPs existed gets one from the backfill`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val photo = fixture("exif-taken.jpg")
            val intent =
                json.decodeFromString<UploadIntentResponse>(
                    creator.post("/api/library/upload-intent") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"files":[{"filename":"harbour.jpg","contentType":"image/jpeg","sizeBytes":${photo.size}}]}""")
                    }.bodyAsText(),
                )
            val itemId = com.mantel.features.media.ItemId(java.util.UUID.fromString(intent.items.single().itemId))
            val originalKey = harness.storage.presigns.single().key
            val prefix = originalKey.substringBeforeLast('/')
            harness.storage.objects[originalKey] = photo
            harness.storage.objects["$prefix/thumb.webp"] = "thumbnail".toByteArray()
            // What 0.5 left behind: backed up with a thumbnail and nothing else.
            org.jetbrains.exposed.sql.transactions.transaction {
                MediaItems.update({ MediaItems.id eq itemId }) {
                    it[status] = ItemState.BACKED_UP
                    it[thumbKey] = "$prefix/thumb.webp"
                }
            }
            assertEquals(null, creator.library().items.single().displayUrl)

            val worker = workerAgainstThisApp(harness)
            assertEquals(1, worker.tick())
            assertEquals(0, worker.tick(), "a backfilled photograph is not offered again")

            val item = creator.library().items.single()
            assertEquals("backed_up", item.status)
            assertEquals("https://storage.test/$prefix/display.webp?signed-for=hour", item.displayUrl)
            assertEquals(1600, widthOf(harness.storage.objects["$prefix/display.webp"]!!))
            assertEquals("2019-07-14T16:20:05Z", item.takenAt)
        }

    private suspend fun io.ktor.client.HttpClient.library() =
        json.decodeFromString<com.mantel.features.library.LibraryPage>(get("/api/library").bodyAsText())

    /** Two seconds of 720p. The 4K case is VideoPipelineTest's; this one proves the wiring. */
    private fun shortClip(): ByteArray {
        val scratch = java.nio.file.Files.createTempDirectory("mantel-e2e-clip")
        val file = scratch.resolve("clip.mp4")
        val process =
            ProcessBuilder(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=24",
                "-t", "2", "-c:v", "libx264", "-preset", "ultrafast",
                // What a phone writes when it films, in UTC.
                "-metadata", "creation_time=2021-03-04T05:06:07.000000Z",
                file.toString(),
            ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)
        check(process.exitValue() == 0) { "ffmpeg failed: $output" }
        return java.nio.file.Files.readAllBytes(file)
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
