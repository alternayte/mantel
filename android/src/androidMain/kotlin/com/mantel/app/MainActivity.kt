package com.mantel.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.mantel.app.account.SignedInScreen
import com.mantel.app.auth.SignInScreen
import com.mantel.app.design.Page
import com.mantel.app.design.Title

/**
 * One activity. Sign-in leaves the app for a browser and comes back through the deep link, and
 * `singleTask` in the manifest is what makes "comes back" mean this instance rather than a new one.
 */
class MainActivity : ComponentActivity() {
    private lateinit var model: AppModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = AppModel(this, lifecycleScope)

        setContent {
            val screen by model.screen.collectAsState()
            val effect by model.effects.collectAsState()

            if (effect is Effect.OpenBrowser) {
                openInBrowser((effect as Effect.OpenBrowser).url)
                model.effectHandled()
            }

            when (val current = screen) {
                is Screen.Starting -> Page { Title("Mantel") }
                is Screen.SignIn -> SignInScreen(current, model)
                is Screen.SignedIn -> SignedInScreen(current, model)
            }
        }

        model.start()
        handle(intent)
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
}
