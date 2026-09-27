package com.mantel.features.library

import com.mantel.features.account.Me
import com.mantel.features.album.AlbumSummary
import com.mantel.features.album.AlbumView
import com.mantel.features.media.UploadIntentResponse
import com.mantel.features.viewer.Manifest
import com.mantel.support.Harness
import com.mantel.support.TEST_WORKER_TOKEN
import com.mantel.support.albumWithReadyPhoto
import com.mantel.support.browser
import com.mantel.support.share
import com.mantel.support.signedIn
import com.mantel.support.uploadIntent
import com.mantel.support.withApp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
 * A deleted photograph waits in the trash for 30 days. It leaves the library and its albums at
 * once, comes back to both on a restore, and only the sweep or a deliberate "remove now" takes its
 * bytes.
 */
class TrashTest {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun HttpClient.library() = json.decodeFromString<LibraryPage>(get("/api/library").bodyAsText())

    private suspend fun HttpClient.trash() = json.decodeFromString<LibraryPage>(get("/api/library/trash").bodyAsText())

    private suspend fun HttpClient.album(albumId: String) = get("/api/albums/$albumId").body<AlbumView>()

    private suspend fun HttpClient.usedBytes() = get("/api/me").body<Me>().storageUsedBytes

    /** Two rendered photographs in one album, so a restore has a place to return to. */
    private suspend fun HttpClient.albumOfTwo(harness: Harness): Pair<AlbumSummary, List<String>> {
        val album = albumWithReadyPhoto(harness)
        val intent =
            json.decodeFromString<UploadIntentResponse>(
                uploadIntent(album.id, """{"files":[{"filename":"b.jpg","contentType":"image/jpeg","sizeBytes":20}]}""")
                    .bodyAsText(),
            )
        val second = intent.items.single().itemId
        harness.renderItem(second, harness.storage.presigns.last().key)
        return album to album(album.id).items.map { it.id }
    }

    @Test
    fun `a deleted item leaves the library and its album, and a restore puts it back where it was`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val (album, items) = browser.albumOfTwo(harness)
            val first = items.first()

            assertEquals(HttpStatusCode.NoContent, browser.delete("/api/library/$first").status)

            assertEquals(items.drop(1), browser.library().items.map { it.id })
            val trimmed = browser.album(album.id)
            assertEquals(items.drop(1), trimmed.items.map { it.id })
            assertEquals(1, trimmed.itemCount)
            assertEquals(20, trimmed.totalBytes)
            val trashed = browser.trash().items.single()
            assertEquals(first, trashed.id)
            assertNotNull(trashed.trashedAt)

            assertEquals(HttpStatusCode.NoContent, browser.post("/api/library/$first/restore").status)

            assertEquals(items.toSet(), browser.library().items.map { it.id }.toSet())
            val restored = browser.album(album.id)
            assertEquals(items, restored.items.map { it.id }, "the restored item returns to its old place")
            assertEquals(2, restored.itemCount)
            assertTrue(browser.trash().items.isEmpty())
        }

    @Test
    fun `a share link keeps working without an item in the trash`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val (album, items) = browser.albumOfTwo(harness)
            val link = browser.share(album.id)

            browser.delete("/api/library/${items.first()}")

            val manifest = browser().get("/api/share/${link.token}")
            assertEquals(HttpStatusCode.OK, manifest.status)
            val shown = json.decodeFromString<Manifest>(manifest.bodyAsText())
            assertEquals(items.drop(1), shown.items.map { it.id })
            assertEquals(1, shown.itemCount)
        }

    @Test
    fun `offering the bytes of a trashed item again leaves it in the trash, and an album refuses it`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val hash = "c".repeat(64)
            val file = """{"filename":"a.jpg","contentType":"image/jpeg","sizeBytes":900,"contentHash":"$hash"}"""
            val itemId = browser.libraryIntent(file).items.single().itemId
            browser.delete("/api/library/$itemId")

            // "Back up everything again" on the phone offers the whole camera roll. It must not undo
            // a deletion.
            val again = browser.libraryIntent(file).items.single()
            assertEquals(itemId, again.itemId)
            assertTrue(again.alreadyHeld)
            assertTrue(again.inTrash)
            assertTrue(browser.library().items.isEmpty())

            val album =
                browser.post("/api/albums") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Later"}""")
                }.body<AlbumSummary>()
            val refused =
                browser.post("/api/albums/${album.id}/items") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"mediaItemIds":["$itemId"]}""")
                }
            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertTrue(refused.bodyAsText().contains("in the trash"))
        }

    @Test
    fun `only an item in the trash can be removed now, and removing it frees its bytes`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val (_, items) = browser.albumOfTwo(harness)
            val first = items.first()
            assertEquals(30, browser.usedBytes())

            assertEquals(HttpStatusCode.NotFound, browser.delete("/api/library/trash/$first").status)

            browser.delete("/api/library/$first")
            assertEquals(30, browser.usedBytes(), "a trashed item's bytes are still in storage")

            assertEquals(HttpStatusCode.NoContent, browser.delete("/api/library/trash/$first").status)
            assertEquals(20, browser.usedBytes())
            assertTrue(browser.trash().items.isEmpty())
            assertTrue(harness.storage.deletedPrefixes.any { it.contains(first) })
        }

    @Test
    fun `the sweep removes an item 30 days after it was deleted, and not before`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val (album, items) = browser.albumOfTwo(harness)
            val first = items.first()
            browser.delete("/api/library/$first")

            harness.clock.advance(Duration.ofDays(29))
            assertEquals(0, sweep().removed)
            assertEquals(1, browser.trash().items.size)
            assertEquals(30, browser.usedBytes())

            harness.clock.advance(Duration.ofDays(2))
            assertEquals(1, sweep().removed)
            assertTrue(browser.trash().items.isEmpty())
            assertEquals(20, browser.usedBytes())
            assertTrue(harness.storage.objects.keys.none { it.contains(first) }, "the bytes are gone")
            assertEquals(1, browser.album(album.id).itemCount)
        }

    private suspend fun ApplicationTestBuilder.sweep(): TrashSweep =
        json.decodeFromString(
            client.post("/api/worker/reconcile/trash") { bearerAuth(TEST_WORKER_TOKEN) }.bodyAsText(),
        )

    private suspend fun HttpClient.libraryIntent(vararg files: String): UploadIntentResponse =
        json.decodeFromString(
            post("/api/library/upload-intent") {
                contentType(ContentType.Application.Json)
                setBody("""{"files":[${files.joinToString(",")}]}""")
            }.bodyAsText(),
        )
}
