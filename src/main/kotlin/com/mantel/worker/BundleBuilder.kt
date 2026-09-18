package com.mantel.worker

import com.mantel.storage.ObjectStorage
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class BundleItem(
    val filename: String,
    val key: String,
    val kind: String,
    val caption: String?,
    val width: Int?,
    val height: Int?,
)

/**
 * A folder of photographs and one page that shows them, zipped.
 *
 * The page has no JavaScript, no web font and no external request of any kind: it is opened by
 * double-clicking a file on a disk that may not be on a network, possibly years from now. That is
 * what "works offline, forever" has to mean to survive contact with 2040 (SDD.md 1).
 */
class BundleBuilder(private val storage: ObjectStorage) {
    fun build(
        into: Path,
        title: String,
        description: String?,
        includesOriginals: Boolean,
        items: List<BundleItem>,
        onProgress: (Int) -> Unit = {},
    ): Path {
        val zipPath = into.resolve("bundle.zip")
        val scratch = Files.createDirectories(into.resolve("media"))

        ZipOutputStream(Files.newOutputStream(zipPath)).use { zip ->
            items.forEachIndexed { index, item ->
                val local = scratch.resolve(item.filename)
                storage.download(item.key, local)
                zip.putNextEntry(ZipEntry("photos/${item.filename}"))
                Files.copy(local, zip)
                zip.closeEntry()
                Files.deleteIfExists(local)
                onProgress(index + 1)
            }

            zip.putNextEntry(ZipEntry("index.html"))
            zip.write(page(title, description, includesOriginals, items).toByteArray())
            zip.closeEntry()

            val captioned = items.filter { !it.caption.isNullOrBlank() }
            if (captioned.isNotEmpty()) {
                zip.putNextEntry(ZipEntry("captions.txt"))
                zip.write(
                    captioned.joinToString("\n") { "photos/${it.filename}\t${it.caption}" }.toByteArray(),
                )
                zip.closeEntry()
            }

            if (includesOriginals) {
                zip.putNextEntry(ZipEntry("ABOUT-THESE-FILES.txt"))
                zip.write(originalsNotice().toByteArray())
                zip.closeEntry()
            }
        }
        return zipPath
    }

    /** The same reading as the album online: photographs on near-black, nothing else (DESIGN.md). */
    private fun page(
        title: String,
        description: String?,
        includesOriginals: Boolean,
        items: List<BundleItem>,
    ): String {
        val figures =
            items.joinToString("\n") { item ->
                val media =
                    if (item.kind == "video") {
                        """<video src="photos/${item.filename}" controls playsinline></video>"""
                    } else {
                        val size =
                            if (item.width != null && item.height != null) {
                                """ width="${item.width}" height="${item.height}""""
                            } else {
                                ""
                            }
                        """<img src="photos/${item.filename}" alt=""$size loading="lazy">"""
                    }
                val caption = item.caption?.takeIf { it.isNotBlank() }?.let { "<figcaption>${it.escaped()}</figcaption>" }
                "      <figure>$media${caption ?: ""}</figure>"
            }

        return """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>${title.escaped()}</title>
                <style>
                  :root { --surface: #0c0c0d; --ink: #ece9e4; --muted: #87837c; }
                  * { box-sizing: border-box; }
                  html { background: var(--surface); }
                  body {
                    margin: 0; background: var(--surface); color: var(--ink);
                    font-family: ui-sans-serif, -apple-system, "Helvetica Neue", Arial, sans-serif;
                  }
                  header { padding: 4rem 2vw 3rem; text-align: center; }
                  h1 {
                    margin: 0; font-size: .82rem; font-weight: 500; letter-spacing: .2em;
                    text-transform: uppercase; color: var(--muted);
                  }
                  p.note { margin: .75rem auto 0; max-width: 46ch; font-size: .78rem; color: var(--muted); }
                  main {
                    width: min(96vw, 1200px); margin: 0 auto; padding-bottom: 8rem;
                    display: flex; flex-direction: column; gap: 10vh;
                  }
                  figure { margin: 0; }
                  img, video { display: block; width: 100%; height: auto; }
                  figcaption { margin-top: .9rem; font-size: .78rem; color: var(--muted); }
                </style>
              </head>
              <body>
                <header>
                  <h1>${title.escaped()}</h1>
                  ${description?.takeIf { it.isNotBlank() }?.let { "<p class=\"note\">${it.escaped()}</p>" } ?: ""}
                  ${if (includesOriginals) "<p class=\"note\">These are the original files. They may carry the location and the device they were taken with.</p>" else ""}
                </header>
                <main>
            $figures
                </main>
              </body>
            </html>
            """.trimIndent()
    }

    private fun originalsNotice(): String =
        """
        These are the original files, exactly as the camera or phone wrote them.

        An original photograph often carries the place it was taken and the device that took it.
        The copies Mantel serves online have that removed; these do not.

        If you pass these files on, you are passing that on too.
        """.trimIndent()

    private fun String.escaped(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
