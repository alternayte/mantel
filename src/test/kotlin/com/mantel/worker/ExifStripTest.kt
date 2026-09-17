package com.mantel.worker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readBytes

/**
 * Guarantee: location data is stripped from every served derivative (SDD.md 12).
 *
 * The fixture is a real JPEG carrying GPS coordinates, a camera make and a model. Every derivative
 * the pipeline writes is checked two ways: the metadata reader finds no EXIF field, and the bytes
 * do not contain the camera string. A creator hands an album to a stranger; the photo must not hand
 * over where it was taken.
 */
class ExifStripTest {
    private val pipeline = PhotoPipeline()

    private fun fixture(into: Path): Path {
        val target = into.resolve("original.jpg")
        ExifStripTest::class.java.getResourceAsStream("/exif-gps.jpg")!!.use { source ->
            Files.copy(source, target)
        }
        return target
    }

    private fun metadataFieldsOf(file: Path): List<String> {
        val process = ProcessBuilder("vipsheader", "-a", file.toString()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        // A metadata line is "field-name: value". The summary line and any warning carry the file
        // path, and a path is not metadata however much it looks like one.
        return output.lines()
            .map { it.substringBefore(':').trim() }
            .filter { field -> field.isNotEmpty() && !field.contains('/') && !field.contains(' ') }
            .filter { field ->
                listOf("exif", "gps", "xmp", "iptc", "make", "model", "orientation")
                    .any { field.lowercase().contains(it) }
            }
    }

    @Test
    fun `the fixture really does carry location and camera data`() {
        val scratch = Files.createTempDirectory("mantel-source")
        val source = fixture(scratch)
        val fields = metadataFieldsOf(source)
        assertTrue(fields.any { it.contains("Make") }, "fixture lost its EXIF: $fields")
        assertTrue(source.readBytes().toString(Charsets.ISO_8859_1).contains("MantelTestCam"))
    }

    @Test
    fun `no derivative carries EXIF, GPS or camera data`() {
        val scratch = Files.createTempDirectory("mantel-strip")
        val source = fixture(scratch)

        val rendered = pipeline.render(source, scratch)

        listOf(rendered.thumb, rendered.displayWebp, rendered.displayAvif).forEach { derivative ->
            assertEquals(emptyList<String>(), metadataFieldsOf(derivative), "$derivative kept metadata")
            val bytes = derivative.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(bytes.contains("MantelTestCam"), "$derivative names the camera")
            assertFalse(bytes.contains("Model X"), "$derivative names the model")
            assertTrue(Files.size(derivative) > 0)
        }
    }

    @Test
    fun `the derivatives are the sizes the design asks for`() {
        val scratch = Files.createTempDirectory("mantel-sizes")
        val source = fixture(scratch)

        val rendered = pipeline.render(source, scratch)

        // 300px thumbnail, 1600px display, and the reported dimensions are the original's.
        assertEquals(300, widthOf(rendered.thumb))
        assertEquals(1600, widthOf(rendered.displayWebp))
        assertEquals(1600, widthOf(rendered.displayAvif))
        assertEquals(2400, rendered.width)
        assertEquals(1600, rendered.height)
    }

    @Test
    fun `the check would notice if the strip were removed`() {
        // The same encoder, with metadata kept. If this finds nothing then the test above proves
        // nothing, and a silent change to keep=all would pass unnoticed.
        val scratch = Files.createTempDirectory("mantel-kept")
        val source = fixture(scratch)
        val kept = scratch.resolve("kept.webp")
        ProcessBuilder("vips", "copy", source.toString(), "$kept[Q=80,keep=all]")
            .redirectErrorStream(true)
            .start()
            .waitFor(60, TimeUnit.SECONDS)

        val fields = metadataFieldsOf(kept)
        assertTrue(fields.any { it.lowercase().contains("gps") }, "the detector found no GPS: $fields")
    }

    @Test
    fun `this libvips can write every derivative the product serves`() {
        // A libvips without an AV1 encoder still has heifsave and fails only on the third
        // derivative, in production. The worker runs this at startup for the same reason.
        pipeline.verifyCodecs()
    }

    @Test
    fun `a file that is not an image fails loudly`() {
        val scratch = Files.createTempDirectory("mantel-broken")
        val broken = scratch.resolve("original")
        Files.writeString(broken, "this is not a photograph")

        val failure = runCatching { pipeline.render(broken, scratch) }.exceptionOrNull()
        assertTrue(failure is PipelineFailure, "expected a pipeline failure, got $failure")
    }

    private fun widthOf(file: Path): Int {
        val process = ProcessBuilder("vipsheader", "-f", "width", file.toString()).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(30, TimeUnit.SECONDS)
        return output.toInt()
    }
}
