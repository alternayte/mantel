package com.mantel.features.media

import com.mantel.features.account.Accounts
import com.mantel.features.account.Me
import com.mantel.features.album.AlbumSummary
import com.mantel.features.album.AlbumView
import com.mantel.kernel.Bytes
import com.mantel.support.createAlbum
import com.mantel.support.signedIn
import com.mantel.support.uploadIntent
import com.mantel.support.withApp
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guarantee: quota is enforced before a presigned URL is issued (SDD.md 12).
 *
 * Checking after the upload would mean the bytes are already paid for by the time the answer
 * arrives, so the test asserts the negative: on refusal, storage was never asked for a URL and no
 * item row exists.
 */
class QuotaPresignTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun setQuota(bytes: Long) {
        transaction { Accounts.update { it[storageQuotaBytes] = Bytes(bytes) } }
    }

    @Test
    fun `a batch larger than the remaining quota is refused before any URL exists`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()
            setQuota(1_000)

            val response =
                browser.uploadIntent(
                    album.id,
                    """{"files":[{"filename":"big.jpg","contentType":"image/jpeg","sizeBytes":1001}]}""",
                )

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertTrue(response.bodyAsText().contains("quota_exceeded"), response.bodyAsText())
            assertTrue(harness.storage.presigns.isEmpty(), "a presigned URL was issued anyway")
            assertEquals(0, browser.get("/api/albums/${album.id}").body<AlbumView>().items.size)
            assertEquals(0, browser.get("/api/me").body<Me>().storageUsedBytes)
        }

    @Test
    fun `the whole batch is refused, not the part that fits`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()
            setQuota(1_000)

            val response =
                browser.uploadIntent(
                    album.id,
                    """{"files":[
                        {"filename":"small.jpg","contentType":"image/jpeg","sizeBytes":100},
                        {"filename":"huge.jpg","contentType":"image/jpeg","sizeBytes":5000}
                    ]}""",
                )

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertTrue(harness.storage.presigns.isEmpty())
            assertEquals(0, browser.get("/api/albums/${album.id}").body<AlbumView>().items.size)
        }

    @Test
    fun `the declared size is reserved at intent, so a second batch cannot spend it twice`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()
            setQuota(1_000)

            val first =
                browser.uploadIntent(
                    album.id,
                    """{"files":[{"filename":"a.jpg","contentType":"image/jpeg","sizeBytes":800}]}""",
                )
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(800, browser.get("/api/me").body<Me>().storageUsedBytes)

            // Nothing has been uploaded yet, and the space is already spoken for.
            val second =
                browser.uploadIntent(
                    album.id,
                    """{"files":[{"filename":"b.jpg","contentType":"image/jpeg","sizeBytes":800}]}""",
                )
            assertEquals(HttpStatusCode.PayloadTooLarge, second.status)
            assertEquals(1, harness.storage.presigns.size)
        }

    @Test
    fun `deleting an item gives the space back`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()
            setQuota(1_000)

            val intent =
                json.decodeFromString<UploadIntentResponse>(
                    browser.uploadIntent(
                        album.id,
                        """{"files":[{"filename":"a.jpg","contentType":"image/jpeg","sizeBytes":900}]}""",
                    ).bodyAsText(),
                )
            val itemId = intent.items.single().itemId
            assertEquals(900, browser.get("/api/me").body<Me>().storageUsedBytes)

            // Taking it out of the album is not deleting it: an album is a selection.
            assertEquals(
                HttpStatusCode.NoContent,
                browser.delete("/api/albums/${album.id}/items/$itemId").status,
            )
            assertEquals(900, browser.get("/api/me").body<Me>().storageUsedBytes)
            assertTrue(harness.storage.deletedPrefixes.isEmpty())

            assertEquals(HttpStatusCode.NoContent, browser.delete("/api/library/$itemId").status)
            assertEquals(0, browser.get("/api/me").body<Me>().storageUsedBytes)
            assertTrue(harness.storage.deletedPrefixes.any { it.contains(itemId) })
        }
}
