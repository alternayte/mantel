package com.mantel.features.account

import com.mantel.support.browser
import com.mantel.support.createAlbum
import com.mantel.support.testConfig
import com.mantel.support.uploadIntent
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
import kotlinx.serialization.json.Json
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
            // The phone reads this to leave an oversized file out of a backup batch rather than
            // have the whole batch refused for it.
            assertEquals(testConfig().maxFileBytes.value, me.maxFileBytes)

            // One album with one uploaded item, and an object belonging to somebody else.
            val album = browser.createAlbum().body<com.mantel.features.album.AlbumSummary>()
            val intent =
                Json { ignoreUnknownKeys = true }.decodeFromString<com.mantel.features.media.UploadIntentResponse>(
                    browser.uploadIntent(
                        album.id,
                        """{"files":[{"filename":"a.jpg","contentType":"image/jpeg","sizeBytes":10}]}""",
                    ).bodyAsText(),
                )
            val mine = harness.storage.presigns.single().key
            harness.storage.objects[mine] = "mine".toByteArray()
            harness.storage.objects["media/somebody-else/original.jpg"] = "theirs".toByteArray()
            assertEquals(1, intent.items.size)

            assertEquals(HttpStatusCode.NoContent, browser.delete("/api/account").status)

            assertTrue(harness.storage.objects[mine] == null, "the account's object survived")
            assertTrue(harness.storage.objects.containsKey("media/somebody-else/original.jpg"))
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
