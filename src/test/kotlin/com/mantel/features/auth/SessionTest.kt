package com.mantel.features.auth

import com.mantel.features.account.Me
import com.mantel.support.browser
import com.mantel.support.withApp
import io.ktor.client.call.body
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

            // The value is an opaque secret: not the account id, not the email, and long enough
            // that guessing is not a strategy. The exact length is not the point and may change.
            val value = setCookie.substringAfter("mantel_session=").substringBefore(";")
            assertTrue(value.length >= 22, "a session secret carries at least 128 bits: $value")
            assertTrue(Regex("^[A-Za-z0-9_-]+$").matches(value), "must survive a cookie header: $value")
            val me = browser.get("/api/me").body<Me>()
            assertTrue(!value.contains(me.email.substringBefore("@")), "the secret names the account")
        }

    @Test
    fun `two sign-ins never share a session secret`() =
        withApp { harness ->
            fun cookieOf(response: io.ktor.client.statement.HttpResponse) =
                response.headers.getAll("Set-Cookie")!!
                    .single { it.startsWith("mantel_session=") }
                    .substringAfter("mantel_session=")
                    .substringBefore(";")

            val secrets =
                List(5) {
                    val browser = browser()
                    browser.post("/api/auth/magic-link") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"email":"nate@example.com"}""")
                    }
                    cookieOf(browser.get(harness.mailer.lastLink()))
                }
            assertEquals(secrets.size, secrets.toSet().size, "a session secret was reused")
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
