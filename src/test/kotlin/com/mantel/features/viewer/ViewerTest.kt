package com.mantel.features.viewer

import com.mantel.features.album.AlbumSummary
import com.mantel.features.media.UploadIntentResponse
import com.mantel.support.albumWithReadyPhoto
import com.mantel.support.browser
import com.mantel.support.createAlbum
import com.mantel.support.share
import com.mantel.support.signedIn
import com.mantel.support.uploadIntent
import com.mantel.support.withApp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ViewerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the manifest carries signed URLs only for items that are ready`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            // A second item that is still waiting to be processed.
            creator.uploadIntent(
                album.id,
                """{"files":[{"filename":"b.jpg","contentType":"image/jpeg","sizeBytes":10}]}""",
            )
            val link = creator.share(album.id)

            val manifest = browser().get("/api/share/${link.token}").body<Manifest>()

            assertEquals(2, manifest.itemCount)
            assertEquals(1, manifest.readyCount)
            val ready = manifest.items.single { it.status == "shareable" }
            assertNotNull(ready.thumbUrl)
            assertNotNull(ready.displayWebpUrl)
            assertEquals(2400, ready.width)

            // A processing item appears with its state and no URLs, so the viewer shows a
            // placeholder rather than a broken grid.
            val waiting = manifest.items.single { it.status != "shareable" }
            assertEquals("pending_upload", waiting.status)
            assertNull(waiting.thumbUrl)
            assertNull(waiting.displayWebpUrl)
        }

    @Test
    fun `an empty album is served, not refused`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.createAlbum("Nothing yet").body<AlbumSummary>()
            val link = creator.share(album.id)

            val manifest = browser().get("/api/share/${link.token}").body<Manifest>()
            assertEquals("Nothing yet", manifest.title)
            assertEquals(0, manifest.itemCount)
            assertTrue(manifest.items.isEmpty())
        }

    @Test
    fun `a PIN'd link says a PIN is required, and nothing else`() =
        withApp { harness ->
            val creator = signedIn(harness, "nate@example.com")
            val album = creator.albumWithReadyPhoto(harness, "Private")
            val link = creator.share(album.id, """{"pin":"1379"}""")

            val response = browser().get("/api/share/${link.token}")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("pin_required"), body)
            assertTrue(!body.contains("Private"), "the title leaked past the PIN")
            assertTrue(!body.contains("nate@example.com"), body)
        }

    @Test
    fun `the eleventh PIN attempt in an hour is refused`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val link = creator.share(album.id, """{"pin":"1379"}""")
            val viewer = browser()

            repeat(10) {
                val wrong =
                    viewer.post("/api/share/${link.token}/unlock") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"pin":"0000"}""")
                    }
                assertEquals(HttpStatusCode.UnprocessableEntity, wrong.status)
            }

            // Even the right PIN waits: a link under attack is not a link to hand out faster.
            val refused =
                viewer.post("/api/share/${link.token}/unlock") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"pin":"1379"}""")
                }
            assertEquals(HttpStatusCode.TooManyRequests, refused.status)
        }

    @Test
    fun `a PIN must be four to twelve digits`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)

            listOf("123", "abcd", "12 34", "1".repeat(13)).forEach { bad ->
                val response =
                    creator.post("/api/albums/${album.id}/share-links") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"pin":"$bad"}""")
                    }
                assertEquals(HttpStatusCode.UnprocessableEntity, response.status, "accepted PIN '$bad'")
            }
        }

    @Test
    fun `an expiry is 7, 30 or 90 days, or never`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)

            assertNull(creator.share(album.id).expiresAt, "a link without an expiry should not have one")
            assertNotNull(creator.share(album.id, """{"expiresInDays":30}""").expiresAt)

            val refused =
                creator.post("/api/albums/${album.id}/share-links") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"expiresInDays":45}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        }

    @Test
    fun `the link preview shows the cover, and a PIN'd album shows a placeholder instead`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val open = creator.albumWithReadyPhoto(harness, "Open")
            val secret = creator.albumWithReadyPhoto(harness, "Secret")

            val openLink = creator.share(open.id)
            val secretLink = creator.share(secret.id, """{"pin":"1379"}""")

            val openShell = browser().get("/a/${openLink.token}").bodyAsText()
            assertTrue(openShell.contains("og:image"), openShell)
            assertTrue(openShell.contains("public/og/${openLink.token}.webp"), openShell)
            assertTrue(openShell.contains("<title>Open</title>"), openShell)
            // The cover really was copied somewhere a crawler can read without a signature.
            assertTrue(harness.storage.objects.containsKey("public/og/${openLink.token}.webp"))

            val secretShell = browser().get("/a/${secretLink.token}").bodyAsText()
            assertTrue(secretShell.contains("og-placeholder.png"), secretShell)
            assertTrue(!secretShell.contains("public/og/${secretLink.token}"), "the cover leaked past the PIN")
            assertTrue(!harness.storage.objects.containsKey("public/og/${secretLink.token}.webp"))
            // The title is the creator's own words and is in the preview by design; the pictures are not.
            assertTrue(secretShell.contains("<title>Secret</title>"))
        }

    @Test
    fun `the placeholder a PIN'd album points at actually loads`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val link = creator.share(album.id, """{"pin":"1379"}""")

            val shell = browser().get("/a/${link.token}").bodyAsText()
            assertTrue(shell.contains("/og-placeholder.png"), shell)

            // A dangling preview image is the same as no preview, so the file has to be there.
            val image = browser().get("/og-placeholder.png")
            assertEquals(HttpStatusCode.OK, image.status)
            assertTrue(image.readRawBytes().size > 1000, "the placeholder is empty")
        }

    @Test
    fun `a title with markup in it cannot break out of the shell`() =
        withApp { harness ->
            val creator = signedIn(harness)
            // The quotes matter: this is a title a creator can really type.
            val album =
                creator.post("/api/albums") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Holiday\" /><script>alert(1)</script>"}""")
                }.body<AlbumSummary>()
            val link = creator.share(album.id)

            val shell = browser().get("/a/${link.token}").bodyAsText()

            assertTrue(!shell.contains("<script>alert(1)</script>"), shell)
            assertTrue(shell.contains("&lt;script&gt;"), shell)
        }

    @Test
    fun `an unknown token and a revoked one are indistinguishable`() =
        withApp {
            val viewer = browser()
            val unknown = viewer.get("/api/share/notarealtoken")
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertEquals(HttpStatusCode.NotFound, viewer.get("/a/notarealtoken").status)
        }

    @Test
    fun `a viewer cannot reach anything a creator can`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            creator.share(album.id)

            val viewer = browser()
            assertEquals(HttpStatusCode.Unauthorized, viewer.get("/api/albums").status)
            assertEquals(HttpStatusCode.Unauthorized, viewer.get("/api/albums/${album.id}").status)
            assertEquals(HttpStatusCode.Unauthorized, viewer.get("/api/me").status)
        }

    @Test
    fun `two viewers of the same album are handed the same media URLs`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val link = creator.share(album.id)

            val first = browser().get("/api/share/${link.token}").body<Manifest>()
            val second = browser().get("/api/share/${link.token}").body<Manifest>()

            // One CDN cache entry, not one per viewer (SDD.md 3.2).
            assertEquals(first.items.single().displayWebpUrl, second.items.single().displayWebpUrl)
        }

    @Test
    fun `an unused intent leaves the album shareable`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.createAlbum().body<AlbumSummary>()
            val intent =
                json.decodeFromString<UploadIntentResponse>(
                    creator.uploadIntent(
                        album.id,
                        """{"files":[{"filename":"a.jpg","contentType":"image/jpeg","sizeBytes":10}]}""",
                    ).bodyAsText(),
                )
            assertEquals(1, intent.items.size)

            val link = creator.share(album.id)
            val manifest = browser().get("/api/share/${link.token}").body<Manifest>()
            assertEquals("pending_upload", manifest.items.single().status)
        }
}
