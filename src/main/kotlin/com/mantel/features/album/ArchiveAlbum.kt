package com.mantel.features.album

import com.mantel.kernel.Clock
import com.mantel.kernel.db
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Archiving hides the album and revokes nothing else yet; the bytes and the quota they hold go
 * when the purge job runs (M10). A creator who wants the space back deletes the items.
 */
suspend fun archiveAlbum(
    call: ApplicationCall,
    clock: Clock = Clock.system,
) {
    val album = requireOwnAlbum(call, albumIdFrom(call))
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    db {
        Albums.update({ Albums.id eq album[Albums.id] }) {
            it[status] = AlbumStatus.ARCHIVED
            it[archivedAt] = now
            it[updatedAt] = now
        }
    }
    call.respond(HttpStatusCode.NoContent)
}
