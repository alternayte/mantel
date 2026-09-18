package com.mantel.features.agent

import com.mantel.features.album.AlbumSummary
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
import io.ktor.server.testing.ApplicationTestBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guarantee: an agent token cannot read another account's albums (SDD.md 12).
 *
 * This is a privacy product with a programmable interface. A token that can enumerate and re-share
 * every album is a data-exfiltration API with good intentions, so scope enforcement is a tested
 * guarantee rather than a convention (SDD.md 9).
 */
class TokenScopeTest {
    private suspend fun ApplicationTestBuilder.agent(token: String): HttpClient {
        val client = browser()
        return client
    }

    private suspend fun HttpClient.withToken(
        token: String,
        path: String,
    ) = get(path) { header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun HttpClient.mintToken(
        name: String,
        vararg scopes: String,
    ): TokenView =
        post("/api/tokens") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","scopes":[${scopes.joinToString(",") { "\"$it\"" }}]}""")
        }.body()

    @Test
    fun `a token reads its own account's albums and nobody else's`() =
        withApp { harness ->
            val mine = signedIn(harness, "nate@example.com")
            val myAlbum = mine.albumWithReadyPhoto(harness, "Mine")
            val token = mine.mintToken("agent", "albums:read")

            val theirs = signedIn(harness, "someone@example.com")
            val theirAlbum = theirs.createAlbum("Theirs").body<AlbumSummary>()

            val agent = browser()

            // Its own account: visible.
            val listed = agent.withToken(token.token!!, "/api/albums")
            assertEquals(HttpStatusCode.OK, listed.status)
            assertTrue(listed.bodyAsText().contains("Mine"))
            assertFalse(listed.bodyAsText().contains("Theirs"), "the token listed another account's album")

            // Somebody else's album, by its id: not found, exactly as for a person.
            val other = agent.withToken(token.token, "/api/albums/${theirAlbum.id}")
            assertEquals(HttpStatusCode.NotFound, other.status)
            assertFalse(other.bodyAsText().contains("Theirs"))

            assertEquals(HttpStatusCode.OK, agent.withToken(token.token, "/api/albums/${myAlbum.id}").status)
        }

    @Test
    fun `albums read cannot write anything`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val token = creator.mintToken("reader", "albums:read")
            val agent = browser()

            val created =
                agent.post("/api/albums") {
                    header(HttpHeaders.Authorization, "Bearer ${token.token}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Not allowed"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, created.status)
            assertTrue(created.bodyAsText().contains("albums:write"), created.bodyAsText())

            val renamed =
                agent.patch("/api/albums/${album.id}") {
                    header(HttpHeaders.Authorization, "Bearer ${token.token}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Renamed"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, renamed.status)

            val archived =
                agent.delete("/api/albums/${album.id}") {
                    header(HttpHeaders.Authorization, "Bearer ${token.token}")
                }
            assertEquals(HttpStatusCode.Forbidden, archived.status)
        }

    @Test
    fun `albums read does not return share tokens for existing links`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val link = creator.share(album.id)
            val token = creator.mintToken("reader", "albums:read", "albums:write")
            val agent = browser()

            val listed = agent.withToken(token.token!!, "/api/albums/${album.id}/share-links")

            assertEquals(HttpStatusCode.Forbidden, listed.status)
            assertFalse(listed.bodyAsText().contains(link.token), "a read token was handed a share token")
        }

    @Test
    fun `share write cannot read the albums it is sharing`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val token = creator.mintToken("sharer", "share:write")
            val agent = browser()

            assertEquals(HttpStatusCode.Forbidden, agent.withToken(token.token!!, "/api/albums").status)

            val link =
                agent.post("/api/albums/${album.id}/share-links") {
                    header(HttpHeaders.Authorization, "Bearer ${token.token}")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.Created, link.status)
        }

    @Test
    fun `a token cannot mint another token, export the account or delete it`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val token = creator.mintToken("everything", "albums:read", "albums:write", "share:write")
            val agent = browser()

            val minted =
                agent.post("/api/tokens") {
                    header(HttpHeaders.Authorization, "Bearer ${token.token}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"child","scopes":["albums:read"]}""")
                }
            assertEquals(HttpStatusCode.Forbidden, minted.status)

            assertEquals(HttpStatusCode.Forbidden, agent.withToken(token.token!!, "/api/account/export").status)
            assertEquals(
                HttpStatusCode.Forbidden,
                agent.delete("/api/account") { header(HttpHeaders.Authorization, "Bearer ${token.token}") }.status,
            )
        }

    @Test
    fun `revoking a token stops the next request`() =
        withApp { harness ->
            val creator = signedIn(harness)
            creator.albumWithReadyPhoto(harness)
            val token = creator.mintToken("agent", "albums:read")
            val agent = browser()
            assertEquals(HttpStatusCode.OK, agent.withToken(token.token!!, "/api/albums").status)

            assertEquals(HttpStatusCode.NoContent, creator.delete("/api/tokens/${token.id}").status)

            assertEquals(HttpStatusCode.Unauthorized, agent.withToken(token.token, "/api/albums").status)
        }

    @Test
    fun `the token is shown once and never again`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val token = creator.mintToken("agent", "albums:read")
            assertNotNull(token.token)
            assertTrue(token.token!!.startsWith("mantel_"))

            val listed = creator.get("/api/tokens").bodyAsText()
            assertTrue(listed.contains("agent"))
            assertFalse(listed.contains(token.token), "the API handed the token back a second time")
        }

    @Test
    fun `an invented or malformed token is refused`() =
        withApp {
            val agent = browser()
            assertEquals(HttpStatusCode.Unauthorized, agent.withToken("mantel_not-a-real-token", "/api/albums").status)
            assertEquals(HttpStatusCode.Unauthorized, agent.withToken("", "/api/albums").status)
        }

    @Test
    fun `a token with an unknown scope is refused at creation`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val response =
                creator.post("/api/tokens") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"agent","scopes":["albums:everything"]}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue(response.bodyAsText().contains("albums:read"), response.bodyAsText())
        }
}
