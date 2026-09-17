package com.mantel.features.auth

import com.mantel.features.account.Me
import com.mantel.support.browser
import com.mantel.support.withApp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MagicLinkTest {
    private suspend fun io.ktor.client.HttpClient.requestLink(email: String) =
        post("/api/auth/magic-link") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email"}""")
        }

    @Test
    fun `a link signs in, once`() =
        withApp { harness ->
            val browser = browser()
            assertEquals(HttpStatusCode.Accepted, browser.requestLink("nate@example.com").status)

            val link = harness.mailer.lastLink()
            val first = browser.get(link)
            assertEquals(HttpStatusCode.Found, first.status)
            assertEquals("/app", first.headers["Location"])

            val me = browser.get("/api/me")
            assertEquals(HttpStatusCode.OK, me.status)
            assertEquals("nate@example.com", me.body<Me>().email)

            // The same link a second time is dead, and the response does not say why beyond that.
            val second = browser().get(link)
            assertEquals(HttpStatusCode.UnprocessableEntity, second.status)
        }

    @Test
    fun `signing in twice reuses the account`() =
        withApp { harness ->
            val first = browser()
            first.requestLink("nate@example.com")
            first.get(harness.mailer.lastLink())
            val quotaAtFirst = first.get("/api/me").body<Me>().storageQuotaBytes

            val second = browser()
            second.requestLink("NATE@example.com")
            second.get(harness.mailer.lastLink())

            val me = second.get("/api/me").body<Me>()
            assertEquals("nate@example.com", me.email)
            assertEquals(quotaAtFirst, me.storageQuotaBytes)
        }

    @Test
    fun `an expired or unknown token is refused`() =
        withApp {
            val response = browser().get("/api/auth/magic-link/callback?token=not-a-real-token")
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue(response.bodyAsText().contains("validation_failed"))
        }

    @Test
    fun `the sixth link in an hour is refused`() =
        withApp {
            val browser = browser()
            repeat(5) { assertEquals(HttpStatusCode.Accepted, browser.requestLink("nate@example.com").status) }
            val sixth = browser.requestLink("nate@example.com")
            assertEquals(HttpStatusCode.TooManyRequests, sixth.status)
            assertTrue(sixth.bodyAsText().contains("rate_limited"))
        }

    @Test
    fun `a malformed address never reaches the mailer`() =
        withApp { harness ->
            val response = browser().requestLink("not-an-email")
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue(harness.mailer.sent.isEmpty())
        }
}
