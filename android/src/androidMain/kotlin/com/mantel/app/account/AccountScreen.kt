package com.mantel.app.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.mantel.app.AppModel
import com.mantel.app.Screen
import com.mantel.app.design.Body
import com.mantel.app.design.Hairline
import com.mantel.app.design.Icon
import com.mantel.app.design.Icons
import com.mantel.app.design.ListRow
import com.mantel.app.design.Meter
import com.mantel.app.design.Samples
import com.mantel.app.design.ScreenHeader
import com.mantel.app.design.Tokens
import com.mantel.app.design.captionStyle
import com.mantel.app.previewModel

/**
 * Behind the avatar on Photos: who is signed in, the space they use, the backup, the trash, and
 * signing out. These are visited, not lived in, so they are one screen away rather than a section.
 */
@Composable
fun AccountScreen(
    state: Screen.Account,
    model: AppModel,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Colour.surface)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader("Account", onBack = model::back)
        Column(
            Modifier.padding(horizontal = Tokens.Space.page, vertical = Tokens.Space.rowY),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
        ) {
            val name = state.me.displayName
            Body(name ?: state.me.email)
            if (name != null) Body(state.me.email, style = captionStyle)
            Spacer(Modifier.height(Tokens.Space.gutter))
            // The quota is here because this is where a person comes to ask about it. A full library
            // refuses a backup, and the backup's status says so; this says how full.
            Meter(state.me.storageUsedBytes, state.me.storageQuotaBytes)
        }
        Hairline()
        ListRow("Backup", detail = "What this phone sends to the library, and when", onClick = model::openSync)
        ListRow("Trash", detail = "Deleted photographs, kept for 30 days", onClick = model::openTrash)
        ListRow(
            "Sign out",
            onClick = model::signOut,
            trailing = { Icon(Icons.LogOut, null, tint = Tokens.Colour.muted) },
        )
    }
}

@Preview(name = "Account", widthDp = 360, heightDp = 720)
@Composable
private fun AccountPreview() = AccountScreen(Screen.Account(Samples.me), previewModel())
