package com.mantel.features.media

import com.mantel.features.account.Me
import com.mantel.features.album.AlbumSummary
import com.mantel.support.TEST_WORKER_TOKEN
import com.mantel.support.albumWithReadyPhoto
import com.mantel.support.browser
import com.mantel.support.createAlbum
import com.mantel.support.share
import com.mantel.support.signedIn
import com.mantel.support.uploadIntent
import com.mantel.support.withApp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Object storage holds the bytes and the database holds the record of what should exist. Neither
 * can see the other, so the reconciliation job walks both (SDD.md 3.3, 4.6). It deletes things, so
 * what it refuses to delete matters more than what it does.
 */
class ReconcileTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val day = 60L * 60 * 25

    private suspend fun HttpClient.classify(vararg objects: Pair<String, Long>): List<String> {
        val body =
            objects.joinToString(",") { (key, age) ->
                """{"key":"$key","sizeBytes":10,"ageSeconds":$age}"""
            }
        val response =
            post("/api/worker/reconcile/classify") {
                header(HttpHeaders.Authorization, "Bearer $TEST_WORKER_TOKEN")
                contentType(ContentType.Application.Json)
                setBody("""{"objects":[$body]}""")
            }
        return json.decodeFromString<ClassifyResponse>(response.bodyAsText()).orphans
    }

    @Test
    fun `an object no row owns is deleted once it is old enough`() =
        withApp { harness ->
            signedIn(harness)
            val worker = browser()

            assertEquals(listOf("media/nobody/original.jpg"), worker.classify("media/nobody/original.jpg" to day))
        }

    @Test
    fun `a young orphan is left alone, because its row may be a second from existing`() =
        withApp { harness ->
            signedIn(harness)
            val worker = browser()

            assertEquals(emptyList<String>(), worker.classify("media/just-uploaded/original.jpg" to 30L))
        }

    @Test
    fun `an object a row owns is never deleted, however old`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.albumWithReadyPhoto(harness)
            val link = creator.share(album.id)
            val worker = browser()

            val mediaKeys = harness.storage.objects.keys.filter { it.startsWith("media/") }
            assertTrue(mediaKeys.isNotEmpty())

            val previews = harness.storage.objects.keys.filter { it.startsWith("public/og/") }
            val everything = (mediaKeys + previews).map { it to day * 10 }.toTypedArray()

            assertEquals(emptyList<String>(), worker.classify(*everything), "it deleted something a row owns")
            assertTrue(link.token.isNotEmpty())
        }

    @Test
    fun `an abandoned upload is swept up, and the item that never completed is not`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val album = creator.createAlbum("Abandoned").body<AlbumSummary>()
            // An intent creates the row; the bytes then arrive but nobody ever completes the batch.
            creator.uploadIntent(
                album.id,
                """{"files":[{"filename":"a.jpg","contentType":"image/jpeg","sizeBytes":10}]}""",
            )
            val key = harness.storage.presigns.single().key
            val worker = browser()

            // The row still points at the key, so it stays: the creator may finish the upload.
            assertEquals(emptyList<String>(), worker.classify(key to day))

            // A key from an intent nobody kept is another matter.
            assertEquals(
                listOf("media/forgotten/original.jpg"),
                worker.classify("media/forgotten/original.jpg" to day),
            )
        }

    @Test
    fun `storage used is recomputed from the rows that own it`() =
        withApp { harness ->
            val creator = signedIn(harness)
            creator.albumWithReadyPhoto(harness)
            val real = creator.get("/api/me").body<Me>().storageUsedBytes

            // Drift, as a crash between reserving and writing would leave it.
            transaction {
                com.mantel.features.account.Accounts.update {
                    it[storageUsedBytes] = com.mantel.kernel.Bytes(real + 9_000_000)
                }
            }
            assertEquals(real + 9_000_000, creator.get("/api/me").body<Me>().storageUsedBytes)

            val repair =
                browser().post("/api/worker/reconcile/quota") {
                    header(HttpHeaders.Authorization, "Bearer $TEST_WORKER_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, repair.status)
            assertTrue(repair.bodyAsText().contains("\"accountsCorrected\":1"), repair.bodyAsText())

            assertEquals(real, creator.get("/api/me").body<Me>().storageUsedBytes)
        }

    @Test
    fun `reconciliation needs the worker token`() =
        withApp {
            val stranger = browser()
            val response =
                stranger.post("/api/worker/reconcile/classify") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"objects":[]}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                stranger.post("/api/worker/reconcile/quota").status,
            )
        }
}
