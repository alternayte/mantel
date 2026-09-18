package com.mantel.features.share

import com.mantel.features.album.AlbumView
import com.mantel.features.viewer.Manifest
import com.mantel.support.albumWithReadyPhoto
import com.mantel.support.browser
import com.mantel.support.share
import com.mantel.support.signedIn
import com.mantel.support.withApp
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Guarantee: a revoked share link returns 404 immediately (SDD.md 12).
 *
 * Not 403. A revoked link must not confirm that a token ever existed, because a stranger holding a
 * forwarded URL would learn they hold a real one (SDD.md 4.3).
 */
class ShareLinkRevocationTest {
    @Test
    fun `a revoked link is 404 at once, and says nothing about having existed`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val link = creator.share(album.id)

            val viewer = browser()
            assertEquals(HttpStatusCode.OK, viewer.get("/api/share/${link.token}").status)

            assertEquals(HttpStatusCode.NoContent, creator.delete("/api/share-links/${link.id}").status)

            val afterwards = viewer.get("/api/share/${link.token}")
            assertEquals(HttpStatusCode.NotFound, afterwards.status)
            assertTrue(afterwards.bodyAsText().contains("not_found"), afterwards.bodyAsText())
            assertFalse(afterwards.bodyAsText().contains("revoked"), "the answer admits the link existed")

            // An invented token answers exactly the same way.
            val invented = viewer.get("/api/share/aaaaaaaaaaaa")
            assertEquals(afterwards.status, invented.status)
            assertEquals(afterwards.bodyAsText(), invented.bodyAsText())
        }

    @Test
    fun `the OG shell goes with it`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val link = creator.share(album.id)
            val viewer = browser()
            assertEquals(HttpStatusCode.OK, viewer.get("/a/${link.token}").status)

            creator.delete("/api/share-links/${link.id}")

            assertEquals(HttpStatusCode.NotFound, viewer.get("/a/${link.token}").status)
            // The public preview image is deleted too: it was readable without any credential.
            assertTrue(harness.storage.objects.keys.none { it.startsWith("public/og/") })
        }

    @Test
    fun `an expired link is 404 without anyone revoking it`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val link = creator.share(album.id, """{"expiresInDays":7}""")
            val viewer = browser()
            assertEquals(HttpStatusCode.OK, viewer.get("/api/share/${link.token}").status)

            harness.clock.advance(Duration.ofDays(7).plusMinutes(1))

            assertEquals(HttpStatusCode.NotFound, viewer.get("/api/share/${link.token}").status)
        }

    @Test
    fun `other links to the same album keep working`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val forFamily = creator.share(album.id)
            val forFriends = creator.share(album.id)

            creator.delete("/api/share-links/${forFamily.id}")

            val viewer = browser()
            assertEquals(HttpStatusCode.NotFound, viewer.get("/api/share/${forFamily.token}").status)
            assertEquals(HttpStatusCode.OK, viewer.get("/api/share/${forFriends.token}").status)
        }

    @Test
    fun `the first live link publishes the album and the last revocation unpublishes it`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            assertEquals("ready", creator.get("/api/albums/${album.id}").body<AlbumView>().status)

            val first = creator.share(album.id)
            val second = creator.share(album.id)
            assertEquals("published", creator.get("/api/albums/${album.id}").body<AlbumView>().status)

            creator.delete("/api/share-links/${first.id}")
            assertEquals(
                "published",
                creator.get("/api/albums/${album.id}").body<AlbumView>().status,
                "one live link is still published",
            )

            creator.delete("/api/share-links/${second.id}")
            assertEquals("ready", creator.get("/api/albums/${album.id}").body<AlbumView>().status)
        }

    @Test
    fun `only the owner can revoke`() =
        withApp { harness ->
            val creator = signedIn(harness, "nate@example.com")
            val album = creator.albumWithReadyPhoto(harness)
            val link = creator.share(album.id)

            val stranger = signedIn(harness, "someone@example.com")
            assertEquals(HttpStatusCode.NotFound, stranger.delete("/api/share-links/${link.id}").status)
            assertEquals(HttpStatusCode.OK, browser().get("/api/share/${link.token}").status)
        }

    @Test
    fun `a manifest never names the creator or the album`() =
        withApp { harness ->
            val creator = signedIn(harness, "nate@example.com")
            val album = creator.albumWithReadyPhoto(harness)
            val link = creator.share(album.id)

            val body = browser().get("/api/share/${link.token}").bodyAsText()

            assertFalse(body.contains("nate@example.com"), body)
            assertFalse(body.contains(album.id), "the manifest carries the album's internal id")
            val manifest = browser().get("/api/share/${link.token}").body<Manifest>()
            assertEquals("Holiday", manifest.title)
            assertEquals(1, manifest.readyCount)
        }
}
