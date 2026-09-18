package com.mantel.features.auth

import com.mantel.features.account.Me
import com.mantel.kernel.GitHubConfig
import com.mantel.support.Harness
import com.mantel.support.browser
import com.mantel.support.withApp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The native half of sign-in: a client with no cookie jar of the browser's ends up holding a
 * session, and nothing else does.
 */
class NativeSignInTest {
    private val verifier = "a-verifier-of-sufficient-length-for-pkce-01"

    private val github =
        GitHubConfig(
            clientId = "client",
            clientSecret = "secret",
            authorizeUrl = "https://github.test/login/oauth/authorize",
            tokenUrl = "https://github.test/login/oauth/access_token",
            apiBaseUrl = "https://api.github.test",
        )

    private fun gitHub() =
        MockEngine { request ->
            val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            when {
                request.url.toString().startsWith(github.tokenUrl) ->
                    respond("""{"access_token":"gho_test"}""", headers = json)
                request.url.encodedPath == "/user" ->
                    respond("""{"id":4242,"login":"alternayte","name":"Nate","email":"nate@example.com"}""", headers = json)
                else -> respond("{}", headers = json)
            }
        }

    private suspend fun ApplicationTestBuilder.codeFromMagicLink(
        harness: Harness,
        challenge: String = s256(verifier),
        email: String = "nate@example.com",
    ): String {
        val app = browser()
        app.post("/api/auth/magic-link") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","challenge":"$challenge"}""")
        }
        // The link opens in the mail app's browser, which is not the client that asked for it.
        val mailBrowser = browser()
        val redirect = mailBrowser.get(harness.mailer.lastLink())
        assertEquals(HttpStatusCode.Found, redirect.status)
        val location = redirect.headers["Location"]!!
        assertTrue(location.startsWith("$NATIVE_REDIRECT?code=")) { location }

        // That browser signed nobody in: the session belongs to whoever holds the code.
        assertNull(redirect.headers[HttpHeaders.SetCookie])
        assertEquals(HttpStatusCode.Unauthorized, mailBrowser.get("/api/me").status)

        return location.substringAfter("code=")
    }

    private suspend fun HttpClient.exchange(
        code: String,
        verifier: String,
    ) = post("/api/auth/native/exchange") {
        contentType(ContentType.Application.Json)
        setBody("""{"code":"$code","verifier":"$verifier"}""")
    }

    @Test
    fun `a magic link signs in a client that holds no cookies`() =
        withApp { harness ->
            val code = codeFromMagicLink(harness)

            // The app keeps no cookies at all. The session travels as a bearer token.
            val app = createClient { install(ContentNegotiation) { json() } }
            val session = app.exchange(code, verifier).body<NativeSession>()

            val me =
                app.get("/api/me") { header(HttpHeaders.Authorization, "Bearer ${session.session}") }
            assertEquals(HttpStatusCode.OK, me.status)
            assertEquals("nate@example.com", me.body<Me>().email)

            // And it is a person, not an agent: the routes an API token may not touch are open to it.
            val export =
                app.get("/api/account/export") { header(HttpHeaders.Authorization, "Bearer ${session.session}") }
            assertEquals(HttpStatusCode.OK, export.status)
        }

    @Test
    fun `a code works once`() =
        withApp { harness ->
            val code = codeFromMagicLink(harness)
            val app = browser()
            assertEquals(HttpStatusCode.OK, app.exchange(code, verifier).status)

            val second = app.exchange(code, verifier)
            assertEquals(HttpStatusCode.UnprocessableEntity, second.status)
        }

    @Test
    fun `a code is useless without the verifier behind the challenge`() =
        withApp { harness ->
            val code = codeFromMagicLink(harness)
            val thief = browser()
            val stolen = thief.exchange(code, "the-wrong-verifier-entirely-but-long-enough")
            assertEquals(HttpStatusCode.UnprocessableEntity, stolen.status)
            assertTrue(stolen.bodyAsText().contains("validation_failed"))

            // Refusing the guess also burns the code, so a second guess has nothing to guess at.
            assertEquals(HttpStatusCode.UnprocessableEntity, browser().exchange(code, verifier).status)
        }

    @Test
    fun `an ordinary magic link still signs the browser in with a cookie`() =
        withApp { harness ->
            val browser = browser()
            browser.post("/api/auth/magic-link") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"nate@example.com"}""")
            }
            val redirect = browser.get(harness.mailer.lastLink())
            assertEquals("/app", redirect.headers["Location"])
            assertEquals(HttpStatusCode.OK, browser.get("/api/me").status)
        }

    @Test
    fun `a custom tab finishes GitHub sign-in at the deep link`() =
        withApp(github = github, githubResponder = gitHub()) {
            // The custom tab keeps its own cookies, which is where the OAuth state lives.
            val tab = browser()
            val start = tab.get("/api/auth/github?challenge=${s256(verifier)}")
            val state = start.headers["Location"]!!.substringAfter("state=")

            val callback = tab.get("/api/auth/github/callback?code=abc&state=$state")
            assertEquals(HttpStatusCode.Found, callback.status)
            val location = callback.headers["Location"]!!
            assertTrue(location.startsWith("$NATIVE_REDIRECT?code=")) { location }

            val app = browser()
            val session = app.exchange(location.substringAfter("code="), verifier).body<NativeSession>()
            val me = app.get("/api/me") { header(HttpHeaders.Authorization, "Bearer ${session.session}") }
            assertEquals("nate@example.com", me.body<Me>().email)
        }

    @Test
    fun `a malformed challenge is refused before a link is sent`() =
        withApp { harness ->
            val response =
                browser().post("/api/auth/magic-link") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"nate@example.com","challenge":"short"}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue(harness.mailer.sent.isEmpty())
        }
}
