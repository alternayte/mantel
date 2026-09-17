package com.mantel.features.media

import com.mantel.features.album.AlbumSummary
import com.mantel.support.createAlbum
import com.mantel.support.signedIn
import com.mantel.support.uploadIntent
import com.mantel.support.withApp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
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
            val url = intent.items.single().uploadUrl
            assertTrue(url.startsWith("https://storage.test/"), url)
            assertTrue(!url.contains("/api/"), url)
        }

    @Test
    fun `the API has no route that accepts image bytes`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()
            val intent =
                json.decodeFromString<UploadIntentResponse>(
                    browser.uploadIntent(
                        album.id,
                        """{"files":[{"filename":"beach.jpg","contentType":"image/jpeg","sizeBytes":2048}]}""",
                    ).bodyAsText(),
                )
            val itemId = intent.items.single().itemId
            val bytes = ByteArray(2048) { 0x42 }

            // Every shape a client might try if the presigned URL were ignored.
            listOf(
                browser.put("/api/albums/${album.id}/items/$itemId") { setBody(bytes) },
                browser.post("/api/albums/${album.id}/items/$itemId") { setBody(bytes) },
                browser.post("/api/albums/${album.id}/upload") { setBody(bytes) },
                browser.put("/api/albums/${album.id}") { setBody(bytes) },
            ).forEach { response ->
                assertTrue(
                    response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.MethodNotAllowed,
                    "an API route accepted a media body: ${response.status}",
                )
            }
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
            harness.storage.objects[harness.storage.presigns[0].key] = "0123456789"

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
