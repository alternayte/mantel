package com.mantel.features.viewer

import com.mantel.support.albumWithReadyPhoto
import com.mantel.support.browser
import com.mantel.support.share
import com.mantel.support.signedIn
import com.mantel.support.withApp
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guarantee: an unprotected album sets no cookie of any kind (SDD.md 12).
 *
 * This is the promise on the front of the product: open a link, see the photos, and nothing is
 * stored about you. A cookie is the one thing a viewer can check for, so it must not be there —
 * not a session, not an analytics id, not a "preferences" cookie nobody meant to add.
 */
class NoCookieTest {
    private fun HttpResponse.cookies(): List<String> = headers.getAll("Set-Cookie").orEmpty()

    @Test
    fun `opening an unprotected album sets no cookie anywhere in the journey`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val link = creator.share(album.id)

            val viewer = browser()
            val visited =
                listOf(
                    viewer.get("/a/${link.token}"),
                    viewer.get("/api/share/${link.token}"),
                    viewer.get("/api/share/${link.token}"),
                )

            visited.forEach { response ->
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(emptyList<String>(), response.cookies(), "a viewer was given a cookie")
            }
        }

    @Test
    fun `a PIN'd album sets one cookie, and only after the PIN is answered`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val link = creator.share(album.id, """{"pin":"1379"}""")

            val viewer = browser()

            // Arriving, being refused, and guessing wrong all set nothing.
            assertEquals(emptyList<String>(), viewer.get("/a/${link.token}").cookies())
            val locked = viewer.get("/api/share/${link.token}")
            assertEquals(HttpStatusCode.Unauthorized, locked.status)
            assertEquals(emptyList<String>(), locked.cookies())

            val wrong =
                viewer.post("/api/share/${link.token}/unlock") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"pin":"0000"}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, wrong.status)
            assertEquals(emptyList<String>(), wrong.cookies())

            // Answering it sets exactly one, scoped to this link's paths and carrying no identifier.
            val unlocked =
                viewer.post("/api/share/${link.token}/unlock") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"pin":"1379"}""")
                }
            assertEquals(HttpStatusCode.NoContent, unlocked.status)
            val cookie = unlocked.cookies().single()
            assertTrue(cookie.startsWith("mantel_unlock_${link.token}="), cookie)
            assertTrue(cookie.contains("Path=/api/share/${link.token}"), cookie)
            assertTrue(cookie.contains("HttpOnly"), cookie)
            assertTrue(cookie.contains("SameSite=Lax"), cookie)

            val value = cookie.substringAfter("=").substringBefore(";")
            assertTrue(!value.contains("1379"), "the cookie carries the PIN")
            assertTrue(!value.contains(album.id), "the cookie carries an identifier")
        }

    @Test
    fun `an unlocked viewer can read the album, and that still sets nothing further`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val link = creator.share(album.id, """{"pin":"1379"}""")

            val viewer = browser()
            viewer.post("/api/share/${link.token}/unlock") {
                contentType(ContentType.Application.Json)
                setBody("""{"pin":"1379"}""")
            }

            val manifest = viewer.get("/api/share/${link.token}")
            assertEquals(HttpStatusCode.OK, manifest.status)
            assertEquals(emptyList<String>(), manifest.cookies())
        }

    @Test
    fun `an unlock for one album does not open another`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val first = creator.albumWithReadyPhoto(harness, "First")
            val second = creator.albumWithReadyPhoto(harness, "Second")
            val firstLink = creator.share(first.id, """{"pin":"1379"}""")
            val secondLink = creator.share(second.id, """{"pin":"1379"}""")

            val viewer = browser()
            viewer.post("/api/share/${firstLink.token}/unlock") {
                contentType(ContentType.Application.Json)
                setBody("""{"pin":"1379"}""")
            }

            assertEquals(HttpStatusCode.OK, viewer.get("/api/share/${firstLink.token}").status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                viewer.get("/api/share/${secondLink.token}").status,
                "one PIN opened another album",
            )
        }

    @Test
    fun `viewer routes are marked noindex`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val link = creator.share(album.id)

            val viewer = browser()
            listOf("/a/${link.token}", "/api/share/${link.token}").forEach { path ->
                val response = viewer.get(path)
                assertEquals("noindex, nofollow", response.headers["X-Robots-Tag"], path)
            }
            val shell = viewer.get("/a/${link.token}").bodyAsText()
            assertTrue(shell.contains("""<meta name="robots" content="noindex, nofollow" />"""), shell)
        }

    @Test
    fun `nothing about a viewer is stored`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val link = creator.share(album.id)

            repeat(3) { browser().get("/api/share/${link.token}") }

            // There is no viewer table, and nothing counts visits. If either appears, this fails.
            val tables =
                org.jetbrains.exposed.sql.transactions.transaction {
                    com.mantel.allTables.map { it.tableName }
                }
            assertTrue(tables.none { it.contains("view") || it.contains("visit") }, "$tables")
            assertNull(
                org.jetbrains.exposed.sql.transactions.transaction {
                    org.jetbrains.exposed.sql.SchemaUtils
                        .statementsRequiredToActualizeScheme()
                        .firstOrNull()
                },
            )
        }
}
