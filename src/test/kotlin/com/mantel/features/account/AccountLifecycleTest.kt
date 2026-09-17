package com.mantel.features.account

import com.mantel.support.browser
import com.mantel.support.withApp
import io.ktor.client.call.body
import io.ktor.client.request.delete
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

class AccountLifecycleTest {
    private suspend fun io.ktor.client.HttpClient.signIn(
        email: String,
        link: () -> String,
    ) {
        post("/api/auth/magic-link") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email"}""")
        }
        get(link())
    }

    @Test
    fun `the export carries the account and names itself a download`() =
        withApp { harness ->
            val browser = browser()
            browser.signIn("nate@example.com") { harness.mailer.lastLink() }

            val export = browser.get("/api/account/export")
            assertEquals(HttpStatusCode.OK, export.status)
            assertTrue(export.headers["Content-Disposition"]!!.contains("mantel-export.json"))
            val body = export.bodyAsText()
            assertTrue(body.contains("nate@example.com"), body)
            assertTrue(body.contains("storageQuotaBytes"), body)
        }

    @Test
    fun `deleting an account purges its storage and ends its session`() =
        withApp { harness ->
            val browser = browser()
            browser.signIn("nate@example.com") { harness.mailer.lastLink() }
            val me = browser.get("/api/me").body<Me>()
            assertEquals("nate@example.com", me.email)

            // Objects the account owns, as the worker would have written them.
            harness.storage.objects["accounts/other/keep.jpg"] = "other account".toByteArray()
            val prefixBefore = harness.storage.deletedPrefixes.size

            assertEquals(HttpStatusCode.NoContent, browser.delete("/api/account").status)

            assertEquals(prefixBefore + 1, harness.storage.deletedPrefixes.size)
            assertTrue(harness.storage.deletedPrefixes.last().startsWith("accounts/"))
            assertTrue(harness.storage.objects.containsKey("accounts/other/keep.jpg"))
            assertEquals(HttpStatusCode.Unauthorized, browser.get("/api/me").status)
        }

    @Test
    fun `a deleted email can start again`() =
        withApp { harness ->
            val first = browser()
            first.signIn("nate@example.com") { harness.mailer.lastLink() }
            first.delete("/api/account")

            val second = browser()
            second.signIn("nate@example.com") { harness.mailer.lastLink() }
            assertEquals(HttpStatusCode.OK, second.get("/api/me").status)
        }
}
