package com.mantel.worker

import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** What the library needs to show a photograph to its owner: the grid's thumbnail and the 1600 px WebP. */
data class LibraryCopies(val thumb: Path, val displayWebp: Path, val width: Int, val height: Int)

data class Derivatives(val thumb: Path, val displayWebp: Path, val displayAvif: Path, val width: Int, val height: Int)

/**
 * libvips, one process per output. `keep=none` is the EXIF strip: location and device data never
 * reach a served derivative (SDD.md 4.5), which ExifStripTest proves against a real photo.
 *
 * Shelling out rather than binding: the binary is in the image already for the video pipeline's
 * sake, one process per image is cheap next to the decode, and a crash in a codec takes the process
 * rather than the worker.
 */
class PhotoPipeline(private val vips: String = "vips", private val vipsheader: String = "vipsheader") {
    /**
     * libvips writes AVIF only when libheif was built with an AV1 encoder, and a package without one
     * still has heifsave. A worker that starts without it fails every photo on its third derivative,
     * so it is worth one encode at startup to find out.
     */
    fun verifyCodecs() {
        val scratch = java.nio.file.Files.createTempDirectory("mantel-codec-check")
        try {
            val probe = scratch.resolve("probe.png")
            run(vips, "black", probe.toString(), "16", "16")
            run(vips, "copy", probe.toString(), "${scratch.resolve("probe.webp")}[Q=80,keep=none]")
            run(vips, "copy", probe.toString(), "${scratch.resolve("probe.avif")}[Q=50,keep=none,compression=av1]")
        } catch (failure: PipelineFailure) {
            throw PipelineFailure(
                "this build of libvips cannot write the derivatives the product serves: ${failure.message}",
            )
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    /** One thumbnail at a given width, with metadata dropped. The video pipeline uses this too. */
    fun thumbnailTo(
        source: Path,
        target: Path,
        width: Int,
    ) {
        run(vips, "thumbnail", source.toString(), "$target[Q=82,keep=none]", width.toString())
    }

    /**
     * What a backed-up photograph needs and nothing more: a thumbnail for the grid, and the display
     * WebP a phone opens when the photograph is no longer on it. The AVIF is for a recipient.
     */
    fun libraryCopies(
        source: Path,
        into: Path,
    ): LibraryCopies {
        val thumb = into.resolve("thumb.webp")
        val displayWebp = into.resolve("display.webp")
        thumbnailTo(source, thumb, 300)
        thumbnailTo(source, displayWebp, 1600)
        return LibraryCopies(thumb, displayWebp, header(source, "width"), header(source, "height"))
    }

    /** The display WebP alone, for a photograph backed up before every photograph had one. */
    fun displayWebp(
        source: Path,
        into: Path,
    ): Path {
        val displayWebp = into.resolve("display.webp")
        thumbnailTo(source, displayWebp, 1600)
        return displayWebp
    }

    /**
     * When the file says it was taken, read before any derivative strips it. Null when it says
     * nothing believable.
     */
    fun takenAt(source: Path): Instant? = takenAtFromHeader(run(vipsheader, "-a", source.toString()))

    fun render(
        source: Path,
        into: Path,
    ): Derivatives {
        val thumb = into.resolve("thumb.webp")
        val displayWebp = into.resolve("display.webp")
        val displayAvif = into.resolve("display.avif")

        thumbnailTo(source, thumb, 300)
        thumbnailTo(source, displayWebp, 1600)
        run(vips, "thumbnail", source.toString(), "$displayAvif[Q=50,keep=none,compression=av1]", "1600")

        return Derivatives(
            thumb = thumb,
            displayWebp = displayWebp,
            displayAvif = displayAvif,
            width = header(source, "width"),
            height = header(source, "height"),
        )
    }

    fun widthOf(file: Path): Int = header(file, "width")

    private fun header(
        file: Path,
        field: String,
    ): Int = run(vipsheader, "-f", field, file.toString()).trim().toInt()

    private fun run(vararg command: String): String {
        val process =
            ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw PipelineFailure("${command.first()} did not finish within five minutes")
        }
        if (process.exitValue() != 0) {
            throw PipelineFailure("${command.joinToString(" ")} failed: ${output.trim().takeLast(500)}")
        }
        return output
    }
}

class PipelineFailure(message: String) : RuntimeException(message)

private val EXIF_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

/**
 * EXIF `DateTimeOriginal` from `vipsheader -a` output, which prints a field as
 * `exif-ifd2-DateTimeOriginal: 2019:07:14 16:20:05 (2019:07:14 16:20:05, ASCII, 20 components, 20 bytes)`.
 *
 * The field is the camera's wall clock and names no zone. `OffsetTimeOriginal` supplies one where
 * the camera wrote it, as modern phones do. Without it the wall clock is read as UTC, so the date
 * and time a person sees are the ones the camera recorded.
 */
fun takenAtFromHeader(header: String): Instant? {
    fun field(name: String): String? =
        header.lineSequence()
            .firstOrNull { it.startsWith("exif-ifd2-$name:") }
            ?.substringAfter(':')
            ?.trim()
            ?.substringBefore(" (")
            ?.trim()

    val wallClock =
        field("DateTimeOriginal")
            ?.let { runCatching { LocalDateTime.parse(it.take(19), EXIF_DATE_TIME) }.getOrNull() }
            ?: return null
    val offset = field("OffsetTimeOriginal")?.let { runCatching { ZoneOffset.of(it) }.getOrNull() } ?: ZoneOffset.UTC
    return wallClock.toInstant(offset).takeIf { plausiblyTaken(it) }
}

/**
 * A camera with no clock set writes 1970, 1904 or a date that has not happened yet. None of those
 * is when the photograph was taken, and the declared or upload time is a better guess.
 */
fun plausiblyTaken(instant: Instant): Boolean =
    instant.isAfter(Instant.parse("1971-01-01T00:00:00Z")) && instant.isBefore(Instant.now().plusSeconds(86_400))
