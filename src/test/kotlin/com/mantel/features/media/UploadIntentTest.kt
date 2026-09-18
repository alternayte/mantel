package com.mantel.features.media

import com.mantel.features.album.AlbumSummary
import com.mantel.support.browser
import com.mantel.support.createAlbum
import com.mantel.support.routeInventory
import com.mantel.support.signedIn
import com.mantel.support.uploadIntent
import com.mantel.support.withApp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guarantee: media bytes never transit the API server (SDD.md 12).
 *
 * The proof has two halves. The upload URL points at storage, not at this server, and this server
 * exposes no route that will take an image body at all.
 */
class UploadIntentTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the upload URL points at storage, not at the API`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()

            val response =
                browser.uploadIntent(
                    album.id,
                    """{"files":[{"filename":"beach.jpg","contentType":"image/jpeg","sizeBytes":2048}]}""",
                )
            assertEquals(HttpStatusCode.OK, response.status)

            val intent = json.decodeFromString<UploadIntentResponse>(response.bodyAsText())
            val url = intent.items.single().uploadUrl!!
            assertTrue(url.startsWith("https://storage.test/"), url)
            assertTrue(!url.contains("/api/"), url)
        }

    @Test
    fun `the API registers no route that could take an upload`() =
        withApp { harness ->
            // The test application builds itself on first use.
            browser().get("/api/health")
            val routes = harness.application.routeInventory()

            // An upload is a PUT to storage. The API having no PUT at all is the whole claim, and
            // this reads it off the routing table rather than off four paths somebody thought of.
            assertEquals(emptyList<String>(), routes.filter { it.startsWith("PUT ") })

            // The rest of the surface is the documented one. A new route that takes a body has to
            // be added here, which is the moment to ask whether it takes media.
            assertEquals(
                setOf(
                    "GET /api/health",
                    "POST /api/auth/magic-link",
                    "GET /api/auth/magic-link/callback",
                    "GET /api/auth/github",
                    "GET /api/auth/github/callback",
                    "POST /api/auth/logout",
                    "GET /api/me",
                    "GET /api/account/export",
                    "DELETE /api/account",
                    "GET /api/albums",
                    "POST /api/albums",
                    "GET /api/albums/{id}",
                    "PATCH /api/albums/{id}",
                    "DELETE /api/albums/{id}",
                    "GET /api/albums/{id}/status",
                    "POST /api/albums/{id}/upload-intent",
                    "GET /api/albums/{id}/items/{itemId}/upload-progress",
                    "POST /api/albums/{id}/uploads/complete",
                    "PATCH /api/albums/{id}/items/reorder",
                    "PATCH /api/albums/{id}/items/{itemId}",
                    "DELETE /api/albums/{id}/items/{itemId}",
                    "POST /api/albums/{id}/items/{itemId}/retry",
                    "POST /api/worker/claim",
                    "POST /api/worker/items/{itemId}/derivatives",
                    "POST /api/worker/items/{itemId}/failure",
                    "POST /api/worker/items/{itemId}/heartbeat",
                ),
                routes,
            )
        }

    @Test
    fun `the presigned PUT is signed for the declared length and type`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()
            browser.uploadIntent(
                album.id,
                """{"files":[
                    {"filename":"beach.jpg","contentType":"image/jpeg","sizeBytes":2048},
                    {"filename":"clip.mp4","contentType":"video/mp4","sizeBytes":9000}
                ]}""",
            )

            assertEquals(2, harness.storage.presigns.size)
            assertEquals(listOf(2048L, 9000L), harness.storage.presigns.map { it.contentLength })
            assertEquals(listOf("image/jpeg", "video/mp4"), harness.storage.presigns.map { it.contentType })
            // Keys live under the account prefix, so deleting the account takes them with it.
            assertTrue(harness.storage.presigns.all { it.key.startsWith("accounts/") }, "${harness.storage.presigns}")
        }

    @Test
    fun `an unsupported type is refused before any URL is issued`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()

            val response =
                browser.uploadIntent(
                    album.id,
                    """{"files":[{"filename":"notes.pdf","contentType":"application/pdf","sizeBytes":100}]}""",
                )
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue(harness.storage.presigns.isEmpty())
            assertEquals(0, browser.get("/api/albums/${album.id}").body<com.mantel.features.album.AlbumView>().items.size)
        }

    @Test
    fun `completion confirms the bytes arrived before the item leaves pending`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()
            val intent =
                json.decodeFromString<UploadIntentResponse>(
                    browser.uploadIntent(
                        album.id,
                        """{"files":[
                            {"filename":"a.jpg","contentType":"image/jpeg","sizeBytes":10},
                            {"filename":"b.jpg","contentType":"image/jpeg","sizeBytes":10}
                        ]}""",
                    ).bodyAsText(),
                )
            val (arrived, neverUploaded) = intent.items[0] to intent.items[1]

            // Only the first upload actually happened.
            harness.storage.objects[harness.storage.presigns[0].key] = "0123456789".toByteArray()

            val complete =
                browser.post("/api/albums/${album.id}/uploads/complete") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"itemIds":["${arrived.itemId}","${neverUploaded.itemId}"]}""")
                }
            assertEquals(HttpStatusCode.OK, complete.status)
            val result = json.decodeFromString<CompleteUploadsResponse>(complete.bodyAsText())
            assertEquals(listOf(arrived.itemId), result.uploaded)
            assertEquals(listOf(neverUploaded.itemId), result.missing)

            val items = browser.get("/api/albums/${album.id}").body<com.mantel.features.album.AlbumView>().items
            assertEquals("uploaded", items.single { it.id == arrived.itemId }.status)
            assertEquals("pending_upload", items.single { it.id == neverUploaded.itemId }.status)
        }
}
