package com.mantel.worker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readBytes

/**
 * A real 4K clip, made by ffmpeg and carrying a GPS location in its container, rendered by the real
 * pipeline. Video is where the product's promises are hardest to keep: the file is large, the job is
 * long, and a phone puts the filming location in the container rather than in EXIF.
 */
class VideoPipelineTest {
    companion object {
        private lateinit var clip: Path

        @BeforeAll
        @JvmStatic
        fun makeClip() {
            val scratch = Files.createTempDirectory("mantel-clip")
            clip = scratch.resolve("source.mp4")
            // Six seconds of 4K with sound, a camera make and a location, as a phone would produce.
            ffmpeg(
                "-f", "lavfi", "-i", "testsrc2=size=3840x2160:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "6",
                "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-c:a", "aac",
                "-metadata", "location=+51.5074-000.1278/",
                "-metadata", "make=MantelTestCam",
                clip.toString(),
            )
        }

        private fun ffmpeg(vararg args: String) {
            val process =
                ProcessBuilder(listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-y") + args)
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(10, TimeUnit.MINUTES)
            check(process.exitValue() == 0) { "ffmpeg failed: $output" }
        }

        fun probe(
            file: Path,
            entries: String,
        ): String {
            val process =
                ProcessBuilder(
                    "ffprobe", "-hide_banner", "-v", "error",
                    "-show_entries", entries,
                    "-of", "default=noprint_wrappers=1", file.toString(),
                ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(2, TimeUnit.MINUTES)
            return output
        }
    }

    private val pipeline = VideoPipeline()

    @Test
    fun `the source really is 4K and really does carry a location`() {
        val details = probe(clip, "stream=width,height:format_tags=location")
        assertTrue(details.contains("width=3840"), details)
        assertTrue(details.contains("location=+51.5074"), details)
    }

    @Test
    fun `a 4K clip becomes one 1080p H264 MP4 with a poster and a thumbnail`() {
        val scratch = Files.createTempDirectory("mantel-video")

        val rendered = pipeline.render(clip, scratch)

        val video = probe(rendered.mp4, "stream=width,height,codec_name")
        assertTrue(video.contains("codec_name=h264"), video)
        assertTrue(video.contains("height=1080"), video)
        assertTrue(video.contains("width=1920"), video)

        // The dimensions reported are the source's, so the creator sees what they filmed.
        assertEquals(3840, rendered.width)
        assertEquals(2160, rendered.height)
        assertTrue(rendered.durationMs in 5_500..6_500, "duration was ${rendered.durationMs}ms")

        assertTrue(Files.size(rendered.poster) > 0)
        assertTrue(Files.size(rendered.thumb) > 0)
        assertEquals(300, PhotoPipeline().widthOf(rendered.thumb))
        assertEquals(1600, PhotoPipeline().widthOf(rendered.poster))
    }

    @Test
    fun `the served video carries no location and no camera name`() {
        val scratch = Files.createTempDirectory("mantel-video-privacy")

        val rendered = pipeline.render(clip, scratch)

        val tags = probe(rendered.mp4, "format_tags:stream_tags")
        assertFalse(tags.contains("51.5074"), "the MP4 still names where it was filmed: $tags")
        assertFalse(tags.lowercase().contains("manteltestcam"), "the MP4 still names the camera: $tags")

        // Nor does the server put its own exact build in the file in place of the camera's.
        assertFalse(Regex("encoder=Lav\\w+\\d").containsMatchIn(tags), "the MP4 names the server's build: $tags")

        listOf(rendered.mp4, rendered.poster, rendered.thumb).forEach { file ->
            val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(bytes.contains("MantelTestCam"), "${file.fileName} names the camera")
            assertFalse(bytes.contains("51.5074"), "${file.fileName} names the location")
        }
    }

    @Test
    fun `the MP4 starts playing before it has finished downloading`() {
        val scratch = Files.createTempDirectory("mantel-video-faststart")

        val rendered = pipeline.render(clip, scratch)

        // faststart puts the moov atom in front of the media data. Without it a viewer waits for
        // the whole file before the first frame.
        val head = rendered.mp4.readBytes().copyOfRange(0, 64).toString(Charsets.ISO_8859_1)
        assertTrue(head.contains("moov"), "moov is not at the front: $head")
    }

    @Test
    fun `a clip with no video stream fails loudly`() {
        val scratch = Files.createTempDirectory("mantel-audio-only")
        val audio = scratch.resolve("audio.m4a")
        ffmpeg("-f", "lavfi", "-i", "sine=frequency=440", "-t", "1", "-c:a", "aac", audio.toString())

        val failure = runCatching { pipeline.render(audio, scratch) }.exceptionOrNull()
        assertTrue(failure is PipelineFailure, "expected a pipeline failure, got $failure")
    }
}
