package com.mantel.features.auth

import com.mantel.support.browser
import com.mantel.support.withApp
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionTest {
    @Test
    fun `the session cookie is HttpOnly and SameSite Lax and carries no identifier`() =
        withApp { harness ->
            val browser = browser()
            browser.post("/api/auth/magic-link") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"nate@example.com"}""")
            }
            val response = browser.get(harness.mailer.lastLink())
            val setCookie = response.headers.getAll("Set-Cookie")?.single { it.startsWith("mantel_session=") }
            assertNotNull(setCookie)
            assertTrue(setCookie!!.contains("HttpOnly"), setCookie)
            assertTrue(setCookie.contains("SameSite=Lax"), setCookie)

            // The value is a random secret, not the account id or the email.
            val value = setCookie.substringAfter("mantel_session=").substringBefore(";")
            assertEquals(32, value.length)
            assertTrue(!value.contains("@"))
        }

    @Test
    fun `without a session the creator endpoints are closed`() =
        withApp {
            val anonymous = browser()
            assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/api/me").status)
            assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/api/account/export").status)
        }

    @Test
    fun `logging out ends the session`() =
        withApp { harness ->
            val browser = browser()
            browser.post("/api/auth/magic-link") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"nate@example.com"}""")
            }
            browser.get(harness.mailer.lastLink())
            assertEquals(HttpStatusCode.OK, browser.get("/api/me").status)

            assertEquals(HttpStatusCode.NoContent, browser.post("/api/auth/logout").status)
            assertEquals(HttpStatusCode.Unauthorized, browser.get("/api/me").status)
        }
}
