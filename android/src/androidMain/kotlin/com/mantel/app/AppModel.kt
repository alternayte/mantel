package com.mantel.app

import android.content.Context
import android.net.Uri
import com.mantel.app.api.AlbumSummary
import com.mantel.app.api.AlbumView
import com.mantel.app.api.ApiException
import com.mantel.app.api.ItemStatus
import com.mantel.app.api.ItemView
import com.mantel.app.api.MantelApi
import com.mantel.app.api.Me
import com.mantel.app.api.ShareLinkView
import com.mantel.app.api.SignInMethods
import com.mantel.app.api.state
import com.mantel.app.auth.Pkce
import com.mantel.app.auth.Settings
import com.mantel.app.auth.StoredSettings
import com.mantel.app.media.Backup
import com.mantel.app.media.DeviceMedia
import com.mantel.app.media.MediaFolder
import com.mantel.app.media.PhoneBackup
import com.mantel.app.media.UploadReport
import com.mantel.app.media.Uploads
import com.mantel.app.media.WorkManagerUploads
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
 * The screens are the shape of the product: sign in, the two peers — the albums and the library —
 * and the screens you visit from them. There is no navigation library here; the stack is a list in
 * the model, because six screens and one back gesture do not need routes (DESIGN.md).
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
        val refreshing: Boolean = false,
        val error: String? = null,
        val retryable: Boolean = false,
    ) : Screen

    data class Library(
        val items: List<ItemView> = emptyList(),
        val totalItems: Long = 0,
        val selected: Set<String> = emptySet(),
        val albums: List<AlbumSummary> = emptyList(),
        val addingTo: Boolean? = null,
        /** The album this library was opened to add to, if it was opened from one. */
        val pickingFor: String? = null,
        /** Where the next page starts. Null once the library has all of it. */
        val cursor: String? = null,
        val loadingMore: Boolean = false,
        val busy: Boolean = false,
        val refreshing: Boolean = false,
        val error: String? = null,
        val retryable: Boolean = false,
    ) : Screen

    data class Sync(
        val enabled: Boolean = false,
        val folders: List<MediaFolder> = emptyList(),
        val selected: Set<String> = emptySet(),
        val unmeteredOnly: Boolean = true,
        val whileCharging: Boolean = true,
        val lastRunAt: Long = 0,
        val busy: Boolean = false,
        val error: String? = null,
    ) : Screen

    data class Album(
        val album: AlbumView,
        val selected: String? = null,
        val caption: String = "",
        val links: List<ShareLinkView> = emptyList(),
        val sharing: Boolean = false,
        val pin: String = "",
        val expiresInDays: Int? = null,
        val busy: Boolean = false,
        val error: String? = null,
        val retryable: Boolean = false,
    ) : Screen {
        val selectedItem: ItemView? get() = album.items.firstOrNull { it.id == selected }

        /** A link nobody has revoked and nothing has expired: what a recipient can still open. */
        val liveLinks: List<ShareLinkView> get() = links.filter { it.live }

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

    /** Reading the device's media, which sync needs and the picker does not. */
    data object AskForMediaAccess : Effect

    /** The system share sheet. A link is shared through whatever the person already uses. */
    data class ShareText(val url: String) : Effect
}

/**
 * What the app has already read from the server, kept across a screen change.
 *
 * A screen that starts empty and fills when the network answers is the whole of the lag people
 * describe as slowness, and no animation hides it (DESIGN.md). So a screen draws this first and
 * refreshes underneath it. It is memory only: a cold start reads the server, because a copy from
 * days ago is worse than a blank screen.
 */
private class Held {
    var albums: List<AlbumSummary>? = null
    var library: LibraryHeld? = null
    val opened = mutableMapOf<String, Pair<AlbumView, List<ShareLinkView>>>()

    fun forget(albumId: String) {
        opened.remove(albumId)
    }
}

private data class LibraryHeld(
    val items: List<ItemView>,
    val totalItems: Long,
    val cursor: String?,
)

class AppModel(
    private val settings: Settings,
    private val backup: Backup,
    private val uploads: Uploads,
    private val api: (String, String?) -> MantelApi,
    private val scope: CoroutineScope,
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        settings = StoredSettings(context.applicationContext),
        backup = PhoneBackup(context.applicationContext),
        uploads = WorkManagerUploads(context.applicationContext),
        api = { url, session -> MantelApi(url, session) },
        scope = scope,
    )

    private val _screen = MutableStateFlow<Screen>(Screen.Starting)
    val screen: StateFlow<Screen> = _screen

    private val _effects = MutableStateFlow<Effect?>(null)
    val effects: StateFlow<Effect?> = _effects

    private val _upload = MutableStateFlow<UploadStatus?>(null)
    val upload: StateFlow<UploadStatus?> = _upload

    /** What the phone's backup is doing, or what went wrong with it. */
    private val _backupStatus = MutableStateFlow<UploadStatus?>(null)
    val backupStatus: StateFlow<UploadStatus?> = _backupStatus

    /** Whether a back gesture has somewhere to go. A peer is the bottom of the stack. */
    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack

    private val stack = ArrayDeque<Screen>()
    private val held = Held()

    private var watching: Job? = null
    private var reporting: Job? = null

    /**
     * The backup, watched for the whole session rather than while a screen is open. Nothing else
     * watches it: a batch the phone uploads on its own has no screen of its own, and before this it
     * could fail with nobody ever told.
     */
    private var backupWatch: Job? = null

    /** The album poll runs while the app is in front of somebody, and not in a pocket. */
    private val resumed = MutableStateFlow(true)

    /** Who is signed in, so that leaving an album does not have to ask the server again. */
    private var account: Me? = null

    fun effectHandled() {
        _effects.value = null
    }

    fun resumed(value: Boolean) {
        resumed.value = value
    }

    // --- navigation ---------------------------------------------------------------------------

    /** A screen you visit. Back returns to whatever you were on. */
    private fun push(screen: Screen) {
        stack.addLast(_screen.value)
        show(screen)
    }

    /** A peer replaces the other peer and is the bottom of the stack (DESIGN.md). */
    private fun peer(screen: Screen) {
        stack.clear()
        show(screen)
    }

    /** A screen that is not part of the stack at all: sign-in, and the app starting. */
    private fun root(screen: Screen) {
        stack.clear()
        show(screen)
    }

    private fun show(screen: Screen) {
        watching?.cancel()
        reporting?.cancel()
        _upload.value = null
        _screen.value = screen
        _canGoBack.value = stack.isNotEmpty()
    }

    /** Leaves a screen for the one beneath it, which is already filled and is re-read underneath. */
    fun back() {
        val beneath = stack.removeLastOrNull() ?: return
        show(beneath)
        when (beneath) {
            is Screen.Albums -> refreshAlbums()
            is Screen.Library -> refreshLibrary()
            is Screen.Album -> resume(beneath.album.id)
            else -> Unit
        }
    }

    /** The albums, as a peer. */
    fun openAlbums() {
        val me = account ?: return
        peer(Screen.Albums(me, albums = held.albums.orEmpty()))
        refreshAlbums()
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
                root(Screen.SignIn())
                return@launch
            }
            if (session != null) {
                try {
                    val me = api(serverUrl, session).use { it.me() }
                    settings.setMe(me)
                    account = me
                    watchBackup()
                    root(Screen.Albums(me))
                    refreshAlbums()
                    return@launch
                } catch (e: java.io.IOException) {
                    // Offline is not signed out. The session is still good, so the app opens on
                    // the account it last saw and offers to ask again.
                    val cached = settings.me.first()
                    if (cached != null) {
                        account = cached
                        root(
                            Screen.Albums(
                                cached,
                                error = "That server did not answer. You may be offline.",
                                retryable = true,
                            ),
                        )
                        return@launch
                    }
                    root(
                        Screen.SignIn(
                            serverUrl = serverUrl,
                            error = "That server did not answer. You may be offline.",
                        ),
                    )
                    return@launch
                } catch (e: ApiException) {
                    // The server answered and refused: this session is over.
                    settings.clearSession()
                }
            }
            root(Screen.SignIn(serverUrl = serverUrl))
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
            val url = api(state.serverUrl, null).use { it.githubSignInUrl(Pkce.challenge(verifier)) }
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
                val me = api(serverUrl, session).use { it.me() }
                settings.setMe(me)
                account = me
                watchBackup()
                root(Screen.Albums(me))
                refreshAlbums()
            }
        }
    }

    fun signOut() {
        scope.launch {
            account = null
            backupWatch?.cancel()
            _backupStatus.value = null
            held.albums = null
            held.library = null
            held.opened.clear()
            val serverUrl = settings.serverUrl.first() ?: return@launch
            val session = settings.session.first()
            // The session row goes whether or not the network is there; the local copy always does.
            runCatching { api(serverUrl, session).use { it.logout() } }
            settings.clearSession()
            root(Screen.SignIn(serverUrl = serverUrl))
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
                held.albums = albums
                _screen.update<Screen.Albums> { it.copy(albums = albums, busy = false, refreshing = false) }
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
                held.albums = null
                _screen.update<Screen.Albums> { it.copy(newTitle = "", busy = false) }
                open(created.id)
            }
        }
    }

    // --- backup -------------------------------------------------------------------------------

    fun openSync() {
        push(Screen.Sync())
        // The worker writes when it last ran, so the screen follows the settings rather than
        // reading them once and then telling the person something that stopped being true.
        watching =
            scope.launch {
                backup.state.collect { state ->
                    val known = (_screen.value as? Screen.Sync)?.folders ?: emptyList()
                    _screen.update<Screen.Sync> {
                        it.copy(
                            enabled = state.enabled,
                            selected = state.folders,
                            unmeteredOnly = state.unmeteredOnly,
                            whileCharging = state.whileCharging,
                            lastRunAt = state.lastRunAt,
                        )
                    }
                    if (state.enabled && known.isEmpty()) loadFolders()
                }
            }
    }

    /**
     * Turning it on is the moment the media permission means something, so that is when it is asked
     * for. An install that never turns sync on is never asked for anything.
     */
    fun toggleSync() {
        val state = _screen.value as? Screen.Sync ?: return
        if (!state.enabled) {
            _effects.value = Effect.AskForMediaAccess
            return
        }
        scope.launch {
            backup.setEnabled(false)
            backup.reschedule()
            _screen.update<Screen.Sync> { it.copy(enabled = false, folders = emptyList()) }
        }
    }

    /** The answer to the permission request. Refused means sync stays off and says so. */
    fun mediaAccess(granted: Boolean) {
        scope.launch {
            if (!granted) {
                _screen.update<Screen.Sync> {
                    it.copy(error = "Backup needs permission to read this phone's photographs.")
                }
                return@launch
            }
            backup.setEnabled(true)
            // Camera, and nothing else, until somebody says otherwise.
            val folders = backup.folders()
            val current = backup.state.first()
            if (current.folders.isEmpty()) {
                val camera = folders.filter { it.name == DeviceMedia.CAMERA }.map { it.id }.toSet()
                backup.setFolders(camera)
            }
            val settled = backup.state.first()
            backup.reschedule()
            backup.runNow()
            _screen.update<Screen.Sync> {
                it.copy(enabled = true, folders = folders, selected = settled.folders, error = null)
            }
        }
    }

    private suspend fun loadFolders() {
        val folders = backup.folders()
        _screen.update<Screen.Sync> { it.copy(folders = folders) }
    }

    fun toggleFolder(folderId: String) {
        val state = _screen.value as? Screen.Sync ?: return
        val next = state.selected.toMutableSet()
        if (!next.add(folderId)) next.remove(folderId)
        scope.launch {
            backup.setFolders(next)
            _screen.update<Screen.Sync> { it.copy(selected = next) }
        }
    }

    fun setUnmeteredOnly(value: Boolean) {
        scope.launch {
            backup.setUnmeteredOnly(value)
            backup.reschedule()
            _screen.update<Screen.Sync> { it.copy(unmeteredOnly = value) }
        }
    }

    fun setWhileCharging(value: Boolean) {
        scope.launch {
            backup.setWhileCharging(value)
            backup.reschedule()
            _screen.update<Screen.Sync> { it.copy(whileCharging = value) }
        }
    }

    fun syncNow() {
        backup.runNow()
    }

    /**
     * Offers every photograph in the chosen folders again, not only the new ones.
     *
     * A backup that failed before 0.5.1 stepped over the photographs it had not sent, and nothing
     * in the app could reach back for them. This is that reach. The server recognises what it
     * already holds by hash, so a second offer of the same photograph costs no bytes and no quota.
     */
    fun backUpEverythingAgain() {
        scope.launch {
            backup.forgetProgress()
            backup.runNow()
        }
    }

    // --- library ------------------------------------------------------------------------------

    /** The library, as a peer. */
    fun openLibrary() {
        peer(libraryScreen(pickingFor = null))
        refreshLibrary()
    }

    /**
     * The library, opened over an album to take items from it. It is the same screen doing a job,
     * so back returns to the album rather than leaving it.
     */
    fun addFromLibrary() {
        val albumId = (_screen.value as? Screen.Album)?.album.let { it?.id } ?: return
        push(libraryScreen(pickingFor = albumId))
        refreshLibrary()
    }

    /** The library as it was last read. Drawn at once; the fetch replaces it when it answers. */
    private fun libraryScreen(pickingFor: String?): Screen.Library {
        val last = held.library
        return Screen.Library(
            items = last?.items.orEmpty(),
            totalItems = last?.totalItems ?: 0,
            cursor = last?.cursor,
            albums = held.albums.orEmpty(),
            pickingFor = pickingFor,
        )
    }

    private fun refreshLibrary() {
        scope.launch {
            onLibrary { api ->
                val page = api.library(limit = LIBRARY_PAGE)
                held.library = LibraryHeld(page.items, page.totalItems, page.next)
                _screen.update<Screen.Library> {
                    it.copy(
                        items = page.items,
                        totalItems = page.totalItems,
                        cursor = page.next,
                        busy = false,
                        refreshing = false,
                    )
                }
            }
        }
    }

    /** The next page, asked for as the grid nears its end. The library is not one screenful. */
    fun loadMoreLibrary() {
        val state = _screen.value as? Screen.Library ?: return
        val cursor = state.cursor ?: return
        if (state.loadingMore) return
        _screen.update<Screen.Library> { it.copy(loadingMore = true) }
        scope.launch {
            withApi(
                onApiError = { message, retryable ->
                    _screen.update<Screen.Library> {
                        it.copy(loadingMore = false, error = message, retryable = retryable)
                    }
                },
            ) { api ->
                val page = api.library(after = cursor, limit = LIBRARY_PAGE)
                val current = _screen.value as? Screen.Library ?: return@withApi
                // The cursor moved while this was in flight, so this page is not the next one.
                if (current.cursor != cursor) return@withApi
                val items = current.items + page.items
                held.library = LibraryHeld(items, page.totalItems, page.next)
                _screen.update<Screen.Library> {
                    it.copy(items = items, cursor = page.next, loadingMore = false)
                }
            }
        }
    }

    fun toggleSelection(itemId: String) {
        _screen.update<Screen.Library> { state ->
            val next = state.selected.toMutableSet()
            if (!next.add(itemId)) next.remove(itemId)
            state.copy(selected = next, addingTo = null, error = null)
        }
    }

    /** The albums to add to. They are held, so the list is there before the server answers. */
    fun chooseAlbum() {
        _screen.update<Screen.Library> { it.copy(addingTo = true, albums = held.albums.orEmpty()) }
        if (held.albums != null) return
        scope.launch {
            withApi(onApiError = { _, _ -> }) { api ->
                val albums = api.albums()
                held.albums = albums
                _screen.update<Screen.Library> { it.copy(albums = albums) }
            }
        }
    }

    fun cancelAdd() {
        _screen.update<Screen.Library> { it.copy(addingTo = null) }
    }

    /** Selecting library media into an album. Nothing is copied and nothing costs quota. */
    fun addSelectionTo(albumId: String) {
        val state = _screen.value as? Screen.Library ?: return
        val pickingFor = state.pickingFor
        scope.launch {
            onLibrary { api ->
                api.addToAlbum(albumId, state.selected.toList())
                held.forget(albumId)
                held.albums = null
                _screen.update<Screen.Library> { it.copy(selected = emptySet(), addingTo = null, busy = false) }
                // Adding from an album returns to it; adding from the library peer opens it.
                if (pickingFor == albumId) back() else open(albumId)
            }
        }
    }

    /** The only deletion that removes bytes. An album only ever held a reference to these. */
    fun deleteSelection() {
        val state = _screen.value as? Screen.Library ?: return
        scope.launch {
            onLibrary { api ->
                state.selected.forEach { api.deleteFromLibrary(it) }
                held.opened.clear()
                _screen.update<Screen.Library> { it.copy(selected = emptySet(), busy = false) }
                refreshLibrary()
            }
        }
    }

    private suspend fun onLibrary(block: suspend (MantelApi) -> Unit) {
        _screen.update<Screen.Library> { it.copy(busy = true, error = null, retryable = false) }
        withApi(
            onApiError = { message, retryable ->
                _screen.update<Screen.Library> {
                    it.copy(busy = false, refreshing = false, error = message, retryable = retryable)
                }
            },
        ) { api -> block(api) }
    }

    fun openAlbum(albumId: String) {
        scope.launch { open(albumId) }
    }

    /**
     * An album you have opened before is on the screen before the server answers. One you have not
     * is drawn from what the album list already says about it: its title, and a tile for every item
     * it holds, in the right places, rather than an empty screen that fills in a moment.
     */
    private suspend fun open(albumId: String) {
        val known = held.opened[albumId]
        if (known != null) {
            push(Screen.Album(known.first, links = known.second))
            watch(albumId)
            refreshAlbum()
            return
        }
        val summary = held.albums?.firstOrNull { it.id == albumId }
        if (summary != null) push(Screen.Album(placeholder(summary))) else push(Screen.Album(placeholder(albumId)))
        watch(albumId)
        refreshAlbum()
    }

    /** Re-entering an album from the screen above it. */
    private fun resume(albumId: String) {
        watch(albumId)
        refreshAlbum()
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
                api.removeFromAlbum(album.id, itemId)
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

    // --- sharing ------------------------------------------------------------------------------

    fun openSharing() {
        _screen.update<Screen.Album> { it.copy(sharing = true, selected = null, error = null) }
    }

    fun closeSharing() {
        _screen.update<Screen.Album> { it.copy(sharing = false, pin = "", expiresInDays = null, error = null) }
    }

    fun setPin(pin: String) {
        _screen.update<Screen.Album> { it.copy(pin = pin.filter { c -> c.isDigit() }.take(12), error = null) }
    }

    fun setExpiry(days: Int?) {
        _screen.update<Screen.Album> { it.copy(expiresInDays = days, error = null) }
    }

    /** Creating the first live link is what publishes an album; there is no separate publish. */
    fun createShareLink() {
        val state = _screen.value as? Screen.Album ?: return
        val pin = state.pin.ifBlank { null }
        scope.launch {
            onAlbum { api, album ->
                api.createShareLink(album.id, pin, state.expiresInDays)
                val links = api.shareLinks(album.id)
                val fresh = api.album(album.id)
                hold(fresh, links)
                _screen.update<Screen.Album> {
                    it.copy(album = fresh, links = links, pin = "", expiresInDays = null, busy = false)
                }
            }
        }
    }

    /**
     * Revocation is immediate and total: the link 404s from the next request, and nothing about it
     * is recoverable (SDD.md 4.3). So the screen asks first.
     */
    fun revokeShareLink(shareLinkId: String) {
        scope.launch {
            onAlbum { api, album ->
                api.revokeShareLink(shareLinkId)
                val links = api.shareLinks(album.id)
                val fresh = api.album(album.id)
                hold(fresh, links)
                _screen.update<Screen.Album> { it.copy(album = fresh, links = links, busy = false) }
            }
        }
    }

    fun shareUrl(url: String) {
        _effects.value = Effect.ShareText(url)
    }

    // --- upload -------------------------------------------------------------------------------

    fun pickMedia() {
        _effects.value = Effect.PickMedia
    }

    fun upload(uris: List<Uri>) {
        val state = _screen.value as? Screen.Album ?: return
        if (uris.isEmpty()) return
        scope.launch {
            uploads.enqueue(state.album.id, uris)
            observeUploads(state.album.id)
        }
    }

    /**
     * The upload belongs to the worker; this only reads what it reports. The album is re-read when a
     * batch finishes, because the items it created are the server's news, not the worker's.
     */
    private fun observeUploads(albumId: String) {
        reporting?.cancel()
        reporting =
            scope.launch {
                uploads.reports(albumId).collect { report ->
                    when (report) {
                        is UploadReport.Running ->
                            _upload.value =
                                UploadStatus(
                                    filename = report.filename,
                                    doneBytes = report.doneBytes,
                                    totalBytes = report.totalBytes,
                                    index = report.index,
                                    count = report.count,
                                )
                        is UploadReport.Failed -> _upload.value = UploadStatus("", 0, 0, 0, 0, report.message)
                        UploadReport.Finished -> {
                            _upload.value = null
                            held.forget(albumId)
                            held.library = null
                            refreshAlbum()
                        }
                    }
                }
            }
    }

    private fun watchBackup() {
        backupWatch?.cancel()
        backupWatch =
            scope.launch {
                uploads.reports(null).collect { report ->
                    when (report) {
                        is UploadReport.Running ->
                            _backupStatus.value =
                                UploadStatus(
                                    filename = report.filename,
                                    doneBytes = report.doneBytes,
                                    totalBytes = report.totalBytes,
                                    index = report.index,
                                    count = report.count,
                                )
                        is UploadReport.Failed -> _backupStatus.value = UploadStatus("", 0, 0, 0, 0, report.message)
                        UploadReport.Finished -> {
                            _backupStatus.value = null
                            held.library = null
                            if (_screen.value is Screen.Library) refreshLibrary()
                        }
                    }
                }
            }
    }

    /** Runs the backup again. What failed is still on the phone, and the sweep will offer it again. */
    fun retryBackup() {
        _backupStatus.value = null
        backup.runNow()
    }

    /** The one control an offline screen needs. It re-runs whatever this screen reads. */
    fun retry() {
        when (_screen.value) {
            is Screen.Albums -> refreshAlbums()
            is Screen.Album -> refreshAlbum()
            is Screen.Library -> refreshLibrary()
            else -> Unit
        }
    }

    /** Pull to refresh. The same read, asked for deliberately, and said so on the screen. */
    fun refresh() {
        when (_screen.value) {
            is Screen.Albums -> {
                _screen.update<Screen.Albums> { it.copy(refreshing = true) }
                refreshAlbums()
            }
            is Screen.Library -> {
                _screen.update<Screen.Library> { it.copy(refreshing = true) }
                refreshLibrary()
            }
            else -> Unit
        }
    }

    fun refreshAlbum() {
        val state = _screen.value as? Screen.Album ?: return
        scope.launch { onAlbum { api, _ -> reload(api, state.album.id) { it } } }
    }

    /**
     * Asks again while anything is still uploading or rendering, and stops when nothing is.
     *
     * It asks for the album alone: share links do not change while an item renders, and they are
     * re-read after anything that changes them. It slows down after the first twenty seconds,
     * because a clip still transcoding by then will not be done in the next two. It waits while the
     * app is not in front of somebody, so a pocketed phone polls nothing.
     */
    private fun watch(albumId: String) {
        watching?.cancel()
        watching =
            scope.launch {
                val startedAt = 0L
                var elapsed = startedAt
                while (true) {
                    val wait = pollDelay(elapsed)
                    delay(wait)
                    elapsed += wait
                    resumed.first { it }
                    val state = _screen.value as? Screen.Album ?: return@launch
                    if (state.album.id != albumId) return@launch
                    if (!state.settling) continue
                    runCatching {
                        withApi(onApiError = { _, _ -> }) { api ->
                            val album = api.album(albumId)
                            hold(album, (_screen.value as? Screen.Album)?.links.orEmpty())
                            _screen.update<Screen.Album> { it.copy(album = album) }
                        }
                    }
                }
            }
    }

    private suspend fun reload(
        api: MantelApi,
        albumId: String,
        block: (Screen.Album) -> Screen.Album,
    ) {
        val album = api.album(albumId)
        val links = api.shareLinks(albumId)
        hold(album, links)
        _screen.update<Screen.Album> {
            block(it.copy(album = album, links = links, busy = false, error = null, retryable = false))
        }
    }

    private fun hold(
        album: AlbumView,
        links: List<ShareLinkView>,
    ) {
        held.opened[album.id] = album to links
    }

    // --- plumbing -----------------------------------------------------------------------------

    private suspend fun onAlbums(block: suspend (MantelApi, Me) -> Unit) {
        val state = _screen.value as? Screen.Albums ?: return
        _screen.update<Screen.Albums> { it.copy(busy = true, error = null, retryable = false) }
        withApi(
            onApiError = { message, retryable ->
                _screen.update<Screen.Albums> {
                    it.copy(busy = false, refreshing = false, error = message, retryable = retryable)
                }
            },
        ) { api -> block(api, state.me) }
    }

    private suspend fun onAlbum(block: suspend (MantelApi, AlbumView) -> Unit) {
        val state = _screen.value as? Screen.Album ?: return
        _screen.update<Screen.Album> { it.copy(busy = true, error = null, retryable = false) }
        withApi(
            onApiError = { message, retryable ->
                _screen.update<Screen.Album> { it.copy(busy = false, error = message, retryable = retryable) }
            },
        ) { api -> block(api, state.album) }
        _screen.update<Screen.Album> { it.copy(busy = false) }
    }

    private suspend fun withApi(
        onApiError: (String, Boolean) -> Unit,
        block: suspend (MantelApi) -> Unit,
    ) {
        val serverUrl = settings.serverUrl.first() ?: return
        val session = settings.session.first() ?: return
        try {
            api(serverUrl, session).use { block(it) }
        } catch (e: ApiException) {
            if (e.code == "unauthenticated") signOut() else onApiError(e.message, false)
        } catch (e: java.io.IOException) {
            // Offline, or the server is down. Either way the same call will work later, so the
            // screen offers it rather than making the person guess.
            onApiError("That server did not answer. You may be offline.", true)
        }
    }

    /** Runs a call against the server, and puts whatever it says wrong in front of the person. */
    private suspend fun attempt(
        serverUrl: String,
        block: suspend (MantelApi) -> Unit,
    ) {
        _screen.update<Screen.SignIn> { it.copy(busy = true, error = null) }
        try {
            api(serverUrl, null).use { block(it) }
        } catch (e: ApiException) {
            _screen.update<Screen.SignIn> { it.copy(busy = false, error = e.message) }
        } catch (e: java.io.IOException) {
            _screen.update<Screen.SignIn> { it.copy(busy = false, error = "That server did not answer") }
        }
    }

    private inline fun <reified T : Screen> MutableStateFlow<Screen>.update(block: (T) -> Screen) {
        val current = value
        if (current is T) value = block(current)
    }

    private companion object {
        /** Three columns of sixty is well past one screenful, and it is one request. */
        const val LIBRARY_PAGE = 60
    }
}

/**
 * How long to wait before asking about an album again.
 *
 * Two seconds while a render might plausibly be about to finish, ten after that. Over two minutes
 * that is twenty requests rather than sixty, and the difference is invisible on the screen.
 */
fun pollDelay(elapsedMillis: Long): Long = if (elapsedMillis < 20_000L) 2_000L else 10_000L

/** How many times [pollDelay] asks the server inside a window. The schedule, counted. */
fun pollRequestsIn(windowMillis: Long): Int {
    var elapsed = 0L
    var asked = 0
    while (true) {
        elapsed += pollDelay(elapsed)
        if (elapsed > windowMillis) return asked
        asked++
    }
}

/**
 * An album the app knows the shape of but has not read. The album list already carries its title and
 * how much is in it, so the screen says those rather than nothing while the read is in flight.
 */
private fun placeholder(summary: AlbumSummary): AlbumView =
    AlbumView(
        id = summary.id,
        title = summary.title,
        description = summary.description,
        status = summary.status,
        itemCount = summary.itemCount,
        totalBytes = summary.totalBytes,
        coverItemId = summary.coverItemId,
        createdAt = summary.createdAt,
        updatedAt = summary.updatedAt,
        items = emptyList(),
    )

/** An album reached without the list: created a moment ago, or opened from the library. */
private fun placeholder(albumId: String): AlbumView =
    AlbumView(
        id = albumId,
        title = "",
        status = "draft",
        itemCount = 0,
        totalBytes = 0,
        createdAt = "",
        updatedAt = "",
        items = emptyList(),
    )

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
