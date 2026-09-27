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
import com.mantel.app.media.BackupLine
import com.mantel.app.media.DeviceMedia
import com.mantel.app.media.MediaFolder
import com.mantel.app.media.PhoneBackup
import com.mantel.app.media.UploadReport
import com.mantel.app.media.Uploads
import com.mantel.app.media.WorkManagerUploads
import com.mantel.app.media.backupLine
import com.mantel.app.timeline.DevicePhoneMedia
import com.mantel.app.timeline.PhoneMedia
import com.mantel.app.timeline.RollPhoto
import com.mantel.app.timeline.Tile
import com.mantel.app.timeline.Timeline
import com.mantel.app.timeline.buildTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The three sections at the foot of the app (DESIGN.md). Each is the bottom of the stack: choosing
 * one replaces whichever you were on, and a screen you visit from one pushes over it.
 */
enum class Section { PHOTOS, ALBUMS, SHARED }

/**
 * What the app is showing, and how it moves between those states.
 *
 * The screens are the shape of the product: sign in, the three sections — Photos, Albums and
 * Shared — and the screens you visit from them. There is no navigation library here; the stack is
 * a list in the model, because a handful of screens and one back gesture do not need routes.
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
        val albums: List<AlbumSummary> = emptyList(),
        /** Whether the field for a new album's title is open. */
        val creating: Boolean = false,
        val newTitle: String = "",
        val busy: Boolean = false,
        val refreshing: Boolean = false,
        val error: String? = null,
        val retryable: Boolean = false,
    ) : Screen

    /**
     * The timeline: the phone's photographs and the library as one, grouped by the day each was
     * taken. Where the app opens.
     */
    data class Photos(
        val library: List<ItemView> = emptyList(),
        val totalItems: Long = 0,
        /** Where the library's next page starts. Null once the timeline has all of it. */
        val cursor: String? = null,
        val loadingMore: Boolean = false,
        val roll: List<RollPhoto> = emptyList(),
        /** Whether the app may read the phone's photographs. Without it the timeline is the library. */
        val phoneAccess: Boolean = false,
        val timeline: Timeline = Timeline.EMPTY,
        /** How many photographs to a row: pinch moves between three. */
        val columns: Int = 4,
        /** Tile keys. A selection exists from the first long press until it is cleared or used. */
        val selected: Set<String> = emptySet(),
        val choosingAlbum: Boolean = false,
        val albums: List<AlbumSummary> = emptyList(),
        val newAlbumTitle: String = "",
        /** Something the last action wants said, such as what a delete left on the phone. */
        val note: String? = null,
        /** The tile open full screen, if one is. The grid stays beneath it, where it was. */
        val viewing: String? = null,
        /** The tile the viewer last showed, so the grid can bring it into view on the way back. */
        val returnTo: String? = null,
        val showingInfo: Boolean = false,
        /** The viewer's controls. A tap on the photograph hides them and shows them again. */
        val chrome: Boolean = true,
        val busy: Boolean = false,
        val refreshing: Boolean = false,
        val error: String? = null,
        val retryable: Boolean = false,
    ) : Screen {
        val selecting: Boolean get() = selected.isNotEmpty()
    }

    /** The library, opened over an album to choose what goes into it. */
    data class Library(
        /** The album this library was opened to add to. */
        val pickingFor: String,
        val items: List<ItemView> = emptyList(),
        val totalItems: Long = 0,
        val selected: Set<String> = emptySet(),
        /** Where the next page starts. Null once the library has all of it. */
        val cursor: String? = null,
        val loadingMore: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        val retryable: Boolean = false,
    ) : Screen

    /** Every live share link across every album: what each opens, and when it stops. */
    data class Shared(
        val links: List<SharedLink> = emptyList(),
        /** The link whose revoke is waiting for a yes. Revoking cannot be undone. */
        val revoking: String? = null,
        val busy: Boolean = false,
        val refreshing: Boolean = false,
        val error: String? = null,
        val retryable: Boolean = false,
    ) : Screen

    /** Behind the avatar: who is signed in, the space they use, backup, the trash, and leaving. */
    data class Account(val me: Me) : Screen

    /** Deleted media, kept for 30 days and restorable until the sweep removes it. */
    data class Trash(
        val items: List<ItemView> = emptyList(),
        val totalItems: Long = 0,
        val selected: Set<String> = emptySet(),
        /** Removing for good cannot be undone, so it waits for a second press. */
        val confirmingRemove: Boolean = false,
        val cursor: String? = null,
        val loadingMore: Boolean = false,
        val busy: Boolean = false,
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
        /** Files the backup left out as too large for the server. */
        val tooLarge: Set<String> = emptySet(),
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

/** A live link with the album it opens, because a link on its own says nothing about what it shows. */
data class SharedLink(
    val link: ShareLinkView,
    val albumId: String,
    val albumTitle: String,
)

/** The section a screen belongs to, when it is one of the three. A visited screen has none. */
val Screen.section: Section?
    get() =
        when (this) {
            // Full screen means full screen: the bar goes while a photograph is open.
            is Screen.Photos -> if (viewing == null) Section.PHOTOS else null
            is Screen.Albums -> Section.ALBUMS
            is Screen.Shared -> Section.SHARED
            else -> null
        }

/** A batch on its way to storage, as the worker last reported it. */
data class UploadStatus(
    val filename: String,
    val doneBytes: Long,
    val totalBytes: Long,
    val index: Int,
    val count: Int,
    val failed: String? = null,
    /** The server's code for the failure, when it refused: `quota_exceeded` is a full library. */
    val failedCode: String? = null,
)

/** What the screen asks the outside world to do, which only the activity can do. */
sealed interface Effect {
    data class OpenBrowser(val url: String) : Effect

    data object PickMedia : Effect

    /** Reading the device's media, which sync needs and the picker does not. */
    data object AskForMediaAccess : Effect

    /** The system share sheet. A link is shared through whatever the person already uses. */
    data class ShareText(val url: String) : Effect

    /**
     * Photographs through the share sheet: the phone's own files, and library photographs at
     * display size, which the activity fetches first because only it can write a file to share.
     */
    data class ShareMedia(val phone: List<String>, val remote: List<String>) : Effect
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
    var shared: List<SharedLink>? = null
    var roll: List<RollPhoto>? = null
    var timeline: Timeline? = null

    /** The density a person chose stays chosen across a screen change. */
    var columns: Int = 4
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
    private val phone: PhoneMedia,
    private val api: (String, String?) -> MantelApi,
    private val scope: CoroutineScope,
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        settings = StoredSettings(context.applicationContext),
        backup = PhoneBackup(context.applicationContext),
        uploads = WorkManagerUploads(context.applicationContext),
        phone = DevicePhoneMedia(context.applicationContext),
        api = { url, session -> MantelApi(url, session) },
        scope = scope,
    )

    /** What the media permission was asked for, because the answer means something different to each. */
    private enum class Access { BACKUP, TIMELINE }

    private var accessFor = Access.BACKUP

    private val _screen = MutableStateFlow<Screen>(Screen.Starting)
    val screen: StateFlow<Screen> = _screen

    private val _effects = MutableStateFlow<Effect?>(null)
    val effects: StateFlow<Effect?> = _effects

    private val _upload = MutableStateFlow<UploadStatus?>(null)
    val upload: StateFlow<UploadStatus?> = _upload

    /** What the phone's backup is doing, or what went wrong with it. */
    private val _backupStatus = MutableStateFlow<UploadStatus?>(null)
    val backupStatus: StateFlow<UploadStatus?> = _backupStatus

    /** The phone's photographs as last read, for the backup's line to count what it has not sent. */
    private val rollNow = MutableStateFlow<List<RollPhoto>>(emptyList())

    /** The line beside the avatar: the one thing true about the backup now. */
    private val _backupLine = MutableStateFlow<BackupLine?>(null)
    val backupLine: StateFlow<BackupLine?> = _backupLine

    private var lineWatch: Job? = null

    /** Whether a back gesture has somewhere to go. A section is the bottom of the stack. */
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
        // Back from the camera: the photograph just taken belongs on the timeline now, before any
        // backup has seen it.
        if (value) rereadRoll()
    }

    /** The phone's photographs, read again after the camera roll changed. */
    private fun rereadRoll() {
        if (!phone.hasAccess()) return
        scope.launch {
            val roll = phone.roll()
            held.roll = roll
            rollNow.value = roll
            if (_screen.value is Screen.Photos) {
                _screen.update<Screen.Photos> { it.copy(roll = roll, phoneAccess = true) }
                rebuildTimeline()
            }
        }
    }

    // --- navigation ---------------------------------------------------------------------------

    /** A screen you visit. Back returns to whatever you were on. */
    private fun push(screen: Screen) {
        stack.addLast(_screen.value)
        show(screen)
    }

    /** A section replaces whichever section you were on, and is the bottom of the stack. */
    private fun section(screen: Screen) {
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
            is Screen.Photos -> refreshPhotos()
            is Screen.Library -> refreshLibrary()
            is Screen.Shared -> refreshShared()
            is Screen.Trash -> refreshTrash()
            is Screen.Album -> resume(beneath.album.id)
            else -> Unit
        }
    }

    /** The navigation bar. */
    fun openSection(section: Section) {
        when (section) {
            Section.PHOTOS -> openPhotos()
            Section.ALBUMS -> openAlbums()
            Section.SHARED -> openShared()
        }
    }

    fun openAlbums() {
        section(Screen.Albums(albums = held.albums.orEmpty()))
        refreshAlbums()
    }

    /** Photos is where the app opens, and where it returns to after signing in. */
    private fun home() {
        root(photosScreen())
        refreshPhotos()
    }

    // --- account ------------------------------------------------------------------------------

    /** The avatar. Drawn from the account already held, then re-read for the storage figure. */
    fun openAccount() {
        val me = account ?: return
        push(Screen.Account(me))
        scope.launch {
            withApi(onApiError = { _, _ -> }) { api ->
                val fresh = api.me()
                account = fresh
                settings.setMe(fresh)
                _screen.update<Screen.Account> { it.copy(me = fresh) }
            }
        }
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
                    home()
                    return@launch
                } catch (e: java.io.IOException) {
                    // Offline is not signed out. The session is still good, so the app opens on
                    // the account it last saw and offers to ask again.
                    val cached = settings.me.first()
                    if (cached != null) {
                        account = cached
                        root(
                            photosScreen().copy(
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
                home()
            }
        }
    }

    fun signOut() {
        scope.launch {
            account = null
            backupWatch?.cancel()
            lineWatch?.cancel()
            _backupStatus.value = null
            _backupLine.value = null
            rollNow.value = emptyList()
            held.albums = null
            held.library = null
            held.shared = null
            held.roll = null
            held.timeline = null
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

    /** The plus in the title. The field opens at the top of the list; it is not a screen. */
    fun startNewAlbum() {
        _screen.update<Screen.Albums> { it.copy(creating = true, error = null) }
    }

    fun cancelNewAlbum() {
        _screen.update<Screen.Albums> { it.copy(creating = false, newTitle = "") }
    }

    fun setNewAlbumTitle(title: String) {
        _screen.update<Screen.Albums> { it.copy(newTitle = title, error = null) }
    }

    fun refreshAlbums() {
        scope.launch {
            onAlbums { api ->
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
            onAlbums { api ->
                val created = api.createAlbum(title)
                held.albums = null
                _screen.update<Screen.Albums> { it.copy(newTitle = "", creating = false, busy = false) }
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
                            tooLarge = state.tooLarge,
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
            accessFor = Access.BACKUP
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
        if (accessFor == Access.TIMELINE) {
            // Refused leaves the timeline as the library, with the offer still there.
            if (granted) refreshPhotos()
            return
        }
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

    // --- photos -------------------------------------------------------------------------------

    /** Photos: the camera roll and the library as one timeline. Where the app opens. */
    fun openPhotos() {
        section(photosScreen())
        refreshPhotos()
    }

    /** What Photos last showed, drawn at once; the reads replace it when they answer. */
    private fun photosScreen(): Screen.Photos {
        val last = held.library
        return Screen.Photos(
            library = last?.items.orEmpty(),
            totalItems = last?.totalItems ?: 0,
            cursor = last?.cursor,
            roll = held.roll.orEmpty(),
            phoneAccess = phone.hasAccess(),
            timeline = held.timeline ?: Timeline.EMPTY,
            columns = held.columns,
        )
    }

    /**
     * The phone's photographs and the library's first page, read side by side, then merged. The
     * phone answers at once and the library over the network, so the phone's are shown the moment
     * they are read rather than when the server has caught up.
     */
    private fun refreshPhotos() {
        scope.launch {
            val access = phone.hasAccess()
            if (access) {
                val roll = phone.roll()
                held.roll = roll
                rollNow.value = roll
                _screen.update<Screen.Photos> { it.copy(roll = roll, phoneAccess = true) }
                rebuildTimeline()
                if (roll.any { it.hash == null }) phone.hashWhenCharging()
            }
            onPhotos { api ->
                val page = api.library(limit = LIBRARY_PAGE)
                held.library = LibraryHeld(page.items, page.totalItems, page.next)
                _screen.update<Screen.Photos> {
                    it.copy(
                        library = page.items,
                        totalItems = page.totalItems,
                        cursor = page.next,
                        busy = false,
                        refreshing = false,
                    )
                }
                rebuildTimeline()
            }
        }
    }

    /** The next page of the library, asked for as the timeline nears the end of what it has. */
    fun loadMorePhotos() {
        val state = _screen.value as? Screen.Photos ?: return
        val cursor = state.cursor ?: return
        if (state.loadingMore) return
        _screen.update<Screen.Photos> { it.copy(loadingMore = true) }
        scope.launch {
            withApi(
                onApiError = { message, retryable ->
                    _screen.update<Screen.Photos> { it.copy(loadingMore = false, error = message, retryable = retryable) }
                },
            ) { api ->
                val page = api.library(after = cursor, limit = LIBRARY_PAGE)
                val current = _screen.value as? Screen.Photos ?: return@withApi
                // The cursor moved while this was in flight, so this page is not the next one.
                if (current.cursor != cursor) return@withApi
                val items = current.library + page.items
                held.library = LibraryHeld(items, page.totalItems, page.next)
                _screen.update<Screen.Photos> { it.copy(library = items, cursor = page.next, loadingMore = false) }
                rebuildTimeline()
            }
        }
    }

    /** The merge, off the main thread: ten thousand photographs are a few milliseconds, not a frame. */
    private suspend fun rebuildTimeline() {
        val state = _screen.value as? Screen.Photos ?: return
        val timeline =
            withContext(Dispatchers.Default) {
                buildTimeline(state.roll, state.library, libraryComplete = state.cursor == null)
            }
        held.timeline = timeline
        _screen.update<Screen.Photos> { current ->
            // A selection keeps only what is still on the timeline.
            val keys = timeline.tiles.mapTo(HashSet()) { it.key }
            // A photograph deleted from the viewer is gone from the timeline; the viewer moves on to
            // the one that took its place rather than closing on nothing.
            val viewing =
                current.viewing?.let { key ->
                    if (key in keys) {
                        key
                    } else {
                        val at = current.timeline.tiles.indexOfFirst { it.key == key }.coerceAtLeast(0)
                        timeline.tiles.getOrNull(at.coerceAtMost(timeline.tiles.lastIndex))?.key
                    }
                }
            current.copy(timeline = timeline, selected = current.selected intersect keys, viewing = viewing)
        }
    }

    /**
     * The phone's own photographs need the media permission. Photos asks for it only when the
     * person says to, from the line that offers it; an install that never does is never asked.
     */
    fun showPhonePhotos() {
        accessFor = Access.TIMELINE
        _effects.value = Effect.AskForMediaAccess
    }

    /** Pinch: three densities, from a few large photographs to a month on one screen. */
    fun zoom(closer: Boolean) {
        val columns = held.columns
        val next = if (closer) DENSITIES.lastOrNull { it < columns } else DENSITIES.firstOrNull { it > columns }
        next ?: return
        held.columns = next
        _screen.update<Screen.Photos> { it.copy(columns = next) }
    }

    // --- the viewer ---------------------------------------------------------------------------

    /** A tap on a photograph, when nothing is selected, opens it full screen. */
    fun openViewer(key: String) {
        _screen.update<Screen.Photos> { it.copy(viewing = key, showingInfo = false, chrome = true, note = null) }
    }

    fun closeViewer() {
        _screen.update<Screen.Photos> { it.copy(viewing = null, returnTo = it.viewing, showingInfo = false) }
    }

    /** A swipe settled on another photograph. Near the end of what is loaded, the next page is asked for. */
    fun viewerMoved(key: String) {
        val state = _screen.value as? Screen.Photos ?: return
        _screen.update<Screen.Photos> { it.copy(viewing = key, showingInfo = false) }
        val index = state.timeline.tiles.indexOfFirst { it.key == key }
        if (index >= state.timeline.tiles.size - VIEWER_LOOKAHEAD) loadMorePhotos()
    }

    fun toggleChrome() {
        _screen.update<Screen.Photos> { it.copy(chrome = !it.chrome) }
    }

    /** When it was taken, how large it is, and whether it lives on the phone, in the library, or both. */
    fun toggleInfo() {
        _screen.update<Screen.Photos> { it.copy(showingInfo = !it.showingInfo, chrome = true) }
    }

    private fun viewed(state: Screen.Photos): Tile? = state.timeline.tiles.firstOrNull { it.key == state.viewing }

    fun shareViewed() {
        val state = _screen.value as? Screen.Photos ?: return
        val tile = viewed(state) ?: return
        _effects.value =
            Effect.ShareMedia(
                phone = listOfNotNull(tile.phone?.uri),
                remote = if (tile.phone == null) listOfNotNull(tile.item?.displayUrl ?: tile.item?.thumbUrl) else emptyList(),
            )
    }

    /** Add to album from the viewer is a selection of one, and the grid's album choice takes it from there. */
    fun addViewedToAlbum() {
        val state = _screen.value as? Screen.Photos ?: return
        val tile = viewed(state) ?: return
        _screen.update<Screen.Photos> {
            it.copy(viewing = null, returnTo = tile.key, showingInfo = false, selected = setOf(tile.key))
        }
        chooseAlbumForSelection()
    }

    /**
     * To the trash, the library copy only. A photograph only on the phone stays, because Mantel never
     * deletes from the phone; one on both keeps its phone copy on the timeline.
     */
    fun deleteViewed() {
        val state = _screen.value as? Screen.Photos ?: return
        val tile = viewed(state) ?: return
        val itemId = tile.item?.id
        if (itemId == null) {
            _screen.update<Screen.Photos> {
                it.copy(note = "This photograph is only on this phone, so it stays. Mantel never deletes from the phone.")
            }
            return
        }
        scope.launch {
            onPhotos { api ->
                api.moveToTrash(itemId)
                forgetMedia()
                _screen.update<Screen.Photos> { it.copy(busy = false, note = "Moved to the trash. It is kept for 30 days.") }
                refreshPhotos()
            }
        }
    }

    // --- selection ----------------------------------------------------------------------------

    /** A long press starts a selection with that photograph in it. */
    fun startSelection(key: String) {
        _screen.update<Screen.Photos> { it.copy(selected = it.selected + key, note = null) }
    }

    /** A tap while selecting adds or removes one photograph. */
    fun toggleTile(key: String) {
        _screen.update<Screen.Photos> { state ->
            state.copy(selected = if (key in state.selected) state.selected - key else state.selected + key, note = null)
        }
    }

    /** A drag after the long press extends the selection over everything it passes. */
    fun selectRange(keys: Collection<String>) {
        _screen.update<Screen.Photos> { it.copy(selected = it.selected + keys) }
    }

    /** A day's heading selects the whole day, or clears it if the day is already selected. */
    fun toggleDay(date: java.time.LocalDate) {
        _screen.update<Screen.Photos> { state ->
            val keys = state.timeline.days.firstOrNull { it.date == date }?.tiles?.map { it.key }.orEmpty()
            val all = keys.isNotEmpty() && state.selected.containsAll(keys)
            state.copy(selected = if (all) state.selected - keys.toSet() else state.selected + keys, note = null)
        }
    }

    fun clearSelection() {
        _screen.update<Screen.Photos> { it.copy(selected = emptySet(), choosingAlbum = false, newAlbumTitle = "") }
    }

    private fun selectedTiles(state: Screen.Photos): List<Tile> = state.timeline.tiles.filter { it.key in state.selected }

    /**
     * Through the system share sheet. A photograph on the phone is shared from the phone; one only in
     * the library is fetched at display size first, which the activity does.
     */
    fun shareSelection() {
        val state = _screen.value as? Screen.Photos ?: return
        val tiles = selectedTiles(state)
        _effects.value =
            Effect.ShareMedia(
                phone = tiles.mapNotNull { it.phone?.uri },
                remote = tiles.filter { it.phone == null }.mapNotNull { it.item?.displayUrl ?: it.item?.thumbUrl },
            )
    }

    /** The albums to add to, held so the list is there before the server answers. */
    fun chooseAlbumForSelection() {
        _screen.update<Screen.Photos> { it.copy(choosingAlbum = true, albums = held.albums.orEmpty()) }
        scope.launch {
            withApi(onApiError = { _, _ -> }) { api ->
                val albums = api.albums()
                held.albums = albums
                _screen.update<Screen.Photos> { it.copy(albums = albums) }
            }
        }
    }

    fun cancelChooseAlbum() {
        _screen.update<Screen.Photos> { it.copy(choosingAlbum = false, newAlbumTitle = "") }
    }

    fun setSelectionAlbumTitle(title: String) {
        _screen.update<Screen.Photos> { it.copy(newAlbumTitle = title) }
    }

    /** An album is made from a selection: a title, then everything selected goes into it. */
    fun newAlbumFromSelection() {
        val state = _screen.value as? Screen.Photos ?: return
        val title = state.newAlbumTitle.trim()
        if (title.isEmpty()) return
        scope.launch {
            onPhotos { api ->
                val album = api.createAlbum(title)
                addTiles(api, album.id, selectedTiles(state))
            }
        }
    }

    fun addSelectionToAlbum(albumId: String) {
        val state = _screen.value as? Screen.Photos ?: return
        scope.launch { onPhotos { api -> addTiles(api, albumId, selectedTiles(state)) } }
    }

    /**
     * What the library holds joins the album as it is. What is only on the phone is uploaded into
     * it, which is the same upload a picked photograph gets.
     */
    private suspend fun addTiles(
        api: MantelApi,
        albumId: String,
        tiles: List<Tile>,
    ) {
        val inLibrary = tiles.mapNotNull { it.item?.id }
        if (inLibrary.isNotEmpty()) api.addToAlbum(albumId, inLibrary)
        val onlyOnPhone = tiles.filter { it.item == null }.mapNotNull { it.phone?.uri }
        if (onlyOnPhone.isNotEmpty()) uploads.enqueue(albumId, onlyOnPhone.map(Uri::parse))
        held.forget(albumId)
        held.albums = null
        _screen.update<Screen.Photos> {
            it.copy(selected = emptySet(), choosingAlbum = false, newAlbumTitle = "", busy = false)
        }
        open(albumId)
    }

    /**
     * To the trash, where the server keeps them for 30 days. Only a library copy can go: Mantel never
     * deletes from the phone, so a photograph only on the phone stays where it is, and the screen
     * says so.
     */
    fun deleteSelection() {
        val state = _screen.value as? Screen.Photos ?: return
        val tiles = selectedTiles(state)
        val inLibrary = tiles.mapNotNull { it.item?.id }
        val onlyOnPhone = tiles.count { it.item == null }
        scope.launch {
            onPhotos { api ->
                inLibrary.forEach { api.moveToTrash(it) }
                forgetMedia()
                val note =
                    when (onlyOnPhone) {
                        0 -> null
                        1 -> "One photograph is only on this phone, so it stays. Mantel never deletes from the phone."
                        else -> "$onlyOnPhone photographs are only on this phone, so they stay. Mantel never deletes from the phone."
                    }
                _screen.update<Screen.Photos> { it.copy(selected = emptySet(), busy = false, note = note) }
                refreshPhotos()
            }
        }
    }

    private suspend fun onPhotos(block: suspend (MantelApi) -> Unit) {
        _screen.update<Screen.Photos> { it.copy(busy = true, error = null, retryable = false) }
        withApi(
            onApiError = { message, retryable ->
                _screen.update<Screen.Photos> {
                    it.copy(busy = false, refreshing = false, error = message, retryable = retryable)
                }
            },
        ) { api -> block(api) }
    }

    // --- library, over an album ---------------------------------------------------------------

    /**
     * The library, opened over an album to take items from it. It is a job, not a section, so back
     * returns to the album rather than leaving it.
     */
    fun addFromLibrary() {
        val albumId = (_screen.value as? Screen.Album)?.album.let { it?.id } ?: return
        val last = held.library
        push(
            Screen.Library(
                items = last?.items.orEmpty(),
                totalItems = last?.totalItems ?: 0,
                cursor = last?.cursor,
                pickingFor = albumId,
            ),
        )
        refreshLibrary()
    }

    private fun refreshLibrary() {
        scope.launch {
            onLibrary { api ->
                val page = api.library(limit = LIBRARY_PAGE)
                held.library = LibraryHeld(page.items, page.totalItems, page.next)
                _screen.update<Screen.Library> {
                    it.copy(items = page.items, totalItems = page.totalItems, cursor = page.next, busy = false)
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
                    _screen.update<Screen.Library> { it.copy(loadingMore = false, error = message, retryable = retryable) }
                },
            ) { api ->
                val page = api.library(after = cursor, limit = LIBRARY_PAGE)
                val current = _screen.value as? Screen.Library ?: return@withApi
                // The cursor moved while this was in flight, so this page is not the next one.
                if (current.cursor != cursor) return@withApi
                val items = current.items + page.items
                held.library = LibraryHeld(items, page.totalItems, page.next)
                _screen.update<Screen.Library> { it.copy(items = items, cursor = page.next, loadingMore = false) }
            }
        }
    }

    fun toggleSelection(itemId: String) {
        _screen.update<Screen.Library> { state ->
            val next = state.selected.toMutableSet()
            if (!next.add(itemId)) next.remove(itemId)
            state.copy(selected = next, error = null)
        }
    }

    /** Selecting library media into the album it was opened from. Nothing is copied and nothing costs quota. */
    fun addSelectionToPickingAlbum() {
        val state = _screen.value as? Screen.Library ?: return
        val albumId = state.pickingFor
        scope.launch {
            onLibrary { api ->
                api.addToAlbum(albumId, state.selected.toList())
                held.forget(albumId)
                held.albums = null
                _screen.update<Screen.Library> { it.copy(selected = emptySet(), busy = false) }
                back()
            }
        }
    }

    private suspend fun onLibrary(block: suspend (MantelApi) -> Unit) {
        _screen.update<Screen.Library> { it.copy(busy = true, error = null, retryable = false) }
        withApi(
            onApiError = { message, retryable ->
                _screen.update<Screen.Library> { it.copy(busy = false, error = message, retryable = retryable) }
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

    // --- shared -------------------------------------------------------------------------------

    fun openShared() {
        section(Screen.Shared(links = held.shared.orEmpty()))
        refreshShared()
    }

    /**
     * Every live link, read album by album. The API lists links per album, and every album is asked
     * at once, so the screen costs one round trip more than the album list and needs nothing from the
     * server that another client does not already have.
     */
    private fun refreshShared() {
        scope.launch {
            onShared { api ->
                val albums = api.albums()
                held.albums = albums
                val links =
                    coroutineScope {
                        albums.map { album -> async { album to api.shareLinks(album.id) } }.awaitAll()
                    }.flatMap { (album, links) ->
                        links.filter { it.live }.map { SharedLink(it, album.id, album.title) }
                    }.sortedByDescending { it.link.createdAt }
                held.shared = links
                _screen.update<Screen.Shared> { it.copy(links = links, busy = false, refreshing = false) }
            }
        }
    }

    /** Revoking is immediate and total (SDD.md 4.3), so the first press only asks. */
    fun askRevoke(shareLinkId: String) {
        _screen.update<Screen.Shared> { it.copy(revoking = shareLinkId, error = null) }
    }

    fun cancelRevoke() {
        _screen.update<Screen.Shared> { it.copy(revoking = null) }
    }

    fun confirmRevoke() {
        val id = (_screen.value as? Screen.Shared)?.revoking ?: return
        scope.launch {
            onShared { api ->
                api.revokeShareLink(id)
                held.opened.clear()
                val links = held.shared.orEmpty().filterNot { it.link.id == id }
                held.shared = links
                _screen.update<Screen.Shared> { it.copy(links = links, revoking = null, busy = false) }
            }
        }
    }

    private suspend fun onShared(block: suspend (MantelApi) -> Unit) {
        _screen.update<Screen.Shared> { it.copy(busy = true, error = null, retryable = false) }
        withApi(
            onApiError = { message, retryable ->
                _screen.update<Screen.Shared> {
                    it.copy(busy = false, refreshing = false, error = message, retryable = retryable)
                }
            },
        ) { api -> block(api) }
    }

    // --- trash --------------------------------------------------------------------------------

    fun openTrash() {
        push(Screen.Trash())
        refreshTrash()
    }

    private fun refreshTrash() {
        scope.launch { onTrash { api -> reloadTrash(api) } }
    }

    private suspend fun reloadTrash(api: MantelApi) {
        val page = api.trash(limit = LIBRARY_PAGE)
        val ids = page.items.map { it.id }.toSet()
        _screen.update<Screen.Trash> {
            it.copy(
                items = page.items,
                totalItems = page.totalItems,
                cursor = page.next,
                selected = it.selected intersect ids,
                confirmingRemove = false,
                busy = false,
            )
        }
    }

    fun loadMoreTrash() {
        val state = _screen.value as? Screen.Trash ?: return
        val cursor = state.cursor ?: return
        if (state.loadingMore) return
        _screen.update<Screen.Trash> { it.copy(loadingMore = true) }
        scope.launch {
            withApi(
                onApiError = { message, retryable ->
                    _screen.update<Screen.Trash> { it.copy(loadingMore = false, error = message, retryable = retryable) }
                },
            ) { api ->
                val page = api.trash(after = cursor, limit = LIBRARY_PAGE)
                val current = _screen.value as? Screen.Trash ?: return@withApi
                if (current.cursor != cursor) return@withApi
                _screen.update<Screen.Trash> {
                    it.copy(items = current.items + page.items, cursor = page.next, loadingMore = false)
                }
            }
        }
    }

    fun toggleTrashSelection(itemId: String) {
        _screen.update<Screen.Trash> { state ->
            val next = state.selected.toMutableSet()
            if (!next.add(itemId)) next.remove(itemId)
            state.copy(selected = next, confirmingRemove = false, error = null)
        }
    }

    /** Back into the library, and into every album each one was in. */
    fun restoreSelection() {
        val state = _screen.value as? Screen.Trash ?: return
        scope.launch {
            onTrash { api ->
                state.selected.forEach { api.restore(it) }
                forgetMedia()
                _screen.update<Screen.Trash> { it.copy(selected = emptySet()) }
                reloadTrash(api)
            }
        }
    }

    fun askRemoveForGood() {
        _screen.update<Screen.Trash> { it.copy(confirmingRemove = true) }
    }

    fun cancelRemove() {
        _screen.update<Screen.Trash> { it.copy(confirmingRemove = false) }
    }

    /** Gone now rather than in 30 days: the bytes, and the space they held. The phone keeps its copy. */
    fun removeSelectionForGood() {
        val state = _screen.value as? Screen.Trash ?: return
        if (!state.confirmingRemove) return
        scope.launch {
            onTrash { api ->
                state.selected.forEach { api.removeFromTrash(it) }
                _screen.update<Screen.Trash> { it.copy(selected = emptySet()) }
                reloadTrash(api)
            }
        }
    }

    private suspend fun onTrash(block: suspend (MantelApi) -> Unit) {
        _screen.update<Screen.Trash> { it.copy(busy = true, error = null, retryable = false) }
        withApi(
            onApiError = { message, retryable ->
                _screen.update<Screen.Trash> { it.copy(busy = false, error = message, retryable = retryable) }
            },
        ) { api -> block(api) }
    }

    /** A change to what the library holds changes the library, its albums and their counts. */
    private fun forgetMedia() {
        held.library = null
        held.albums = null
        held.opened.clear()
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
                        is UploadReport.Finished -> {
                            // The rest landed; the ones too large to send are named, not dropped.
                            _upload.value =
                                report.tooLarge.takeIf { it.isNotEmpty() }?.let { names ->
                                    UploadStatus("", 0, 0, 0, 0, tooLargeNote(names))
                                }
                            held.forget(albumId)
                            held.library = null
                            refreshAlbum()
                        }
                    }
                }
            }
    }

    private fun watchBackup() {
        // Existing installs armed nothing new when they updated; this arms the trigger for a new
        // photograph, and re-reads the schedule, once per session.
        scope.launch { backup.reschedule() }
        lineWatch?.cancel()
        lineWatch =
            scope.launch {
                // A photograph taken while the app is open appears at once, and the line counts it.
                launch { phone.changes().debounce(ROLL_SETTLES_MS).collect { rereadRoll() } }
                combine(backup.state, backup.conditions, _backupStatus, rollNow) { state, conditions, upload, roll ->
                    backupLine(state, conditions, upload, waiting = roll.count { it.added > state.watermark })
                }.collect { _backupLine.value = it }
            }
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
                        is UploadReport.Failed ->
                            _backupStatus.value = UploadStatus("", 0, 0, 0, 0, report.message, report.code)
                        is UploadReport.Finished -> {
                            _backupStatus.value = null
                            held.library = null
                            if (_screen.value is Screen.Photos) refreshPhotos()
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
            is Screen.Photos -> refreshPhotos()
            is Screen.Library -> refreshLibrary()
            is Screen.Shared -> refreshShared()
            is Screen.Trash -> refreshTrash()
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
            is Screen.Photos -> {
                _screen.update<Screen.Photos> { it.copy(refreshing = true) }
                refreshPhotos()
            }
            is Screen.Shared -> {
                _screen.update<Screen.Shared> { it.copy(refreshing = true) }
                refreshShared()
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

    private suspend fun onAlbums(block: suspend (MantelApi) -> Unit) {
        if (_screen.value !is Screen.Albums) return
        _screen.update<Screen.Albums> { it.copy(busy = true, error = null, retryable = false) }
        withApi(
            onApiError = { message, retryable ->
                _screen.update<Screen.Albums> {
                    it.copy(busy = false, refreshing = false, error = message, retryable = retryable)
                }
            },
        ) { api -> block(api) }
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
        /** Well past one screenful at the densest grid, and it is one request. */
        const val LIBRARY_PAGE = 120

        /** Photographs to a row, from close to far. */
        val DENSITIES = listOf(3, 4, 6)

        /** How close to the end of what is loaded a swipe in the viewer asks for the next page. */
        const val VIEWER_LOOKAHEAD = 10

        /** A photograph arrives as several MediaStore changes in a burst; the roll is read once after them. */
        const val ROLL_SETTLES_MS = 500L
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

/** What an upload that left files out says about them. */
private fun tooLargeNote(names: List<String>): String =
    if (names.size == 1) {
        "${names.single()} is larger than your server accepts and was not uploaded."
    } else {
        "${names.size} files are larger than your server accepts and were not uploaded: ${names.joinToString(", ")}."
    }
