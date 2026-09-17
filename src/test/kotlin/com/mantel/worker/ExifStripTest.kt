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
        // The first line is the summary and carries the file path, which is not metadata.
        return output.lines()
            .drop(1)
            .map { it.substringBefore(':').trim() }
            .filter { field ->
                listOf("exif", "gps", "xmp", "iptc", "make", "model", "orientation")
                    .any { field.lowercase().contains(it) }
            }
    }

    @Test
    fun `the fixture really does carry location and camera data`() {
        val scratch = Files.createTempDirectory("exif-source")
        val source = fixture(scratch)
        val fields = metadataFieldsOf(source)
        assertTrue(fields.any { it.contains("Make") }, "fixture lost its EXIF: $fields")
        assertTrue(source.readBytes().toString(Charsets.ISO_8859_1).contains("MantelTestCam"))
    }

    @Test
    fun `no derivative carries EXIF, GPS or camera data`() {
        val scratch = Files.createTempDirectory("exif-strip")
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
        val scratch = Files.createTempDirectory("exif-sizes")
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
    fun `this libvips can write every derivative the product serves`() {
        // A libvips without an AV1 encoder still has heifsave and fails only on the third
        // derivative, in production. The worker runs this at startup for the same reason.
        pipeline.verifyCodecs()
    }

    @Test
    fun `a file that is not an image fails loudly`() {
        val scratch = Files.createTempDirectory("exif-broken")
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
