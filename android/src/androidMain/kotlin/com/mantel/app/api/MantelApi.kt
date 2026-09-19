package com.mantel.app.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The REST API of SDD.md 6, and nothing else. The app is a pure consumer of it: if it wants
 * something the API does not do, that belongs in the API for every client (SDD.md 10).
 */
@Serializable
data class SignInMethods(val magicLink: Boolean, val github: Boolean)

@Serializable
data class NativeSession(val session: String, val expiresAt: String)

@Serializable
data class Me(
    val email: String,
    val displayName: String? = null,
    val storageQuotaBytes: Long,
    val storageUsedBytes: Long,
)

@Serializable
private data class ErrorBody(val error: ErrorDetail)

@Serializable
private data class ErrorDetail(val code: String, val message: String)

/**
 * The documented error shape (SDD.md 6.4), as an exception. `code` is contract and drives
 * behaviour; `message` is what the person reads.
 */
class ApiException(val code: String, override val message: String) : RuntimeException(message)

private val lenientJson = Json { ignoreUnknownKeys = true }

class MantelApi(
    baseUrl: String,
    private val session: String? = null,
    /** The transport. It is a parameter so a test can answer without a server; nothing else sets it. */
    engine: HttpClientEngine = OkHttp.create(),
) {
    private val base = baseUrl.trimEnd('/')

    private val client =
        HttpClient(engine) {
            install(ContentNegotiation) { json(lenientJson) }
            expectSuccess = false
        }

    /** Where the custom tab starts a GitHub sign-in. The browser, not the app, follows this. */
    fun githubSignInUrl(challenge: String): String = "$base/api/auth/github?challenge=$challenge"

    suspend fun signInMethods(): SignInMethods = client.get("$base/api/auth/methods").require()

    suspend fun requestMagicLink(
        email: String,
        challenge: String,
    ) {
        client.post("$base/api/auth/magic-link") {
            contentType(ContentType.Application.Json)
            setBody(MagicLinkRequest(email, challenge))
        }.require<Unit>()
    }

    suspend fun exchange(
        code: String,
        verifier: String,
    ): NativeSession =
        client.post("$base/api/auth/native/exchange") {
            contentType(ContentType.Application.Json)
            setBody(ExchangeRequest(code, verifier))
        }.require()

    suspend fun me(): Me = client.get("$base/api/me") { authorize() }.require()

    // --- albums ---------------------------------------------------------------------------------

    suspend fun albums(): List<AlbumSummary> = client.get("$base/api/albums") { authorize() }.require()

    suspend fun createAlbum(title: String): AlbumSummary =
        client.post("$base/api/albums") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(CreateAlbumRequest(title))
        }.require()

    suspend fun album(albumId: String): AlbumView = client.get("$base/api/albums/$albumId") { authorize() }.require()

    suspend fun setCover(
        albumId: String,
        itemId: String,
    ): AlbumSummary =
        client.patch("$base/api/albums/$albumId") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(UpdateAlbumRequest(coverItemId = itemId))
        }.require()

    suspend fun setCaption(
        albumId: String,
        itemId: String,
        caption: String?,
    ) {
        client.patch("$base/api/albums/$albumId/items/$itemId") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(CaptionRequest(caption))
        }.require<Unit>()
    }

    /** The whole order, every time: a partial reorder would ask the server to guess about the rest. */
    suspend fun reorder(
        albumId: String,
        itemIds: List<String>,
    ) {
        client.patch("$base/api/albums/$albumId/items/reorder") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(ReorderRequest(itemIds))
        }.require<Unit>()
    }

    /** Out of the album, not out of the library: unselecting is not deleting. */
    suspend fun removeFromAlbum(
        albumId: String,
        itemId: String,
    ) {
        client.delete("$base/api/albums/$albumId/items/$itemId") { authorize() }.require<Unit>()
    }

    suspend fun retryItem(
        albumId: String,
        itemId: String,
    ) {
        client.post("$base/api/albums/$albumId/items/$itemId/retry") { authorize() }.require<Unit>()
    }

    // --- sharing --------------------------------------------------------------------------------

    suspend fun shareLinks(albumId: String): List<ShareLinkView> =
        client.get("$base/api/albums/$albumId/share-links") { authorize() }.require()

    /** Creating the first live link is what publishes an album; there is no separate publish. */
    suspend fun createShareLink(
        albumId: String,
        pin: String?,
        expiresInDays: Int?,
    ): ShareLinkView =
        client.post("$base/api/albums/$albumId/share-links") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(CreateShareLinkRequest(pin, expiresInDays))
        }.require()

    suspend fun revokeShareLink(shareLinkId: String) {
        client.delete("$base/api/share-links/$shareLinkId") { authorize() }.require<Unit>()
    }

    // --- upload ---------------------------------------------------------------------------------

    // Media lands in the library. An album is a selection from it, so an upload names no album.
    suspend fun uploadIntent(files: List<DeclaredFile>): UploadIntentResponse =
        client.post("$base/api/library/upload-intent") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(UploadIntentRequest(files))
        }.require()

    suspend fun completeUploads(itemIds: List<String>): CompleteUploadsResponse =
        client.post("$base/api/library/uploads/complete") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(CompleteUploadsRequest(itemIds))
        }.require()

    suspend fun library(
        after: String? = null,
        limit: Int? = null,
    ): LibraryPage {
        val query =
            listOfNotNull(after?.let { "after=$it" }, limit?.let { "limit=$it" })
                .joinToString("&")
                .let { if (it.isEmpty()) "" else "?$it" }
        return client.get("$base/api/library$query") { authorize() }.require()
    }

    suspend fun deleteFromLibrary(itemId: String) {
        client.delete("$base/api/library/$itemId") { authorize() }.require<Unit>()
    }

    /** Selecting library media into an album. It costs no quota and no upload. */
    suspend fun addToAlbum(
        albumId: String,
        mediaItemIds: List<String>,
    ) {
        client.post("$base/api/albums/$albumId/items") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(AddItemsRequest(mediaItemIds))
        }.require<Unit>()
    }

    /** What storage already holds for an interrupted upload, and fresh URLs for what it does not. */
    suspend fun uploadProgress(itemId: String): UploadProgress =
        client.get("$base/api/library/$itemId/upload-progress") { authorize() }.require()

    /**
     * The bytes, straight to storage. They never pass through the API (SDD.md 6.3), so this call
     * carries no session and the URL is signed for exactly this length and type.
     */
    suspend fun putBytes(
        url: String,
        contentType: String,
        length: Long,
        body: suspend (ByteWriteChannel) -> Unit,
    ) {
        val response =
            client.put(url) {
                setBody(
                    object : OutgoingContent.WriteChannelContent() {
                        override val contentType = ContentType.parse(contentType)
                        override val contentLength = length

                        override suspend fun writeTo(channel: ByteWriteChannel) = body(channel)
                    },
                )
            }
        if (response.status.value !in 200..299) {
            throw ApiException("upload_failed", "Storage refused the upload (${response.status.value})")
        }
    }

    suspend fun logout() {
        client.post("$base/api/auth/logout") { authorize() }.require<Unit>()
    }

    fun close() = client.close()

    private fun io.ktor.client.request.HttpRequestBuilder.authorize() {
        session?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    /**
     * A failed call carries the server's code, so the caller can act on `unauthenticated` without
     * reading English. A response that is not the documented shape at all is a broken instance or
     * the wrong address, and says so.
     */
    private suspend inline fun <reified T> HttpResponse.require(): T {
        if (status.value in 200..299) {
            return if (T::class == Unit::class) Unit as T else body()
        }
        val text = bodyAsText()
        val detail =
            runCatching { lenientJson.decodeFromString<ErrorBody>(text).error }
                .getOrNull()
                ?: throw ApiException("unreachable", "That address did not answer as a Mantel server")
        throw ApiException(detail.code, detail.message)
    }

    @Serializable
    private data class CreateAlbumRequest(val title: String)

    @Serializable
    private data class UpdateAlbumRequest(
        val title: String? = null,
        val description: String? = null,
        val coverItemId: String? = null,
    )

    @Serializable
    private data class CaptionRequest(val caption: String? = null)

    @Serializable
    private data class ReorderRequest(val itemIds: List<String>)

    @Serializable
    private data class CreateShareLinkRequest(val pin: String? = null, val expiresInDays: Int? = null)

    @Serializable
    private data class AddItemsRequest(val mediaItemIds: List<String>)

    @Serializable
    private data class UploadIntentRequest(val files: List<DeclaredFile>)

    @Serializable
    private data class CompleteUploadsRequest(val itemIds: List<String>)

    @Serializable
    private data class MagicLinkRequest(val email: String, val challenge: String)

    @Serializable
    private data class ExchangeRequest(val code: String, val verifier: String)
}
