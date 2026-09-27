package com.mantel.app

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.mantel.app.account.AccountScreen
import com.mantel.app.album.AlbumScreen
import com.mantel.app.album.AlbumsScreen
import com.mantel.app.auth.SignInScreen
import com.mantel.app.design.NavBar
import com.mantel.app.design.Page
import com.mantel.app.design.Title
import com.mantel.app.design.Tokens
import com.mantel.app.library.LibraryScreen
import com.mantel.app.library.TrashScreen
import com.mantel.app.media.DeviceMedia
import com.mantel.app.media.SyncScreen
import com.mantel.app.share.SharedScreen
import com.mantel.app.timeline.PhotosScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One activity. Sign-in leaves the app for a browser and comes back through the deep link, and
 * `singleTask` in the manifest is what makes "comes back" mean this instance rather than a new one.
 */
class MainActivity : ComponentActivity() {
    private lateinit var model: AppModel

    /**
     * The photo picker, not a storage permission. It hands back the files the person chose and
     * nothing else, so the app never asks to read the whole photo library.
     */
    private val picker =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris: List<Uri> ->
            model.upload(uris)
        }

    private val notifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Reading the device's media. The photo picker needs none of this, so an install that never
     * turns backup on is never asked.
     */
    private val mediaAccess =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            model.mediaAccess(granted.values.any { it })
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The album is the page; the system bars sit over it and the screens keep clear of them.
        enableEdgeToEdge()
        model = AppModel(this, lifecycleScope)

        setContent {
            val screen by model.screen.collectAsState()
            val upload by model.upload.collectAsState()
            val effect by model.effects.collectAsState()

            when (val pending = effect) {
                is Effect.OpenBrowser -> {
                    openInBrowser(pending.url)
                    model.effectHandled()
                }
                is Effect.PickMedia -> {
                    askForNotifications()
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    model.effectHandled()
                }
                is Effect.AskForMediaAccess -> {
                    mediaAccess.launch(DeviceMedia.permissions().toTypedArray())
                    model.effectHandled()
                }
                is Effect.ShareText -> {
                    share(pending.url)
                    model.effectHandled()
                }
                is Effect.ShareMedia -> {
                    lifecycleScope.launch { shareMedia(pending) }
                    model.effectHandled()
                }
                null -> Unit
            }

            // The navigation bar belongs to the three sections and to nothing visited from them.
            val section = screen.section
            Column(Modifier.fillMaxSize().background(Tokens.Colour.surface)) {
                Box(
                    Modifier
                        .weight(1f)
                        // The bar keeps clear of the system's own; the screen above it need not.
                        .then(if (section != null) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier),
                ) {
                    // One screen replaces another, and says only that: a crossfade, no slide,
                    // because these screens have no arrangement in space to imply (DESIGN.md).
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            val spec = tween<Float>(Tokens.Motion.medium, easing = Tokens.Motion.ease)
                            fadeIn(spec) togetherWith fadeOut(spec)
                        },
                        contentKey = { it.key() },
                        label = "screen",
                    ) { current ->
                        when (current) {
                            is Screen.Starting -> Page { Title("Mantel") }
                            is Screen.SignIn -> SignInScreen(current, model)
                            is Screen.Albums -> AlbumsScreen(current, model)
                            is Screen.Photos -> PhotosScreen(current, model)
                            is Screen.Library -> LibraryScreen(current, model)
                            is Screen.Shared -> SharedScreen(current, model)
                            is Screen.Account -> AccountScreen(current, model)
                            is Screen.Trash -> TrashScreen(current, model)
                            is Screen.Sync -> SyncScreen(current, model)
                            is Screen.Album -> AlbumScreen(current, upload, model)
                        }
                    }
                }
                if (section != null) NavBar(section, model::openSection)
            }
        }

        model.start()
        handle(intent)
    }

    /** The album poll asks nothing while the app is not in front of somebody. */
    override fun onResume() {
        super.onResume()
        model.resumed(true)
    }

    override fun onPause() {
        super.onPause()
        model.resumed(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "mantel" || data.host != "auth") return
        val code = data.getQueryParameter("code") ?: return
        model.completeSignIn(code)
    }

    /**
     * The system share sheet. A link is sent through whatever the person already uses to send
     * things; Mantel has no opinion about it and no way to know which one was chosen.
     */
    private fun share(url: String) {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
        startActivity(Intent.createChooser(send, null))
    }

    /**
     * Photographs through the share sheet. A photograph on the phone is handed over as it is; one
     * only in the library is fetched at display size into a scratch folder first, because another app can
     * only be given a file, not a signed URL that expires in an hour.
     */
    private suspend fun shareMedia(media: Effect.ShareMedia) {
        val fetched =
            withContext(Dispatchers.IO) {
                val folder =
                    java.io.File(cacheDir, "shared").apply {
                        deleteRecursively()
                        mkdirs()
                    }
                media.remote.mapIndexedNotNull { index, url ->
                    runCatching {
                        val file = java.io.File(folder, "mantel-${index + 1}.webp")
                        java.net.URL(url).openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
                        FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                    }.getOrNull()
                }
            }
        val uris = ArrayList(media.phone.map(Uri::parse) + fetched)
        if (uris.isEmpty()) return
        val send =
            if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        send.type = "*/*"
        send.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, null))
    }

    /**
     * The upload runs in the foreground and shows its progress there, which on Android 13 and later
     * needs permission. It is asked for when the first upload starts, not on first launch, because
     * that is the moment it means something.
     */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * A custom tab, not a WebView: the sign-in page is the server's, the person can see the address
     * it is really at, and GitHub refuses an embedded browser for good reasons.
     */
    private fun openInBrowser(url: String) {
        CustomTabsIntent.Builder()
            .setUrlBarHidingEnabled(false)
            .setShowTitle(true)
            .setDefaultColorSchemeParams(
                CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(getColor(R.color.colour_surface))
                    .build(),
            )
            .build()
            .launchUrl(this, Uri.parse(url))
    }

    private companion object {
        /** The server takes two hundred files in one batch; the picker should not offer more. */
        const val MAX_PICK = 200
    }
}

/**
 * What counts as the same screen for a transition. A screen that only changed its contents — a
 * refresh landing, a selection — must not cross-fade with itself.
 */
private fun Screen.key(): String =
    when (this) {
        is Screen.Starting -> "starting"
        is Screen.SignIn -> "sign-in"
        is Screen.Albums -> "albums"
        is Screen.Photos -> "photos"
        is Screen.Library -> "library"
        is Screen.Shared -> "shared"
        is Screen.Account -> "account"
        is Screen.Trash -> "trash"
        is Screen.Sync -> "sync"
        is Screen.Album -> "album:${album.id}"
    }
