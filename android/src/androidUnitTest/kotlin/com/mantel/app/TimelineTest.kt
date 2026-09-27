package com.mantel.app

import com.mantel.app.api.ItemView
import com.mantel.app.timeline.RollPhoto
import com.mantel.app.timeline.buildTimeline
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The timeline shows each photograph once, whether it is on the phone, in the library, or both. The
 * failure a person notices is the same picture twice, and it cannot be seen on a device until the
 * index and the library disagree in just the wrong way.
 */
class TimelineTest {
    private val utc = ZoneOffset.UTC

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun phone(
        name: String,
        takenAt: String,
        size: Long = 1000,
        hash: String? = null,
    ) = RollPhoto(
        uri = "content://media/external/images/media/$name",
        video = false,
        takenAt = ms(takenAt),
        size = size,
        dateModified = 0,
        width = 4000,
        height = 3000,
        hash = hash,
    )

    private fun item(
        id: String,
        takenAt: String,
        size: Long = 1000,
        hash: String? = null,
    ) = ItemView(
        id = id,
        position = 0,
        kind = "photo",
        status = "backed_up",
        byteSize = size,
        takenAt = takenAt,
        contentHash = hash,
    )

    @Test
    fun `a photograph on the phone and in the library is one tile, drawn from the phone`() {
        val timeline =
            buildTimeline(
                roll = listOf(phone("1", "2026-09-20T10:00:00Z", hash = "aa")),
                library = listOf(item("i1", "2026-09-20T10:00:00Z", hash = "aa")),
                libraryComplete = true,
                zone = utc,
            )
        val tile = timeline.tiles.single()
        assertTrue(tile.phone != null)
        assertEquals("i1", tile.item?.id)
        assertTrue(tile.backedUp)
    }

    @Test
    fun `until it is hashed, a library item of the same size from the same moment is held back`() {
        // The library read EXIF with no offset, so its time is the camera's wall clock as UTC: two
        // hours off the phone's real instant, and still the same photograph.
        val timeline =
            buildTimeline(
                roll = listOf(phone("1", "2026-09-20T10:00:00Z", size = 4242)),
                library = listOf(item("i1", "2026-09-20T12:00:00Z", size = 4242, hash = "aa")),
                libraryComplete = true,
                zone = utc,
            )
        assertEquals(1, timeline.tiles.size)
        assertEquals("i1", timeline.tiles.single().item?.id)
    }

    @Test
    fun `a hashed photograph the library does not hold is on the phone only, and a library photograph is its own tile`() {
        val timeline =
            buildTimeline(
                roll = listOf(phone("1", "2026-09-20T10:00:00Z", size = 4242, hash = "bb")),
                library = listOf(item("i1", "2026-09-19T10:00:00Z", size = 4242, hash = "aa")),
                libraryComplete = true,
                zone = utc,
            )
        assertEquals(2, timeline.tiles.size)
        val (newer, older) = timeline.tiles
        assertNull(newer.item, "same size, but the hash says it is a different photograph")
        assertNull(older.phone)
        assertEquals(listOf("2026-09-20", "2026-09-19"), timeline.days.map { it.date.toString() })
    }

    @Test
    fun `while the library has more to send, a phone photograph older than its last page waits`() {
        val timeline =
            buildTimeline(
                roll = listOf(phone("new", "2026-09-20T10:00:00Z"), phone("old", "2026-01-01T10:00:00Z", hash = "cc")),
                library = listOf(item("i1", "2026-09-10T10:00:00Z", size = 7)),
                libraryComplete = false,
                zone = utc,
            )
        assertEquals(listOf("p:content://media/external/images/media/new", "l:i1"), timeline.tiles.map { it.key })
    }

    @Test
    fun `a photograph on the phone whose library copy is in the trash says so, and shows once`() {
        val timeline =
            buildTimeline(
                roll = listOf(phone("1", "2026-09-20T10:00:00Z", hash = "aa"), phone("2", "2026-09-20T09:00:00Z", size = 77)),
                library = emptyList(),
                libraryComplete = true,
                trash = listOf(item("t1", "2026-09-20T10:00:00Z", hash = "aa"), item("t2", "2026-09-20T11:00:00Z", size = 77)),
                zone = utc,
            )
        assertEquals(2, timeline.tiles.size, "a trashed item is never a tile of its own")
        assertTrue(timeline.tiles.all { it.trashed && !it.backedUp })
    }

    @Test
    fun `a photograph taken today shows while the library's first page is also from today`() {
        // A day of three hundred photographs fills the first page with that day. The phone's own
        // photographs from the same day must not wait for the second page to appear.
        val timeline =
            buildTimeline(
                roll = listOf(phone("today", "2026-09-27T18:00:00Z", size = 5)),
                library = listOf(item("i1", "2026-09-27T20:00:00Z", size = 6), item("i2", "2026-09-27T09:00:00Z", size = 7)),
                libraryComplete = false,
                zone = utc,
            )
        assertTrue(timeline.tiles.any { it.key == "p:content://media/external/images/media/today" })
    }
}
