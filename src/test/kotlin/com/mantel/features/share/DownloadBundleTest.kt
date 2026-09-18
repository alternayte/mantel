package com.mantel.features.share

import com.mantel.features.album.AlbumSummary
import com.mantel.support.TEST_WORKER_TOKEN
import com.mantel.support.albumWithReadyPhoto
import com.mantel.support.browser
import com.mantel.support.createAlbum
import com.mantel.support.share
import com.mantel.support.signedIn
import com.mantel.support.withApp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The bundle is built by the worker into storage and the API hands back a URL to it. These are the
 * parts that decide whether a recipient gets their album or somebody else's yesterday.
 */
class DownloadBundleTest {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun HttpClient.claimBundles(): List<ClaimedBundle> =
        json.decodeFromString(
            post("/api/worker/bundles/claim") {
                header(HttpHeaders.Authorization, "Bearer $TEST_WORKER_TOKEN")
                contentType(ContentType.Application.Json)
                setBody("""{"limit":5}""")
            }.bodyAsText(),
        )

    private suspend fun HttpClient.reportBuilt(
        bundleId: String,
        key: String,
    ) = post("/api/worker/bundles/$bundleId/built") {
        header(HttpHeaders.Authorization, "Bearer $TEST_WORKER_TOKEN")
        contentType(ContentType.Application.Json)
        setBody("""{"key":"$key","byteSize":4096}""")
    }

    @Test
    fun `the first ask queues a build, and the next ask redirects to the finished bundle`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val link = creator.share(album.id)
            val viewer = browser()
            val worker = browser()

            val first = viewer.get("/api/share/${link.token}/download")
            assertEquals(HttpStatusCode.Accepted, first.status)
            assertTrue(first.bodyAsText().contains("building"), first.bodyAsText())

            val claimed = worker.claimBundles()
            assertEquals(1, claimed.size)
            assertEquals("display", claimed.single().variant)
            assertEquals(1, claimed.single().entries.size)
            // The worker is told everything it needs; it cannot look anything up.
            assertTrue(claimed.single().entries.single().filename.startsWith("001-"))
            assertTrue(claimed.single().targetKey.startsWith("bundles/"))

            harness.storage.objects[claimed.single().targetKey] = "a zip".toByteArray()
            assertEquals(HttpStatusCode.NoContent, worker.reportBuilt(claimed.single().bundleId, claimed.single().targetKey).status)

            val second = viewer.get("/api/share/${link.token}/download")
            assertEquals(HttpStatusCode.Found, second.status)
            assertTrue(second.headers["Location"]!!.contains("bundles/"), second.headers["Location"]!!)
        }

    @Test
    fun `a second ask while it is being built does not queue a second build`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val link = creator.share(album.id)
            val viewer = browser()

            viewer.get("/api/share/${link.token}/download")
            viewer.get("/api/share/${link.token}/download")

            assertEquals(1, browser().claimBundles().size)
        }

    @Test
    fun `an album that changed is packed again rather than handed over stale`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val link = creator.share(album.id)
            val viewer = browser()
            val worker = browser()

            viewer.get("/api/share/${link.token}/download")
            val first = worker.claimBundles().single()
            harness.storage.objects[first.targetKey] = "first".toByteArray()
            worker.reportBuilt(first.bundleId, first.targetKey)
            assertEquals(HttpStatusCode.Found, viewer.get("/api/share/${link.token}/download").status)

            // A caption is part of the album, so the bundle is no longer the album.
            val itemId =
                creator.get("/api/albums/${album.id}").body<com.mantel.features.album.AlbumView>().items.single().id
            creator.patch("/api/albums/${album.id}/items/$itemId") {
                contentType(ContentType.Application.Json)
                setBody("""{"caption":"low tide"}""")
            }

            val afterChange = viewer.get("/api/share/${link.token}/download")
            assertEquals(HttpStatusCode.Accepted, afterChange.status, "a changed album handed back the old bundle")

            val rebuilt = worker.claimBundles()
            assertEquals(1, rebuilt.size)
            assertEquals(first.bundleId, rebuilt.single().bundleId, "a rebuild replaces the row")
        }

    @Test
    fun `originals are a separate bundle from display quality`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val link = creator.share(album.id)
            val viewer = browser()

            viewer.get("/api/share/${link.token}/download")
            viewer.get("/api/share/${link.token}/download?originals=true")

            val claimed = browser().claimBundles().sortedBy { it.variant }
            assertEquals(listOf("display", "originals"), claimed.map { it.variant })
            assertEquals(listOf(false, true), claimed.map { it.includesOriginals })
            // The originals bundle points at the uploaded file, the display one at the derivative.
            assertNotEquals(claimed[0].entries.single().key, claimed[1].entries.single().key)
            assertTrue(claimed[1].entries.single().key.endsWith("original.jpg"), claimed[1].entries.single().key)
        }

    @Test
    fun `a PIN'd album will not pack without the PIN`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val link = creator.share(album.id, """{"pin":"1379"}""")
            val viewer = browser()

            val locked = viewer.get("/api/share/${link.token}/download")
            assertEquals(HttpStatusCode.Unauthorized, locked.status)
            assertTrue(locked.bodyAsText().contains("pin_required"))
            assertEquals(0, browser().claimBundles().size, "a locked album was queued anyway")

            viewer.post("/api/share/${link.token}/unlock") {
                contentType(ContentType.Application.Json)
                setBody("""{"pin":"1379"}""")
            }
            assertEquals(HttpStatusCode.Accepted, viewer.get("/api/share/${link.token}/download").status)
        }

    @Test
    fun `a revoked link cannot be downloaded`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val link = creator.share(album.id)
            val viewer = browser()
            viewer.get("/api/share/${link.token}/download")
            val built = browser().claimBundles().single()
            harness.storage.objects[built.targetKey] = "zip".toByteArray()
            browser().reportBuilt(built.bundleId, built.targetKey)

            creator.delete("/api/share-links/${link.id}")

            assertEquals(HttpStatusCode.NotFound, viewer.get("/api/share/${link.token}/download").status)
        }

    @Test
    fun `an album with nothing rendered has nothing to pack`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.createAlbum("Empty").body<AlbumSummary>()
            val link = creator.share(album.id)

            val response = browser().get("/api/share/${link.token}/download")
            assertEquals(HttpStatusCode.Conflict, response.status)
        }
}
