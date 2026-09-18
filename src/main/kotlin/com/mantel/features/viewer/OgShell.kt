package com.mantel.features.viewer

import com.mantel.features.album.Albums
import com.mantel.features.share.ogKeyFor
import com.mantel.http.WebAssets
import com.mantel.kernel.Clock
import com.mantel.kernel.Config
import com.mantel.kernel.db
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import org.jetbrains.exposed.sql.selectAll

/**
 * `/a/{token}` is served by Ktor rather than by the SPA's static index, so a crawler gets real tags
 * and a human gets the same bundle a moment later (SDD.md 7.1). No second runtime exists for this.
 *
 * A PIN'd album shows a generic image instead of its cover: a preview is fetched with no
 * credentials, so the cover would hand a picture of the album to whoever holds the URL, which is
 * exactly what the PIN was chosen to prevent (SDD.md 4.3).
 */
suspend fun serveOgShell(
    call: ApplicationCall,
    config: Config,
    assets: WebAssets,
    clock: Clock = Clock.system,
) {
    val token = tokenFrom(call)

    // A link that is unknown, revoked or expired is still opened by a person in a browser, so it
    // gets the same page with a 404 on it rather than a JSON error. Unknown and revoked look
    // identical, because telling them apart tells a stranger their forwarded link was real.
    val linked = runCatching { resolveToken(token, clock) }.getOrNull()
    val album =
        linked?.let { db { Albums.selectAll().where { Albums.id eq it.albumId }.singleOrNull() } }

    val status = if (album == null) HttpStatusCode.NotFound else HttpStatusCode.OK
    val title = album?.get(Albums.title)?.value ?: "Album"
    val description = album?.get(Albums.description) ?: album?.let { "${it[Albums.itemCount]} photos" } ?: ""
    val image =
        if (album == null || linked?.needsPin != false) {
            "${config.publicBaseUrl}/og-placeholder.png"
        } else {
            "${config.storage.publicEndpoint ?: config.storage.endpoint}/${config.storage.bucket}/${ogKeyFor(token)}"
        }

    call.respondText(ContentType.Text.Html, status) {
        """
        <!doctype html>
        <html lang="en">
          <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1" />
            <meta name="robots" content="noindex, nofollow" />
            <title>${title.escaped()}</title>
            <meta property="og:type" content="website" />
            <meta property="og:title" content="${title.escaped()}" />
            <meta property="og:description" content="${description.escaped()}" />
            <meta property="og:image" content="${image.escaped()}" />
            <meta name="twitter:card" content="summary_large_image" />
            <link rel="preload" as="image" href="${image.escaped()}" />
            ${assets.headTags()}
          </head>
          <body>
            <div id="root"></div>
            ${assets.bodyTags()}
          </body>
        </html>
        """.trimIndent()
    }
}

/** A title is the creator's text, and it goes into markup. */
private fun String.escaped(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
