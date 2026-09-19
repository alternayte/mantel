package com.mantel.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a person feels, and cannot check by hand.
 *
 * A screen that draws what it already has, a back gesture that returns where it came from, and an
 * album that does not ask the server sixty times while it renders. Each of these regresses in
 * silence: the app keeps working and only feels worse.
 *
 * These run on real coroutines against a transport that answers at once. A virtual clock cannot be
 * used here: Ktor parses a response body off the test dispatcher, so the clock would advance past
 * work it could not see.
 */
class HeldAndPollTest {
    private fun scope() = CoroutineScope(Dispatchers.Default + Job())

    private fun model(
        server: FakeServer,
        scope: CoroutineScope,
    ) = AppModel(
        settings = FakeSettings(),
        backup = FakeBackup(),
        uploads = FakeUploads(),
        api = server::api,
        scope = scope,
    )

    /** Waits for the screen the test is about, rather than guessing at a delay. */
    private suspend fun AppModel.waitFor(
        what: String,
        predicate: (Screen) -> Boolean,
    ) {
        val reached = withTimeoutOrNull(5_000) { screen.first(predicate) }
        assertTrue(reached != null, "the app never reached $what. It is on ${screen.value}")
    }

    @Test
    fun `a screen that has been read before draws before the server answers`() =
        runBlocking {
            val server = FakeServer()
            server.library = """{"items":[${item("i1", "shareable")}],"next":null,"totalItems":1}"""
            val scope = scope()
            val model = model(server, scope)

            model.start()
            model.waitFor("the albums") { it is Screen.Albums }

            model.openLibrary()
            model.waitFor("the library, read") { it is Screen.Library && it.items.size == 1 }

            model.openAlbums()
            model.waitFor("the albums again") { it is Screen.Albums }

            // The server answers nothing from here, so whatever is on the screen was held.
            server.hold()
            model.openLibrary()

            val shown = model.screen.value as Screen.Library
            assertEquals(1, shown.items.size, "the library drew nothing until the read returned")
            assertEquals(1, shown.totalItems)

            server.release()
            scope.cancel()
        }

    @Test
    fun `back from the library returns to the album it was opened from`() =
        runBlocking {
            val server = FakeServer()
            server.albums = """[{"id":"a1","title":"Holiday","status":"draft","itemCount":0,""" +
                """"totalBytes":0,"createdAt":"","updatedAt":""}]"""
            val scope = scope()
            val model = model(server, scope)

            model.start()
            model.waitFor("the albums, read") { it is Screen.Albums && it.albums.size == 1 }

            model.openAlbum("a1")
            model.waitFor("the album") { it is Screen.Album && it.album.id == "a1" }

            model.addFromLibrary()
            model.waitFor("the library, over the album") { it is Screen.Library && it.pickingFor == "a1" }

            model.back()
            model.waitFor("the album it came from") { it is Screen.Album && it.album.id == "a1" }

            scope.cancel()
        }

    @Test
    fun `two minutes of polling costs twenty requests, not sixty`() {
        assertEquals(20, pollRequestsIn(120_000))
        // It is still frequent enough to be a progress report at the start.
        assertEquals(5, pollRequestsIn(10_000))
    }

    @Test
    fun `the poll asks for the album and not for its share links`() =
        runBlocking {
            val server = FakeServer()
            server.album = { id -> emptyAlbum(id, items = item("i1", "processing")) }
            val scope = scope()
            val model = model(server, scope)

            model.openAlbum("a1")
            model.waitFor("the album") { it is Screen.Album && it.album.items.size == 1 }
            val onOpen = server.countOf("/api/albums/a1")

            // One poll, at the two-second beat the schedule starts on.
            delay(3_000)

            assertTrue(
                server.countOf("/api/albums/a1") > onOpen,
                "an album with an item still rendering was never asked about again",
            )
            assertEquals(
                1,
                server.countOf("/api/albums/a1/share-links"),
                "the poll re-read the share links, which do not change while an item renders",
            )
            scope.cancel()
        }
}
