package com.mantel.features.viewer

import com.mantel.features.album.AlbumId
import com.mantel.features.album.Albums
import com.mantel.features.media.ItemState
import com.mantel.features.media.MediaItems
import com.mantel.features.share.ShareLinks
import com.mantel.features.share.ShareToken
import com.mantel.features.share.isLive
import com.mantel.kernel.Clock
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.db
import com.mantel.storage.ObjectStorage
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Serializable
data class ManifestItem(
    val id: String,
    val kind: String,
    val status: String,
    val caption: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Int? = null,
    val thumbUrl: String? = null,
    val displayWebpUrl: String? = null,
    val displayAvifUrl: String? = null,
    val posterUrl: String? = null,
    val mp4Url: String? = null,
)

/**
 * What the viewer renders. It carries no creator email, no account id and no album id: a recipient
 * learns the title, the pictures and nothing about who made it (SDD.md 6.2).
 */
@Serializable
data class Manifest(
    val title: String,
    val description: String? = null,
    val status: String,
    val itemCount: Int,
    val readyCount: Int,
    val items: List<ManifestItem>,
)

/** Long enough that a viewer reading an album does not watch a picture expire mid-scroll. */
private val MEDIA_URL_LIFETIME: Duration = Duration.ofHours(6)

class LinkedAlbum(val albumId: AlbumId, val needsPin: Boolean)

/**
 * Resolves a token to its album. A revoked, expired or unknown token is 404 and says no more:
 * confirming that a token once existed tells a stranger they have a real link (SDD.md 4.3).
 */
suspend fun resolveToken(
    token: ShareToken,
    clock: Clock,
): LinkedAlbum {
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    return db {
        val link =
            ShareLinks.selectAll().where { ShareLinks.token eq token }.singleOrNull()
                ?: throw DomainException(ErrorCode.NOT_FOUND, "No such album")
        if (!link.isLive(now)) throw DomainException(ErrorCode.NOT_FOUND, "No such album")
        LinkedAlbum(link[ShareLinks.albumId], link[ShareLinks.pinHash] != null)
    }
}

fun tokenFrom(call: ApplicationCall): ShareToken =
    call.parameters["token"]?.takeIf { it.isNotBlank() }?.let { ShareToken(it) }
        ?: throw DomainException(ErrorCode.NOT_FOUND, "No such album")

suspend fun getManifest(
    call: ApplicationCall,
    config: com.mantel.kernel.Config,
    storage: ObjectStorage,
    clock: Clock = Clock.system,
) {
    val token = tokenFrom(call)
    val linked = resolveToken(token, clock)

    if (linked.needsPin && !call.hasUnlocked(token, config.cookieSecret, clock)) {
        throw DomainException(ErrorCode.PIN_REQUIRED, "This album is protected by a PIN")
    }

    val album =
        db { Albums.selectAll().where { Albums.id eq linked.albumId }.singleOrNull() }
            ?: throw DomainException(ErrorCode.NOT_FOUND, "No such album")
    val rows =
        db {
            MediaItems.selectAll()
                .where { MediaItems.albumId eq linked.albumId }
                .orderBy(MediaItems.position to SortOrder.ASC)
                .toList()
        }

    val items = withContext(Dispatchers.IO) { rows.map { it.toManifestItem(storage) } }

    call.respond(
        Manifest(
            title = album[Albums.title].value,
            description = album[Albums.description],
            status = album[Albums.status].wire,
            itemCount = rows.size,
            readyCount = rows.count { it[MediaItems.status] == ItemState.READY },
            items = items,
        ),
    )
}

/**
 * An item that is still processing appears with its state and no URLs, so the viewer can show a
 * placeholder rather than a broken grid, and a failed one does not silently disappear (SDD.md 4.4).
 */
private fun ResultRow.toManifestItem(storage: ObjectStorage): ManifestItem {
    val ready = this[MediaItems.status] == ItemState.READY

    fun url(key: String?) = key?.takeIf { ready }?.let { storage.presignGetForThisHour(it, MEDIA_URL_LIFETIME) }

    return ManifestItem(
        id = this[MediaItems.id].toString(),
        kind = this[MediaItems.kind].wire,
        status = this[MediaItems.status].wire,
        caption = this[MediaItems.caption],
        width = this[MediaItems.width],
        height = this[MediaItems.height],
        durationMs = this[MediaItems.durationMs],
        thumbUrl = url(this[MediaItems.thumbKey]),
        displayWebpUrl = url(this[MediaItems.displayWebpKey]),
        displayAvifUrl = url(this[MediaItems.displayAvifKey]),
        posterUrl = url(this[MediaItems.posterKey]),
        mp4Url = url(this[MediaItems.mp4Key]),
    )
}
