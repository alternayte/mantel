package com.mantel.worker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * When a photograph was taken, as libvips prints it. The timeline groups by this date, and a wrong
 * zone moves a photograph taken near midnight onto the wrong day.
 */
class TakenAtTest {
    private val original =
        "exif-ifd2-DateTimeOriginal: 2019:07:14 16:20:05 (2019:07:14 16:20:05, ASCII, 20 components, 20 bytes)"

    @Test
    fun `the offset a phone writes places the moment exactly`() {
        val header = "$original\nexif-ifd2-OffsetTimeOriginal: +02:00 (+02:00, ASCII, 7 components, 7 bytes)"
        assertEquals(Instant.parse("2019-07-14T14:20:05Z"), takenAtFromHeader(header))
    }

    @Test
    fun `without an offset the camera's wall clock is read as UTC`() {
        assertEquals(Instant.parse("2019-07-14T16:20:05Z"), takenAtFromHeader(original))
    }

    @Test
    fun `a camera with no clock set says nothing believable`() {
        val unset = "exif-ifd2-DateTimeOriginal: 0000:00:00 00:00:00 (0000:00:00 00:00:00, ASCII, 20 components, 20 bytes)"
        val epoch = "exif-ifd2-DateTimeOriginal: 1970:01:01 00:00:00 (1970:01:01 00:00:00, ASCII, 20 components, 20 bytes)"
        assertNull(takenAtFromHeader(unset))
        assertNull(takenAtFromHeader(epoch))
        assertNull(takenAtFromHeader("width: 2400\nheight: 1600"))
    }
}
