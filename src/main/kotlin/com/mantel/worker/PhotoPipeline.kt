package com.mantel.worker

import java.nio.file.Path
import java.util.concurrent.TimeUnit

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
    fun render(
        source: Path,
        into: Path,
    ): Derivatives {
        val thumb = into.resolve("thumb.webp")
        val displayWebp = into.resolve("display.webp")
        val displayAvif = into.resolve("display.avif")

        run(vips, "thumbnail", source.toString(), "$thumb[Q=80,keep=none]", "300")
        run(vips, "thumbnail", source.toString(), "$displayWebp[Q=82,keep=none]", "1600")
        run(vips, "thumbnail", source.toString(), "$displayAvif[Q=50,keep=none,compression=av1]", "1600")

        return Derivatives(
            thumb = thumb,
            displayWebp = displayWebp,
            displayAvif = displayAvif,
            width = header(source, "width"),
            height = header(source, "height"),
        )
    }

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
