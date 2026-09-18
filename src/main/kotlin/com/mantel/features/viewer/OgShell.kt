package com.mantel.features.viewer

import com.mantel.features.album.Albums
import com.mantel.features.share.ogKeyFor
import com.mantel.kernel.Clock
import com.mantel.kernel.Config
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
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
    clock: Clock = Clock.system,
) {
    val token = tokenFrom(call)
    val linked = resolveToken(token, clock)

    val album =
        db { Albums.selectAll().where { Albums.id eq linked.albumId }.singleOrNull() }
            ?: throw DomainException(ErrorCode.NOT_FOUND, "No such album")

    val title = album[Albums.title].value
    val description = album[Albums.description] ?: "${album[Albums.itemCount]} photos"
    val image =
        if (linked.needsPin) {
            "${config.publicBaseUrl}/og-placeholder.png"
        } else {
            "${config.storage.publicEndpoint ?: config.storage.endpoint}/${config.storage.bucket}/${ogKeyFor(token)}"
        }

    call.respondText(ContentType.Text.Html, HttpStatusCode.OK) {
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
          </head>
          <body>
            <div id="root"></div>
            <script type="module" src="/app/assets/index.js"></script>
          </body>
        </html>
        """.trimIndent()
    }
}

/** A title is the creator's text, and it goes into markup. */
private fun String.escaped(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
