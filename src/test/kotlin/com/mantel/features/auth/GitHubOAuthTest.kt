package com.mantel.features.auth

import com.mantel.features.account.Me
import com.mantel.kernel.GitHubConfig
import com.mantel.support.browser
import com.mantel.support.withApp
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class GitHubOAuthTest {
    private val github =
        GitHubConfig(
            clientId = "client",
            clientSecret = "secret",
            authorizeUrl = "https://github.test/login/oauth/authorize",
            tokenUrl = "https://github.test/login/oauth/access_token",
            apiBaseUrl = "https://api.github.test",
        )

    private fun gitHub(
        userEmail: String? = "nate@example.com",
        verifiedPrimary: String? = null,
    ) = MockEngine { request ->
        val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        when {
            request.url.toString().startsWith(github.tokenUrl) ->
                respond("""{"access_token":"gho_test"}""", headers = json)
            request.url.encodedPath == "/user" ->
                respond(
                    """{"id":4242,"login":"alternayte","name":"Nate",${userEmail?.let { "\"email\":\"$it\"" } ?: "\"email\":null"}}""",
                    headers = json,
                )
            request.url.encodedPath == "/user/emails" ->
                respond(
                    """[{"email":"unverified@example.com","primary":false,"verified":false}""" +
                        (verifiedPrimary?.let { ""","{"email":"$it","primary":true,"verified":true}""" } ?: "") +
                        "]",
                    headers = json,
                )
            else -> respond("{}", headers = json)
        }
    }

    private suspend fun io.ktor.client.HttpClient.beginAndFollow(): String {
        val start = get("/api/auth/github")
        assertEquals(HttpStatusCode.Found, start.status)
        val location = start.headers["Location"]!!
        return location.substringAfter("state=")
    }

    @Test
    fun `a first sign-in creates the account`() =
        withApp(github = github, githubResponder = gitHub()) {
            val browser = browser()
            val state = browser.beginAndFollow()
            val callback = browser.get("/api/auth/github/callback?code=abc&state=$state")
            assertEquals(HttpStatusCode.Found, callback.status)
            assertEquals("/app", callback.headers["Location"])

            val me = browser.get("/api/me").body<Me>()
            assertEquals("nate@example.com", me.email)
            assertEquals("Nate", me.displayName)
        }

    @Test
    fun `a callback with the wrong state is refused`() =
        withApp(github = github, githubResponder = gitHub()) {
            val browser = browser()
            browser.beginAndFollow()
            val callback = browser.get("/api/auth/github/callback?code=abc&state=forged")
            assertEquals(HttpStatusCode.UnprocessableEntity, callback.status)
            assertEquals(HttpStatusCode.Unauthorized, browser.get("/api/me").status)
        }

    @Test
    fun `GitHub signs in to the account the same email already owns`() =
        withApp(github = github, githubResponder = gitHub()) { harness ->
            val byMail = browser()
            byMail.post("/api/auth/magic-link") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"nate@example.com"}""")
            }
            byMail.get(harness.mailer.lastLink())

            val byGitHub = browser()
            val state = byGitHub.beginAndFollow()
            byGitHub.get("/api/auth/github/callback?code=abc&state=$state")

            // One account, reached two ways: the export is the same account's export.
            assertEquals("nate@example.com", byGitHub.get("/api/me").body<Me>().email)
            assertEquals(
                byMail.get("/api/account/export").body<String>().length,
                byGitHub.get("/api/account/export").body<String>().length,
            )
        }

    @Test
    fun `an unverified address cannot claim an account`() =
        withApp(github = github, githubResponder = gitHub(userEmail = null, verifiedPrimary = null)) {
            val browser = browser()
            val state = browser.beginAndFollow()
            val callback = browser.get("/api/auth/github/callback?code=abc&state=$state")
            assertEquals(HttpStatusCode.UnprocessableEntity, callback.status)
        }

    @Test
    fun `the state cookie is set on the way out`() =
        withApp(github = github, githubResponder = gitHub()) {
            val start = browser().get("/api/auth/github")
            val cookie = start.headers.getAll("Set-Cookie")?.singleOrNull { it.startsWith("mantel_oauth_state=") }
            assertNotNull(cookie)
            assert(cookie!!.contains("HttpOnly"))
        }
}
