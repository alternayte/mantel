package com.mantel.features.media

import com.mantel.features.account.Accounts
import com.mantel.features.account.Me
import com.mantel.features.album.AlbumSummary
import com.mantel.kernel.Bytes
import com.mantel.support.createAlbum
import com.mantel.support.signedIn
import com.mantel.support.uploadIntent
import com.mantel.support.withApp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Two batches arriving at once must not both fit in the same remaining space. Reading the quota and
 * reserving against it in one transaction is not enough on its own: without a row lock both
 * transactions read the same starting value and the second write silently overwrites the first.
 */
class QuotaConcurrencyTest {
    @Test
    fun `two batches racing for the last space, only one wins`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()
            transaction { Accounts.update { it[storageQuotaBytes] = Bytes(1_000) } }

            val body = """{"files":[{"filename":"a.jpg","contentType":"image/jpeg","sizeBytes":800}]}"""
            val responses =
                coroutineScope {
                    List(2) { async { browser.uploadIntent(album.id, body) } }.awaitAll()
                }

            val accepted = responses.count { it.status == HttpStatusCode.OK }
            val refused = responses.count { it.status == HttpStatusCode.PayloadTooLarge }
            assertEquals(1, accepted, "both batches were accepted into space for one")
            assertEquals(1, refused)
            assertEquals(800, browser.get("/api/me").body<Me>().storageUsedBytes)
            assertTrue(harness.storage.presigns.size == 1, "a URL was issued for the refused batch")
        }
}
