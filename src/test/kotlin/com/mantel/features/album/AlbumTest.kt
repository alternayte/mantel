package com.mantel.features.album

import com.mantel.features.media.UploadIntentResponse
import com.mantel.support.browser
import com.mantel.support.createAlbum
import com.mantel.support.signedIn
import com.mantel.support.uploadIntent
import com.mantel.support.withApp
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
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

class AlbumTest {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun io.ktor.client.HttpClient.addItems(
        albumId: String,
        count: Int,
    ): List<String> {
        val files =
            (1..count).joinToString(",") {
                """{"filename":"p$it.jpg","contentType":"image/jpeg","sizeBytes":10}"""
            }
        val intent =
            json.decodeFromString<UploadIntentResponse>(uploadIntent(albumId, """{"files":[$files]}""").bodyAsText())
        return intent.items.map { it.itemId }
    }

    @Test
    fun `an album starts as a draft and lists for its owner only`() =
        withApp { harness ->
            val mine = signedIn(harness, "nate@example.com")
            val created = mine.createAlbum("Holiday")
            assertEquals(HttpStatusCode.Created, created.status)
            val album = created.body<AlbumSummary>()
            assertEquals("draft", album.status)

            assertEquals(1, mine.get("/api/albums").body<List<AlbumSummary>>().size)

            val other = signedIn(harness, "someone@example.com")
            assertEquals(0, other.get("/api/albums").body<List<AlbumSummary>>().size)
            // Not 403: another creator learns nothing, not even that the album exists.
            assertEquals(HttpStatusCode.NotFound, other.get("/api/albums/${album.id}").status)
            assertEquals(HttpStatusCode.NotFound, other.delete("/api/albums/${album.id}").status)
        }

    @Test
    fun `title, description and cover can be changed, and the cover must be in the album`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()
            val items = browser.addItems(album.id, 2)

            val renamed =
                browser.patch("/api/albums/${album.id}") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Cornwall","description":"  three days  ","coverItemId":"${items[1]}"}""")
                }
            assertEquals(HttpStatusCode.OK, renamed.status)
            val updated = renamed.body<AlbumSummary>()
            assertEquals("Cornwall", updated.title)
            assertEquals("three days", updated.description)
            assertEquals(items[1], updated.coverItemId)

            val otherAlbum = browser.createAlbum("Other").body<AlbumSummary>()
            val foreignItem = browser.addItems(otherAlbum.id, 1).single()
            val refused =
                browser.patch("/api/albums/${album.id}") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"coverItemId":"$foreignItem"}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        }

    @Test
    fun `an empty title is refused`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val response =
                browser.post("/api/albums") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"   "}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `reorder names every item once and renumbers from zero`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()
            val items = browser.addItems(album.id, 3)

            val reversed = items.reversed()
            val response =
                browser.patch("/api/albums/${album.id}/items/reorder") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"itemIds":[${reversed.joinToString(",") { "\"$it\"" }}]}""")
                }
            assertEquals(HttpStatusCode.NoContent, response.status)

            val view = browser.get("/api/albums/${album.id}").body<AlbumView>()
            assertEquals(reversed, view.items.map { it.id })
            assertEquals(listOf(0, 1, 2), view.items.map { it.position })

            val partial =
                browser.patch("/api/albums/${album.id}/items/reorder") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"itemIds":["${items[0]}"]}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, partial.status)
        }

    @Test
    fun `a caption is set and cleared`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()
            val item = browser.addItems(album.id, 1).single()

            browser.patch("/api/albums/${album.id}/items/$item") {
                contentType(ContentType.Application.Json)
                setBody("""{"caption":"  low tide  "}""")
            }
            assertEquals("low tide", browser.get("/api/albums/${album.id}").body<AlbumView>().items.single().caption)

            browser.patch("/api/albums/${album.id}/items/$item") {
                contentType(ContentType.Application.Json)
                setBody("""{"caption":""}""")
            }
            assertEquals(null, browser.get("/api/albums/${album.id}").body<AlbumView>().items.single().caption)
        }

    @Test
    fun `deleting an item closes the gap in positions`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()
            val items = browser.addItems(album.id, 3)

            browser.delete("/api/albums/${album.id}/items/${items[0]}")

            val view = browser.get("/api/albums/${album.id}").body<AlbumView>()
            assertEquals(items.drop(1), view.items.map { it.id })
            assertEquals(listOf(0, 1), view.items.map { it.position })
            assertEquals(2, view.itemCount)
        }

    @Test
    fun `progress counts what is ready, failed and still coming`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()
            browser.addItems(album.id, 2)

            val progress = browser.get("/api/albums/${album.id}/status").body<AlbumProgress>()
            assertEquals(2, progress.total)
            assertEquals(0, progress.ready)
            assertEquals(0, progress.failed)
            assertEquals(2, progress.pending)
            assertEquals("draft", progress.status)
        }

    @Test
    fun `archiving hides the album`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()

            assertEquals(HttpStatusCode.NoContent, browser.delete("/api/albums/${album.id}").status)
            assertEquals(0, browser.get("/api/albums").body<List<AlbumSummary>>().size)
            assertEquals(HttpStatusCode.NotFound, browser.get("/api/albums/${album.id}").status)
        }

    @Test
    fun `album endpoints are closed without a session`() =
        withApp {
            val anonymous = browser()
            assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/api/albums").status)
            val response =
                anonymous.post("/api/albums") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"x"}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("unauthenticated"))
        }
}
