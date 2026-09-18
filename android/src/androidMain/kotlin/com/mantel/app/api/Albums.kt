package com.mantel.app.api

import kotlinx.serialization.Serializable

/**
 * The album shapes of the REST API, named as the server names them (SDD.md 6). They are copied, not
 * shared: the server is a JVM build and this is an Android one, and a shared module between them
 * would make the API an internal interface instead of a product surface.
 */
@Serializable
data class AlbumSummary(
    val id: String,
    val title: String,
    val description: String? = null,
    val status: String,
    val itemCount: Int,
    val totalBytes: Long,
    val coverItemId: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class ItemView(
    val id: String,
    val position: Int,
    val kind: String,
    val status: String,
    val byteSize: Long,
    val caption: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Int? = null,
    val lastError: String? = null,
    val filename: String? = null,
    val thumbUrl: String? = null,
)

@Serializable
data class AlbumView(
    val id: String,
    val title: String,
    val description: String? = null,
    val status: String,
    val itemCount: Int,
    val totalBytes: Long,
    val coverItemId: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val items: List<ItemView>,
)

/** `contentHash` is the SHA-256 of the original bytes: the API answers when it already holds them. */
@Serializable
data class DeclaredFile(
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val contentHash: String? = null,
)

@Serializable
data class LibraryPage(val items: List<ItemView>, val next: String? = null, val totalItems: Long = 0)

@Serializable
data class PresignedPart(val partNumber: Int, val uploadUrl: String, val sizeBytes: Long)

@Serializable
data class PresignedUpload(
    val itemId: String,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val uploadUrl: String? = null,
    val uploadId: String? = null,
    val parts: List<PresignedPart>? = null,
    /** The library already holds these bytes. Nothing to send, and nothing was reserved. */
    val alreadyHeld: Boolean = false,
)

@Serializable
data class UploadIntentResponse(val items: List<PresignedUpload>, val expiresInSeconds: Long)

@Serializable
data class CompleteUploadsResponse(val uploaded: List<String>, val missing: List<String>)

@Serializable
data class ReceivedPart(val partNumber: Int, val etag: String, val sizeBytes: Long)

/** What storage already holds for an interrupted upload, and fresh URLs for what it does not. */
@Serializable
data class UploadProgress(
    val itemId: String,
    val uploadId: String,
    val sizeBytes: Long,
    val received: List<ReceivedPart>,
    val remaining: List<PresignedPart>,
)

/**
 * A share link, as the creator sees it. `token` is the unguessable part of the URL and leaks with
 * it; the PIN does not, which is the whole reason the PIN exists (SDD.md 4.3).
 */
@Serializable
data class ShareLinkView(
    val id: String,
    val url: String,
    val token: String,
    val hasPin: Boolean,
    val expiresAt: String? = null,
    val revokedAt: String? = null,
    val createdAt: String,
    val live: Boolean,
)

/**
 * An item's processing state. The wire values are the server's enum; the app matches on them rather
 * than on a message, because the message is for people (SDD.md 6.4).
 */
enum class ItemStatus {
    PENDING_UPLOAD,
    UPLOADED,
    PROCESSING,

    /** The library holds the original and a thumbnail. Nothing renders it for a viewer yet. */
    BACKED_UP,

    /** Every derivative exists, so an album holding it can publish. */
    SHAREABLE,
    FAILED,
    UNKNOWN,
    ;

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.name.equals(wire, ignoreCase = true) } ?: UNKNOWN
    }
}

val ItemView.state: ItemStatus get() = ItemStatus.of(status)

/** Nothing is waiting on it: it is either shown or it failed. */
val ItemView.settled: Boolean get() = state == ItemStatus.SHAREABLE || state == ItemStatus.FAILED
