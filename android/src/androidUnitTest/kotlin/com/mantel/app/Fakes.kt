package com.mantel.app

import android.net.Uri
import com.mantel.app.api.MantelApi
import com.mantel.app.api.Me
import com.mantel.app.auth.Settings
import com.mantel.app.media.Backup
import com.mantel.app.media.MediaFolder
import com.mantel.app.media.SyncState
import com.mantel.app.media.UploadReport
import com.mantel.app.media.Uploads
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * What `AppModel` talks to, without a device.
 *
 * The API is not faked: the real `MantelApi` runs against a mock transport, so what these tests see
 * is the client the app ships, including its URLs and its deserialisation.
 */
class FakeSettings(
    serverUrl: String? = "https://albums.example.com",
    session: String? = "a-session",
) : Settings {
    override val serverUrl = MutableStateFlow(serverUrl)
    override val session = MutableStateFlow(session)
    override val me = MutableStateFlow<Me?>(null)

    override suspend fun setMe(me: Me) {
        this.me.value = me
    }

    override suspend fun setServerUrl(url: String) {
        serverUrl.value = url
    }

    override suspend fun setSession(secret: String) {
        session.value = secret
    }

    override suspend fun clearSession() {
        session.value = null
        me.value = null
    }

    override suspend fun startFlow(verifier: String) = Unit

    override suspend fun takeVerifier(): String? = null
}

class FakeBackup : Backup {
    override val state = MutableStateFlow(SyncState())

    override suspend fun setEnabled(enabled: Boolean) = Unit

    override suspend fun setFolders(folders: Set<String>) = Unit

    override suspend fun setUnmeteredOnly(value: Boolean) = Unit

    override suspend fun setWhileCharging(value: Boolean) = Unit

    override suspend fun folders(): List<MediaFolder> = emptyList()

    override suspend fun reschedule() = Unit

    override fun runNow() = Unit
}

class FakeUploads : Uploads {
    override suspend fun enqueue(
        albumId: String?,
        uris: List<Uri>,
    ) = Unit

    override fun reports(albumId: String?): Flow<UploadReport> = emptyFlow()
}

/**
 * A server that answers from a script and counts what it was asked.
 *
 * [hold] makes every answer wait, which is how a test sees what a screen shows while a read is in
 * flight. The real `MantelApi` runs against this, so the URLs and the deserialisation under test are
 * the ones the app ships. The transport answers in the caller's own coroutine, so a test's virtual
 * clock sees the whole call rather than losing it to a thread pool.
 */
class FakeServer {
    val requests = mutableListOf<String>()
    private val gate = CompletableDeferred<Unit>()
    private var holding = false

    var me: String = """{"email":"a@example.com","storageQuotaBytes":100,"storageUsedBytes":0}"""
    var albums: String = "[]"
    var album: (String) -> String = { emptyAlbum(it) }
    var shareLinks: String = "[]"
    var library: String = """{"items":[],"next":null,"totalItems":0}"""

    /** Nothing answers until [release]. */
    fun hold() {
        holding = true
    }

    fun release() {
        gate.complete(Unit)
    }

    fun countOf(path: String): Int = requests.count { it == path }

    fun api(
        baseUrl: String,
        session: String?,
    ): MantelApi = MantelApi(baseUrl, session, InlineEngine(::answer))

    private suspend fun answer(path: String): String {
        requests += path
        if (holding) gate.await()
        return when {
            path == "/api/me" -> me
            path == "/api/albums" -> albums
            path.startsWith("/api/albums/") && path.endsWith("/share-links") -> shareLinks
            path.startsWith("/api/albums/") -> album(path.removePrefix("/api/albums/"))
            path == "/api/library" -> library
            else -> "{}"
        }
    }
}

/**
 * A transport that answers in the caller's coroutine.
 *
 * Ktor's own mock engine answers on a thread pool, which a virtual clock cannot see: a test would
 * advance two minutes and find that nothing had happened yet.
 */
private class InlineEngine(
    private val handler: suspend (String) -> String,
) : HttpClientEngineBase("inline") {
    override val config = HttpClientEngineConfig()
    override val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined

    @InternalAPI
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val body = handler(data.url.encodedPath)
        return HttpResponseData(
            statusCode = HttpStatusCode.OK,
            requestTime = GMTDate(),
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            version = HttpProtocolVersion.HTTP_1_1,
            body = ByteReadChannel(body),
            // Its own job. The caller's would make closing the client wait on the coroutine that
            // is closing it.
            callContext = Dispatchers.Unconfined + Job(),
        )
    }
}

/** An album, as the server describes one. */
fun emptyAlbum(
    id: String,
    items: String = "",
) = """{"id":"$id","title":"Holiday","status":"draft","itemCount":0,"totalBytes":0,""" +
    """"createdAt":"","updatedAt":"","items":[$items]}"""

/** One item, in whatever state the test needs it in. */
fun item(
    id: String,
    status: String,
) = """{"id":"$id","position":0,"kind":"photo","status":"$status","byteSize":1}"""
