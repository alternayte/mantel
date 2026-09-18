package com.mantel.app

import android.content.Context
import android.net.Uri
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mantel.app.api.AlbumSummary
import com.mantel.app.api.AlbumView
import com.mantel.app.api.ApiException
import com.mantel.app.api.ItemStatus
import com.mantel.app.api.ItemView
import com.mantel.app.api.MantelApi
import com.mantel.app.api.Me
import com.mantel.app.api.SignInMethods
import com.mantel.app.api.state
import com.mantel.app.auth.Pkce
import com.mantel.app.auth.Settings
import com.mantel.app.media.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What the app is showing, and how it moves between those states.
 *
 * The screens are the shape of the product: sign in, the albums, one album. There is no navigation
 * library here because there is nothing to navigate.
 */
sealed interface Screen {
    data object Starting : Screen

    data class SignIn(
        val serverUrl: String = "",
        val methods: SignInMethods? = null,
        val email: String = "",
        val linkSentTo: String? = null,
        val busy: Boolean = false,
        val error: String? = null,
    ) : Screen

    data class Albums(
        val me: Me,
        val albums: List<AlbumSummary> = emptyList(),
        val newTitle: String = "",
        val busy: Boolean = false,
        val error: String? = null,
    ) : Screen

    data class Album(
        val album: AlbumView,
        val selected: String? = null,
        val caption: String = "",
        val busy: Boolean = false,
        val error: String? = null,
    ) : Screen {
        val selectedItem: ItemView? get() = album.items.firstOrNull { it.id == selected }

        /** An album with anything still moving is worth asking about again. */
        val settling: Boolean
            get() = album.items.any { it.state in MOVING }
    }
}

private val MOVING = setOf(ItemStatus.PENDING_UPLOAD, ItemStatus.UPLOADED, ItemStatus.PROCESSING)

/** A batch on its way to storage, as the worker last reported it. */
data class UploadStatus(
    val filename: String,
    val doneBytes: Long,
    val totalBytes: Long,
    val index: Int,
    val count: Int,
    val failed: String? = null,
)

/** What the screen asks the outside world to do, which only the activity can do. */
sealed interface Effect {
    data class OpenBrowser(val url: String) : Effect

    data object PickMedia : Effect
}

class AppModel(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val context = context.applicationContext
    private val settings = Settings(this.context)

    private val _screen = MutableStateFlow<Screen>(Screen.Starting)
    val screen: StateFlow<Screen> = _screen

    private val _effects = MutableStateFlow<Effect?>(null)
    val effects: StateFlow<Effect?> = _effects

    private val _upload = MutableStateFlow<UploadStatus?>(null)
    val upload: StateFlow<UploadStatus?> = _upload

    private var watching: Job? = null

    /** Who is signed in, so that leaving an album does not have to ask the server again. */
    private var account: Me? = null

    fun effectHandled() {
        _effects.value = null
    }

    // --- sign in ------------------------------------------------------------------------------

    /**
     * A session outlives the process, so the app opens on whatever it held. A session the server no
     * longer honours — revoked, expired, or an account that was deleted — drops back to sign-in
     * rather than showing a screen it cannot fill.
     */
    fun start() {
        scope.launch {
            val serverUrl = settings.serverUrl.first()
            val session = settings.session.first()
            if (serverUrl == null) {
                _screen.value = Screen.SignIn()
                return@launch
            }
            if (session != null) {
                val me = runCatching { api(serverUrl, session).use { it.me() } }.getOrNull()
                if (me != null) {
                    account = me
                    _screen.value = Screen.Albums(me)
                    refreshAlbums()
                    return@launch
                }
                settings.clearSession()
            }
            _screen.value = Screen.SignIn(serverUrl = serverUrl)
            loadMethods(serverUrl)
        }
    }

    fun setServerUrl(url: String) {
        _screen.update<Screen.SignIn> { it.copy(serverUrl = url, methods = null, error = null) }
    }

    fun setEmail(email: String) {
        _screen.update<Screen.SignIn> { it.copy(email = email, error = null) }
    }

    /** Fixes the address this install talks to, and asks it how it can sign somebody in. */
    fun useServer() {
        val state = _screen.value as? Screen.SignIn ?: return
        val url = normalise(state.serverUrl)
        if (url == null) {
            _screen.update<Screen.SignIn> { it.copy(error = "That is not a web address") }
            return
        }
        scope.launch {
            settings.setServerUrl(url)
            _screen.update<Screen.SignIn> { it.copy(serverUrl = url) }
            loadMethods(url)
        }
    }

    /** Back to the sign-in choices, from "check your email" or from a mistyped address. */
    fun startOver() {
        _screen.update<Screen.SignIn> { it.copy(linkSentTo = null, error = null) }
    }

    fun requestMagicLink() {
        val state = _screen.value as? Screen.SignIn ?: return
        val email = state.email.trim()
        scope.launch {
            val verifier = Pkce.verifier()
            settings.startFlow(verifier)
            attempt(state.serverUrl) { api ->
                api.requestMagicLink(email, Pkce.challenge(verifier))
                _screen.update<Screen.SignIn> { it.copy(busy = false, linkSentTo = email) }
            }
        }
    }

    fun signInWithGitHub() {
        val state = _screen.value as? Screen.SignIn ?: return
        scope.launch {
            val verifier = Pkce.verifier()
            settings.startFlow(verifier)
            val url = api(state.serverUrl).use { it.githubSignInUrl(Pkce.challenge(verifier)) }
            _effects.value = Effect.OpenBrowser(url)
        }
    }

    /**
     * The deep link came back. The verifier proves this is the install that started the flow, and it
     * is spent either way: a code that survives a failure is a code worth guessing at.
     */
    fun completeSignIn(code: String) {
        scope.launch {
            val serverUrl = settings.serverUrl.first() ?: return@launch
            val verifier = settings.takeVerifier()
            if (verifier == null) {
                _screen.update<Screen.SignIn> { it.copy(error = "That sign-in did not start on this device") }
                return@launch
            }
            attempt(serverUrl) { api ->
                val session = api.exchange(code, verifier).session
                settings.setSession(session)
                account = api(serverUrl, session).use { it.me() }
                _screen.value = Screen.Albums(account!!)
                refreshAlbums()
            }
        }
    }

    fun signOut() {
        scope.launch {
            watching?.cancel()
            account = null
            val serverUrl = settings.serverUrl.first() ?: return@launch
            val session = settings.session.first()
            // The session row goes whether or not the network is there; the local copy always does.
            runCatching { api(serverUrl, session).use { it.logout() } }
            settings.clearSession()
            _screen.value = Screen.SignIn(serverUrl = serverUrl)
            loadMethods(serverUrl)
        }
    }

    private suspend fun loadMethods(serverUrl: String) {
        attempt(serverUrl) { api ->
            val methods = api.signInMethods()
            _screen.update<Screen.SignIn> { it.copy(busy = false, methods = methods) }
        }
    }

    // --- albums -------------------------------------------------------------------------------

    fun setNewAlbumTitle(title: String) {
        _screen.update<Screen.Albums> { it.copy(newTitle = title, error = null) }
    }

    fun refreshAlbums() {
        scope.launch {
            onAlbums { api, _ ->
                val albums = api.albums()
                _screen.update<Screen.Albums> { it.copy(albums = albums, busy = false) }
            }
        }
    }

    fun createAlbum() {
        val state = _screen.value as? Screen.Albums ?: return
        val title = state.newTitle.trim()
        if (title.isEmpty()) return
        scope.launch {
            onAlbums { api, _ ->
                val created = api.createAlbum(title)
                _screen.update<Screen.Albums> { it.copy(newTitle = "", busy = false) }
                open(created.id)
            }
        }
    }

    fun openAlbum(albumId: String) {
        scope.launch { open(albumId) }
    }

    private suspend fun open(albumId: String) {
        val serverUrl = settings.serverUrl.first() ?: return
        val session = settings.session.first() ?: return
        try {
            val album = api(serverUrl, session).use { it.album(albumId) }
            _screen.value = Screen.Album(album)
            watch(albumId)
        } catch (e: ApiException) {
            _screen.update<Screen.Albums> { it.copy(busy = false, error = e.message) }
        } catch (e: java.io.IOException) {
            _screen.update<Screen.Albums> { it.copy(busy = false, error = "That server did not answer") }
        }
    }

    /** Leaves the album. The albums behind it are re-read, because one of them has just changed. */
    fun back() {
        val me = account ?: return
        watching?.cancel()
        _upload.value = null
        _screen.value = Screen.Albums(me)
        refreshAlbums()
    }

    // --- one album ----------------------------------------------------------------------------

    fun select(itemId: String?) {
        _screen.update<Screen.Album> { state ->
            state.copy(
                selected = itemId,
                caption = state.album.items.firstOrNull { it.id == itemId }?.caption.orEmpty(),
                error = null,
            )
        }
    }

    fun setCaption(text: String) {
        _screen.update<Screen.Album> { it.copy(caption = text) }
    }

    fun saveCaption() {
        val state = _screen.value as? Screen.Album ?: return
        val itemId = state.selected ?: return
        val caption = state.caption.trim().ifEmpty { null }
        scope.launch {
            onAlbum { api, album ->
                api.setCaption(album.id, itemId, caption)
                reload(api, album.id) { it.copy(selected = null) }
            }
        }
    }

    fun setCover() {
        val state = _screen.value as? Screen.Album ?: return
        val itemId = state.selected ?: return
        scope.launch {
            onAlbum { api, album ->
                api.setCover(album.id, itemId)
                reload(api, album.id) { it.copy(selected = null) }
            }
        }
    }

    fun deleteItem() {
        val state = _screen.value as? Screen.Album ?: return
        val itemId = state.selected ?: return
        scope.launch {
            onAlbum { api, album ->
                api.deleteItem(album.id, itemId)
                reload(api, album.id) { it.copy(selected = null) }
            }
        }
    }

    fun retryItem() {
        val state = _screen.value as? Screen.Album ?: return
        val itemId = state.selected ?: return
        scope.launch {
            onAlbum { api, album ->
                api.retryItem(album.id, itemId)
                reload(api, album.id) { it.copy(selected = null) }
            }
        }
    }

    /** The order moves under the finger. The server hears it once, on the drop. */
    fun move(
        from: Int,
        to: Int,
    ) {
        _screen.update<Screen.Album> { state ->
            val items = state.album.items.toMutableList()
            if (from !in items.indices || to !in items.indices) return@update state
            items.add(to, items.removeAt(from))
            state.copy(album = state.album.copy(items = items))
        }
    }

    fun dropOrder() {
        val state = _screen.value as? Screen.Album ?: return
        val order = state.album.items.map { it.id }
        scope.launch {
            onAlbum { api, album -> api.reorder(album.id, order) }
        }
    }

    // --- upload -------------------------------------------------------------------------------

    fun pickMedia() {
        _effects.value = Effect.PickMedia
    }

    fun upload(uris: List<Uri>) {
        val state = _screen.value as? Screen.Album ?: return
        if (uris.isEmpty()) return
        scope.launch {
            UploadWorker.enqueue(context, state.album.id, uris)
            observeUploads(state.album.id)
        }
    }

    /**
     * The worker owns the upload; this only reads what it reports. The album is re-read when a batch
     * finishes, because the items it created are the server's news, not the worker's.
     */
    private fun observeUploads(albumId: String) {
        scope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosByTagFlow(UploadWorker.tagFor(albumId))
                .collect { infos ->
                    val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    if (running != null) {
                        val data = running.progress
                        _upload.value =
                            UploadStatus(
                                filename = data.getString(UploadWorker.PROGRESS_FILE).orEmpty(),
                                doneBytes = data.getLong(UploadWorker.PROGRESS_DONE, 0),
                                totalBytes = data.getLong(UploadWorker.PROGRESS_TOTAL, 0),
                                index = data.getInt(UploadWorker.PROGRESS_INDEX, 0),
                                count = data.getInt(UploadWorker.PROGRESS_COUNT, 0),
                            )
                        return@collect
                    }
                    val failed = infos.firstOrNull { it.state == WorkInfo.State.FAILED }
                    if (failed != null) {
                        _upload.value =
                            UploadStatus("", 0, 0, 0, 0, failed.outputData.getString(UploadWorker.ERROR) ?: "The upload failed")
                        return@collect
                    }
                    if (infos.all { it.state.isFinished }) {
                        _upload.value = null
                        refreshAlbum()
                    }
                }
        }
    }

    fun refreshAlbum() {
        val state = _screen.value as? Screen.Album ?: return
        scope.launch { onAlbum { api, _ -> reload(api, state.album.id) { it } } }
    }

    /** Polls while anything is still uploading or rendering, and stops when nothing is. */
    private fun watch(albumId: String) {
        watching?.cancel()
        observeUploads(albumId)
        watching =
            scope.launch {
                while (true) {
                    delay(POLL_MILLIS)
                    val state = _screen.value as? Screen.Album ?: return@launch
                    if (state.album.id != albumId) return@launch
                    if (!state.settling) continue
                    runCatching { onAlbum { api, _ -> reload(api, albumId) { it } } }
                }
            }
    }

    private suspend fun reload(
        api: MantelApi,
        albumId: String,
        block: (Screen.Album) -> Screen.Album,
    ) {
        val album = api.album(albumId)
        _screen.update<Screen.Album> { block(it.copy(album = album, busy = false, error = null)) }
    }

    // --- plumbing -----------------------------------------------------------------------------

    private suspend fun onAlbums(block: suspend (MantelApi, Me) -> Unit) {
        val state = _screen.value as? Screen.Albums ?: return
        _screen.update<Screen.Albums> { it.copy(busy = true, error = null) }
        withApi(
            onApiError = { message -> _screen.update<Screen.Albums> { it.copy(busy = false, error = message) } },
        ) { api -> block(api, state.me) }
    }

    private suspend fun onAlbum(block: suspend (MantelApi, AlbumView) -> Unit) {
        val state = _screen.value as? Screen.Album ?: return
        _screen.update<Screen.Album> { it.copy(busy = true, error = null) }
        withApi(
            onApiError = { message -> _screen.update<Screen.Album> { it.copy(busy = false, error = message) } },
        ) { api -> block(api, state.album) }
        _screen.update<Screen.Album> { it.copy(busy = false) }
    }

    private suspend fun withApi(
        onApiError: (String) -> Unit,
        block: suspend (MantelApi) -> Unit,
    ) {
        val serverUrl = settings.serverUrl.first() ?: return
        val session = settings.session.first() ?: return
        try {
            api(serverUrl, session).use { block(it) }
        } catch (e: ApiException) {
            if (e.code == "unauthenticated") signOut() else onApiError(e.message)
        } catch (e: java.io.IOException) {
            onApiError("That server did not answer")
        }
    }

    /** Runs a call against the server, and puts whatever it says wrong in front of the person. */
    private suspend fun attempt(
        serverUrl: String,
        block: suspend (MantelApi) -> Unit,
    ) {
        _screen.update<Screen.SignIn> { it.copy(busy = true, error = null) }
        try {
            api(serverUrl).use { block(it) }
        } catch (e: ApiException) {
            _screen.update<Screen.SignIn> { it.copy(busy = false, error = e.message) }
        } catch (e: java.io.IOException) {
            _screen.update<Screen.SignIn> { it.copy(busy = false, error = "That server did not answer") }
        }
    }

    private fun api(
        serverUrl: String,
        session: String? = null,
    ) = MantelApi(serverUrl, session)

    private inline fun <reified T : Screen> MutableStateFlow<Screen>.update(block: (T) -> Screen) {
        val current = value
        if (current is T) value = block(current)
    }

    private companion object {
        const val POLL_MILLIS = 2_000L
    }
}

private inline fun <T> MantelApi.use(block: (MantelApi) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }

/**
 * A self-hoster types an address, not a URL. `albums.example.com` is what somebody writes down, so
 * it is what the app accepts; https is assumed because the session travels over it.
 */
fun normalise(input: String): String? {
    val trimmed = input.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    val host = withScheme.substringAfter("://").substringBefore('/')
    if (!host.contains('.') && host.substringBefore(':') != "localhost") return null
    return withScheme
}
