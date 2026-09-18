package com.mantel.worker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class VideoDerivatives(
    val thumb: Path,
    val poster: Path,
    val mp4: Path,
    val width: Int,
    val height: Int,
    val durationMs: Int,
)

/**
 * ffmpeg for the video, libvips for the still taken out of it, so a video thumbnail is the same kind
 * of WebP as a photo's and the viewer's grid does not care which it is looking at.
 *
 * `-map_metadata -1` is the strip: a phone's video carries GPS in its container just as a photo
 * carries it in EXIF, and the served copy must not (SDD.md 4.5).
 *
 * One 1080p H.264 MP4, used by the web viewer and by the download bundle. HLS is deferred, and a
 * second rendition would double the work for a product whose albums are forty items, not a catalogue.
 */
class VideoPipeline(
    private val ffmpeg: String = "ffmpeg",
    private val ffprobe: String = "ffprobe",
    private val photos: PhotoPipeline = PhotoPipeline(),
) {
    fun render(
        source: Path,
        into: Path,
    ): VideoDerivatives {
        val probe = probe(source)

        // A frame from a second in, so a clip that fades from black still has a picture on it.
        val frameAt = minOf(1.0, probe.durationSeconds / 10)
        val still = into.resolve("poster-source.png")
        run(
            ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
            "-ss", frameAt.toString(),
            "-i", source.toString(),
            "-frames:v", "1",
            still.toString(),
        )

        val poster = into.resolve("poster.webp")
        val thumb = into.resolve("thumb.webp")
        photos.thumbnailTo(still, poster, 1600)
        photos.thumbnailTo(still, thumb, 300)

        val mp4 = into.resolve("display.mp4")
        run(
            ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
            "-i", source.toString(),
            // Every trace of where and on what it was filmed goes here.
            "-map_metadata", "-1",
            // And the server's own exact build does not go in its place.
            "-fflags", "+bitexact", "-flags:v", "+bitexact", "-flags:a", "+bitexact",
            // Downscale to 1080p, never upscale, and keep dimensions even for H.264.
            "-vf", "scale='min(1920,iw)':'min(1080,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2",
            "-c:v", "libx264", "-crf", "23", "-preset", "medium", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "128k",
            // The index goes at the front, so playback starts before the file has arrived.
            "-movflags", "+faststart",
            mp4.toString(),
        )

        return VideoDerivatives(
            thumb = thumb,
            poster = poster,
            mp4 = mp4,
            width = probe.width,
            height = probe.height,
            durationMs = (probe.durationSeconds * 1000).toInt(),
        )
    }

    data class Probe(val width: Int, val height: Int, val durationSeconds: Double)

    fun probe(source: Path): Probe {
        val output =
            run(
                ffprobe, "-hide_banner", "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height:format=duration",
                "-of", "json", source.toString(),
            )
        val root = Json.parseToJsonElement(output).jsonObject
        val stream =
            root["streams"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw PipelineFailure("no video stream in ${source.fileName}")
        val duration =
            root["format"]?.jsonObject?.get("duration")?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: throw PipelineFailure("no duration in ${source.fileName}")
        return Probe(
            width = stream["width"]!!.jsonPrimitive.content.toInt(),
            height = stream["height"]!!.jsonPrimitive.content.toInt(),
            durationSeconds = duration,
        )
    }

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        // A long clip is the normal case here, not a symptom.
        if (!process.waitFor(2, TimeUnit.HOURS)) {
            process.destroyForcibly()
            throw PipelineFailure("${command.first()} did not finish within two hours")
        }
        if (process.exitValue() != 0) {
            throw PipelineFailure("${command.joinToString(" ")} failed: ${output.trim().takeLast(500)}")
        }
        return output
    }
}
