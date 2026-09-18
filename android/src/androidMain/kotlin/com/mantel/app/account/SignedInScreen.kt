package com.mantel.app.account

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mantel.app.AppModel
import com.mantel.app.Screen
import com.mantel.app.design.Body
import com.mantel.app.design.Button
import com.mantel.app.design.Card
import com.mantel.app.design.Page
import com.mantel.app.design.Title
import com.mantel.app.design.Tokens
import com.mantel.app.design.captionStyle
import kotlin.math.max

/**
 * The account, as the API reports it. M11 ends here: albums arrive at M12, and this screen exists
 * so that "signed in" is something the person can see rather than something the app claims.
 */
@Composable
fun SignedInScreen(
    state: Screen.SignedIn,
    model: AppModel,
) {
    Page {
        Title("Mantel")
        Spacer(Modifier.height(Tokens.Space.gutter))

        Card {
            Body(state.me.displayName ?: state.me.email)
            if (state.me.displayName != null) Body(state.me.email, style = captionStyle)
            Body(storageLine(state.me.storageUsedBytes, state.me.storageQuotaBytes), style = captionStyle)
        }

        Card {
            Body("Albums arrive in the next release.", style = captionStyle)
        }

        Button(
            text = if (state.busy) "Signing out…" else "Sign out",
            onClick = model::signOut,
            enabled = !state.busy,
            quiet = true,
        )
    }
}

/** Storage in the units a person reads, against the quota, because the number alone means nothing. */
fun storageLine(
    used: Long,
    quota: Long,
): String = "${gigabytes(used)} of ${gigabytes(quota)} used"

private fun gigabytes(bytes: Long): String {
    val gb = bytes.toDouble() / (1024 * 1024 * 1024)
    if (gb >= 1) return "${round(gb)} GB"
    val mb = bytes.toDouble() / (1024 * 1024)
    return "${round(max(mb, 0.0))} MB"
}

private fun round(value: Double): String {
    val rounded = Math.round(value * 10) / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}
