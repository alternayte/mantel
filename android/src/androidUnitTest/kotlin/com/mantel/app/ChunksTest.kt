package com.mantel.app

import com.mantel.app.media.Arrival
import com.mantel.app.media.chunksOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The backup's batches. A mistake here loses photographs silently, and nobody can see it by hand:
 * the library just never has them.
 */
class ChunksTest {
    @Test
    fun `a camera roll larger than the server takes is cut into batches it will take`() {
        val roll = (1..450L).map { Arrival("p$it", added = it) }

        val chunks = chunksOf(roll, size = 50)

        assertEquals(9, chunks.size)
        assertTrue(chunks.all { it.items.size <= 50 })
        assertEquals(roll.map { it.item }, chunks.flatMap { it.items }, "every item, once, in order")
    }

    @Test
    fun `photographs and videos are ordered together, so no watermark steps over an earlier item`() {
        // MediaStore answers photographs, then videos, each in its own order.
        val photographs = listOf(Arrival("photo-early", 10), Arrival("photo-late", 40))
        val videos = listOf(Arrival("video-middle", 20), Arrival("video-last", 50))

        val chunks = chunksOf(photographs + videos, size = 2)

        assertEquals(listOf("photo-early", "video-middle"), chunks[0].items)
        assertEquals(20, chunks[0].watermark)
        // If the second batch fails, the watermark is 20: both of its items are newer and are
        // offered again. Unsorted, the first batch would have ended at 40 and lost video-middle.
        assertTrue(chunks[1].items.all { it.startsWith("photo-late") || it.startsWith("video-last") })
        assertEquals(50, chunks[1].watermark)
    }

    @Test
    fun `nothing found is no batches`() {
        assertEquals(emptyList(), chunksOf(emptyList<Arrival<String>>(), size = 50))
    }
}
