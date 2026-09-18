package com.mantel.features.share

import com.mantel.features.album.Albums
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import java.util.UUID

@JvmInline
value class ShareLinkId(val value: UUID) {
    override fun toString() = value.toString()
}

/**
 * The unguessable part of the URL. It leaks with the URL, which is the threat the PIN exists for
 * (SDD.md 4.3), so nothing else about the album may be derivable from it.
 */
@JvmInline
value class ShareToken(val value: String) {
    override fun toString() = value
}

object ShareLinks : Table("share_link") {
    val id = uuid("id").transform({ ShareLinkId(it) }, { it.value })
    val albumId =
        reference("album_id", Albums.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION)
    val token = text("token").transform({ ShareToken(it) }, { it.value })
    val pinHash = text("pin_hash").nullable()
    val expiresAt = timestampWithTimeZone("expires_at").nullable()
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

/** Where a link's public link-preview image lives. Unguessable because the token is. */
fun ogKeyFor(token: ShareToken): String = "public/og/$token.webp"

const val PUBLIC_PREFIX = "public/"
