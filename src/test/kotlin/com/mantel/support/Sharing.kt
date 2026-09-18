package com.mantel.support

import com.mantel.features.album.AlbumSummary
import com.mantel.features.media.UploadIntentResponse
import com.mantel.features.share.ShareLinkView
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/** An album with one item already rendered, which is the state a creator shares from. */
suspend fun HttpClient.albumWithReadyPhoto(
    harness: Harness,
    title: String = "Holiday",
): AlbumSummary {
    val album = createAlbum(title).body<AlbumSummary>()
    val intent =
        json.decodeFromString<UploadIntentResponse>(
            uploadIntent(
                album.id,
                """{"files":[{"filename":"a.jpg","contentType":"image/jpeg","sizeBytes":10}]}""",
            ).body<String>(),
        )
    val itemId = intent.items.single().itemId
    val key = harness.storage.presigns.last().key
    harness.storage.objects[key] = "0123456789".toByteArray()
    post("/api/albums/${album.id}/uploads/complete") {
        contentType(ContentType.Application.Json)
        setBody("""{"itemIds":["$itemId"]}""")
    }
    harness.renderItem(itemId, key)
    return album
}

suspend fun HttpClient.share(
    albumId: String,
    body: String = "{}",
): ShareLinkView =
    post("/api/albums/$albumId/share-links") {
        contentType(ContentType.Application.Json)
        setBody(body)
    }.body()
