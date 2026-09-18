package com.mantel.features.share

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A name that arrived from a browser ends up as a path inside a ZIP that somebody unpacks. It is
 * not trusted to be one.
 */
class BundleFilenameTest {
    @Test
    fun `the creator's own name survives, with the derivative's extension`() {
        assertEquals("001-beach.webp", bundleFilename(1, "beach.JPG", "media/x/display.webp"))
        assertEquals("042-clip.mp4", bundleFilename(42, "clip.mov", "media/x/display.mp4"))
    }

    @Test
    fun `a path cannot escape the folder it is unpacked into`() {
        assertEquals("001-passwd.webp", bundleFilename(1, "../../../etc/passwd", "media/x/display.webp"))
        assertEquals("002-evil.webp", bundleFilename(2, "..\\..\\windows\\evil.exe", "media/x/display.webp"))
        assertEquals("003-photo.webp", bundleFilename(3, "../../..", "media/x/display.webp"))
    }

    @Test
    fun `an unusable name falls back rather than producing nothing`() {
        assertEquals("004-photo.webp", bundleFilename(4, null, "media/x/display.webp"))
        assertEquals("005-photo.webp", bundleFilename(5, "   ", "media/x/display.webp"))
        assertEquals("006-photo.webp", bundleFilename(6, "😀😀.jpg", "media/x/display.webp"))
    }

    @Test
    fun `spaces and length are tamed`() {
        assertEquals("007-a-day-at-the-sea.webp", bundleFilename(7, "a day at the sea.jpg", "media/x/display.webp"))
        val long = bundleFilename(8, "x".repeat(200) + ".jpg", "media/x/display.webp")
        assertEquals(69, long.length, long)
    }

    @Test
    fun `numbering keeps the album in order in a file manager`() {
        val names = (1..11).map { bundleFilename(it, "photo.jpg", "media/x/display.webp") }
        assertEquals(names, names.sorted())
    }
}
