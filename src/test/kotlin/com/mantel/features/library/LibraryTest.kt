package com.mantel.features.library

import com.mantel.features.account.AccountExport
import com.mantel.features.account.Me
import com.mantel.features.album.AlbumSummary
import com.mantel.features.album.AlbumView
import com.mantel.features.album.settleAlbumsHolding
import com.mantel.features.media.ItemId
import com.mantel.features.media.ItemState
import com.mantel.features.media.MediaItems
import com.mantel.features.media.UploadIntentResponse
import com.mantel.support.Harness
import com.mantel.support.browser
import com.mantel.support.createAlbum
import com.mantel.support.signedIn
import com.mantel.support.uploadIntent
import com.mantel.support.withApp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Media belongs to the account. An album is a selection from it, so the two have different
 * lifetimes: removing a photograph from an album is not deleting it, and deleting an album is not
 * deleting the photographs in it.
 */
class LibraryTest {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun HttpClient.uploadTo(
        albumId: String,
        filename: String,
        sizeBytes: Long = 100,
        contentHash: String? = null,
    ): String {
        val hash = contentHash?.let { ""","contentHash":"$it"""" }.orEmpty()
        val body =
            """{"files":[{"filename":"$filename","contentType":"image/jpeg","sizeBytes":$sizeBytes$hash}]}"""
        val intent = json.decodeFromString<UploadIntentResponse>(uploadIntent(albumId, body).bodyAsText())
        return intent.items.single().itemId
    }

    private suspend fun HttpClient.library() = json.decodeFromString<LibraryPage>(get("/api/library").bodyAsText())

    private suspend fun HttpClient.album(albumId: String) = get("/api/albums/$albumId").body<AlbumView>()

    private suspend fun HttpClient.usedBytes() = get("/api/me").body<Me>().storageUsedBytes

    @Test
    fun `deleting an album keeps the photographs and the bytes`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()
            browser.uploadTo(album.id, "a.jpg", sizeBytes = 900)
            assertEquals(900, browser.usedBytes())

            assertEquals(HttpStatusCode.NoContent, browser.delete("/api/albums/${album.id}").status)

            assertEquals(1, browser.library().items.size)
            assertEquals(900, browser.usedBytes())
        }

    @Test
    fun `one photograph in two albums counts once and is captioned twice`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val summer = browser.createAlbum("Summer").body<AlbumSummary>()
            val family = browser.createAlbum("Family").body<AlbumSummary>()
            val itemId = browser.uploadTo(summer.id, "a.jpg", sizeBytes = 900)

            assertEquals(
                HttpStatusCode.NoContent,
                browser.post("/api/albums/${family.id}/items") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"mediaItemIds":["$itemId"]}""")
                }.status,
            )

            browser.caption(summer.id, itemId, "low tide")
            browser.caption(family.id, itemId, "the whole bay")

            assertEquals("low tide", browser.album(summer.id).items.single().caption)
            assertEquals("the whole bay", browser.album(family.id).items.single().caption)

            // The bytes exist once, whatever number of albums point at them.
            assertEquals(900, browser.usedBytes())
            assertEquals(1, browser.library().items.size)
        }

    @Test
    fun `the same bytes never upload twice`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()
            val hash = "a".repeat(64)

            val first = browser.uploadTo(album.id, "a.jpg", sizeBytes = 900, contentHash = hash)
            assertEquals(900, browser.usedBytes())

            // A reinstall offers the same file again. It costs nothing and creates nothing.
            val body =
                """{"files":[{"filename":"a.jpg","contentType":"image/jpeg","sizeBytes":900,"contentHash":"$hash"}]}"""
            val again =
                json.decodeFromString<UploadIntentResponse>(browser.uploadIntent(album.id, body).bodyAsText())
                    .items
                    .single()

            assertEquals(first, again.itemId)
            assertTrue(again.alreadyHeld)
            assertNull(again.uploadUrl)
            assertEquals(900, browser.usedBytes())
            assertEquals(1, browser.library().items.size)
        }

    @Test
    fun `one batch naming the same photograph twice creates one item and charges once`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()
            val hash = "b".repeat(64)

            // A backup that picked the same photograph up twice. Before this, the second copy hit
            // the account's content-hash constraint and the whole batch failed with a 500.
            val file = """{"filename":"a.jpg","contentType":"image/jpeg","sizeBytes":900,"contentHash":"$hash"}"""
            val response = browser.uploadIntent(album.id, """{"files":[$file,$file]}""")
            assertEquals(HttpStatusCode.OK, response.status)

            val items = json.decodeFromString<UploadIntentResponse>(response.bodyAsText()).items
            assertEquals(2, items.size)
            assertEquals(items[0].itemId, items[1].itemId, "both namings are the same item")
            assertNotNull(items[0].uploadUrl)
            assertNull(items[1].uploadUrl, "the second naming has nothing to send")
            assertTrue(items[1].alreadyHeld)

            assertEquals(900, browser.usedBytes(), "the photograph is charged once")
            assertEquals(1, browser.library().items.size)
        }

    @Test
    fun `a file nothing can render is kept, shown as a filename, and refused by an album`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()

            val raw =
                json.decodeFromString<UploadIntentResponse>(
                    browser.post("/api/library/upload-intent") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"files":[{"filename":"DSC_0001.dng","contentType":"image/x-adobe-dng","sizeBytes":40}]}""")
                    }.bodyAsText(),
                ).items.single()

            val item = browser.library().items.single()
            assertEquals("DSC_0001.dng", item.filename)
            assertEquals("file", item.kind)
            assertNull(item.thumbUrl)

            val refused =
                browser.post("/api/albums/${album.id}/items") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"mediaItemIds":["${raw.itemId}"]}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
            assertTrue(refused.bodyAsText().contains("cannot be shown in an album"))
        }

    @Test
    fun `an export carries the library, not only what is in an album`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum("Cornwall").body<AlbumSummary>()
            val inAlbum = browser.uploadTo(album.id, "a.jpg")
            browser.post("/api/library/upload-intent") {
                contentType(ContentType.Application.Json)
                setBody("""{"files":[{"filename":"loose.jpg","contentType":"image/jpeg","sizeBytes":10}]}""")
            }

            val export = browser.get("/api/account/export").body<AccountExport>()
            assertEquals(2, export.library.size)
            assertTrue(export.library.any { it.filename == "loose.jpg" })
            assertEquals(listOf(inAlbum), export.albums.single().items.map { it.id })
        }

    @Test
    fun `an album refuses to publish while an item is only backed up`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()
            val itemId = browser.uploadTo(album.id, "a.jpg")
            harness.backUp(itemId)

            val refused =
                browser.post("/api/albums/${album.id}/share-links") {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertTrue(refused.bodyAsText().contains("a.jpg is not ready to be shared yet"))
        }

    @Test
    fun `a backed-up item is asked for the rest when it joins an album`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()
            val other = browser.createAlbum("Second").body<AlbumSummary>()
            val itemId = browser.uploadTo(album.id, "a.jpg")
            harness.backUp(itemId)
            assertEquals("backed_up", browser.album(album.id).items.single().status)

            browser.post("/api/albums/${other.id}/items") {
                contentType(ContentType.Application.Json)
                setBody("""{"mediaItemIds":["$itemId"]}""")
            }

            // Somebody may look at it now, so the derivatives a viewer needs are wanted.
            assertEquals("uploaded", browser.album(other.id).items.single().status)
        }

    @Test
    fun `a thumbnail is served from the moment an item is backed up`() =
        withApp { harness ->
            val browser = signedIn(harness)
            val album = browser.createAlbum().body<AlbumSummary>()
            val itemId = browser.uploadTo(album.id, "a.jpg")
            harness.backUp(itemId)

            assertNotNull(browser.library().items.single().thumbUrl)
        }
}

private suspend fun HttpClient.caption(
    albumId: String,
    itemId: String,
    caption: String,
) {
    patch("/api/albums/$albumId/items/$itemId") {
        contentType(ContentType.Application.Json)
        setBody("""{"caption":"$caption"}""")
    }
}

/** Marks an item backed up as the worker would after rendering only its thumbnail. */
private fun Harness.backUp(itemId: String) {
    transaction {
        val id = ItemId(UUID.fromString(itemId))
        val key =
            MediaItems.selectAll().where { MediaItems.id eq id }.single()[MediaItems.originalKey]
        val prefix = key.substringBeforeLast('/')
        storage.objects["$prefix/thumb.webp"] = "thumbnail".toByteArray()
        MediaItems.update({ MediaItems.id eq id }) {
            it[status] = ItemState.BACKED_UP
            it[thumbKey] = "$prefix/thumb.webp"
        }
        settleAlbumsHolding(id, OffsetDateTime.now())
    }
}
