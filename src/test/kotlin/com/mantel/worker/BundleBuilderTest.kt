package com.mantel.worker

import com.mantel.support.RecordingStorage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * The bundle is the promise that an album outlives the service (SDD.md 1). What matters is that it
 * opens on a disk with no network: no web font, no script, no absolute URL, nothing to fetch.
 */
class BundleBuilderTest {
    private fun storageWith(vararg keys: String): RecordingStorage {
        val storage = RecordingStorage()
        keys.forEach { storage.objects[it] = "pretend this is a photograph".toByteArray() }
        return storage
    }

    private val items =
        listOf(
            BundleItem("001-beach.webp", "media/a/display.webp", "photo", "low tide", 1600, 1067),
            BundleItem("002-clip.mp4", "media/b/display.mp4", "video", null, 1920, 1080),
        )

    @Test
    fun `the bundle holds the photographs, a page and the captions`() {
        val storage = storageWith("media/a/display.webp", "media/b/display.mp4")
        val scratch = Files.createTempDirectory("bundle-test")

        val zip = BundleBuilder(storage).build(scratch, "Cornwall 2026", "Three days", false, items)

        ZipFile(zip.toFile()).use { archive ->
            val names = archive.entries().toList().map { it.name }.sorted()
            assertEquals(listOf("captions.txt", "index.html", "photos/001-beach.webp", "photos/002-clip.mp4"), names)

            val captions = archive.getInputStream(archive.getEntry("captions.txt")).readBytes().decodeToString()
            assertEquals("photos/001-beach.webp\tlow tide", captions)
        }
    }

    @Test
    fun `the page has nothing to fetch`() {
        val storage = storageWith("media/a/display.webp", "media/b/display.mp4")
        val scratch = Files.createTempDirectory("bundle-offline")

        val zip = BundleBuilder(storage).build(scratch, "Cornwall 2026", null, false, items)

        ZipFile(zip.toFile()).use { archive ->
            val page = archive.getInputStream(archive.getEntry("index.html")).readBytes().decodeToString()
            assertFalse(page.contains("http://"), "the page reaches out over the network")
            assertFalse(page.contains("https://"), "the page reaches out over the network")
            assertFalse(page.contains("<script"), "the page needs JavaScript to show a photograph")
            assertFalse(page.contains("@import"), "the page pulls in a stylesheet")
            // It shows the photographs and says which album it is.
            assertTrue(page.contains("photos/001-beach.webp"), page.take(400))
            assertTrue(page.contains("<video src=\"photos/002-clip.mp4\""), page)
            assertTrue(page.contains("<title>Cornwall 2026</title>"))
            assertTrue(page.contains("low tide"))
        }
    }

    @Test
    fun `an originals bundle says what originals carry, and a display one does not`() {
        val storage = storageWith("media/a/display.webp", "media/b/display.mp4")
        val withOriginals =
            BundleBuilder(storage).build(Files.createTempDirectory("b1"), "A", null, true, items)
        val displayOnly =
            BundleBuilder(storage).build(Files.createTempDirectory("b2"), "A", null, false, items)

        ZipFile(withOriginals.toFile()).use { archive ->
            val notice = archive.getEntry("ABOUT-THESE-FILES.txt")
            assertTrue(notice != null, "the originals bundle carries no warning")
            val text = archive.getInputStream(notice).readBytes().decodeToString()
            assertTrue(text.contains("where the photograph was taken") || text.contains("place it was taken"), text)
            val page = archive.getInputStream(archive.getEntry("index.html")).readBytes().decodeToString()
            assertTrue(page.contains("location"), "the page does not repeat the warning")
        }
        ZipFile(displayOnly.toFile()).use { archive ->
            assertTrue(archive.getEntry("ABOUT-THESE-FILES.txt") == null)
        }
    }

    @Test
    fun `a title with markup in it cannot break the page`() {
        val storage = storageWith("media/a/display.webp", "media/b/display.mp4")
        val zip =
            BundleBuilder(storage).build(
                Files.createTempDirectory("bundle-escape"),
                """Holiday" /><script>alert(1)</script>""",
                null,
                false,
                items,
            )

        ZipFile(zip.toFile()).use { archive ->
            val page = archive.getInputStream(archive.getEntry("index.html")).readBytes().decodeToString()
            assertFalse(page.contains("<script>alert(1)</script>"))
            assertTrue(page.contains("&lt;script&gt;"))
        }
    }

    @Test
    fun `an album with no captions carries no captions file`() {
        val storage = storageWith("media/a/display.webp")
        val zip =
            BundleBuilder(storage).build(
                Files.createTempDirectory("bundle-plain"),
                "A",
                null,
                false,
                listOf(BundleItem("001-a.webp", "media/a/display.webp", "photo", null, 100, 100)),
            )
        ZipFile(zip.toFile()).use { archive -> assertTrue(archive.getEntry("captions.txt") == null) }
    }
}
