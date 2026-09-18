package com.mantel.app

import android.content.Context
import com.mantel.app.api.ApiException
import com.mantel.app.api.MantelApi
import com.mantel.app.api.Me
import com.mantel.app.api.SignInMethods
import com.mantel.app.auth.Pkce
import com.mantel.app.auth.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What the app is showing, and how it moves between those states.
 *
 * Three states, because there are three: the app does not know the server yet, it knows the server
 * and nobody is signed in, or somebody is.
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

    data class SignedIn(val me: Me, val busy: Boolean = false) : Screen
}

/** What the screen asks the outside world to do, which only the activity can do. */
sealed interface Effect {
    data class OpenBrowser(val url: String) : Effect
}

class AppModel(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val settings = Settings(context.applicationContext)

    private val _screen = MutableStateFlow<Screen>(Screen.Starting)
    val screen: StateFlow<Screen> = _screen

    private val _effects = MutableStateFlow<Effect?>(null)
    val effects: StateFlow<Effect?> = _effects

    fun effectHandled() {
        _effects.value = null
    }

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
                    _screen.value = Screen.SignedIn(me)
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
                _screen.value = Screen.SignedIn(api(serverUrl, session).use { it.me() })
            }
        }
    }

    fun signOut() {
        scope.launch {
            val serverUrl = settings.serverUrl.first() ?: return@launch
            val session = settings.session.first()
            _screen.update<Screen.SignedIn> { it.copy(busy = true) }
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
