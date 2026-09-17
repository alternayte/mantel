package com.mantel.features.album

import com.mantel.features.media.ItemId
import com.mantel.features.media.MediaItems
import com.mantel.kernel.Clock
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.db
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Serializable
data class UpdateAlbumRequest(
    val title: String? = null,
    val description: String? = null,
    val coverItemId: String? = null,
)

suspend fun updateAlbum(
    call: ApplicationCall,
    clock: Clock = Clock.system,
) {
    val album = requireOwnAlbum(call, albumIdFrom(call))
    val albumId = album[Albums.id]
    val request = call.receive<UpdateAlbumRequest>()

    val title = request.title?.let { AlbumTitle.of(it) }

    // The cover is one of this album's own items, which is the whole reason this is checked here
    // and not left to the foreign key.
    val cover =
        request.coverItemId?.let { raw ->
            val itemId =
                runCatching { ItemId(UUID.fromString(raw)) }.getOrNull()
                    ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "That is not an item id")
            db {
                MediaItems.selectAll()
                    .where { (MediaItems.id eq itemId) and (MediaItems.albumId eq albumId) }
                    .singleOrNull()
            } ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "That item is not in this album")
            itemId
        }

    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    db {
        Albums.update({ Albums.id eq albumId }) { statement ->
            title?.let { statement[Albums.title] = it }
            request.description?.let { statement[description] = it.trim().ifEmpty { null } }
            cover?.let { statement[coverItemId] = it }
            statement[updatedAt] = now
        }
    }
    val updated = db { Albums.selectAll().where { Albums.id eq albumId }.single() }
    call.respond(HttpStatusCode.OK, updated.toSummary())
}
