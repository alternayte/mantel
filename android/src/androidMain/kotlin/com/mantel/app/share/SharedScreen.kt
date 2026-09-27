package com.mantel.app.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.Preview
import com.mantel.app.AppModel
import com.mantel.app.Screen
import com.mantel.app.SharedLink
import com.mantel.app.design.Body
import com.mantel.app.design.Button
import com.mantel.app.design.ButtonRow
import com.mantel.app.design.Hairline
import com.mantel.app.design.IconButton
import com.mantel.app.design.Icons
import com.mantel.app.design.ListRow
import com.mantel.app.design.PullToRefresh
import com.mantel.app.design.Samples
import com.mantel.app.design.ScreenHeader
import com.mantel.app.design.Tokens
import com.mantel.app.design.captionStyle
import com.mantel.app.design.failStyle
import com.mantel.app.design.rememberPull
import com.mantel.app.previewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Every live share link, across every album. Mantel exists to hand an album to somebody as a link,
 * and until this screen nothing listed what had been handed out: what each link opens, when it
 * stops working, and the one control that takes it back.
 */
@Composable
fun SharedScreen(
    state: Screen.Shared,
    model: AppModel,
) {
    val pull = rememberPull(model::refresh)

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Colour.surface)
            .safeDrawingPadding()
            .nestedScroll(pull),
    ) {
        ScreenHeader("Shared")
        PullToRefresh(
            refreshing = state.refreshing,
            pull = pull.fraction,
            modifier = Modifier.padding(horizontal = Tokens.Space.page),
        )
        Column(
            Modifier.padding(horizontal = Tokens.Space.page),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
        ) {
            if (state.error != null) {
                Body(state.error, style = failStyle)
                if (state.retryable) Button(text = "Try again", onClick = model::retry, quiet = true)
            }
            if (state.links.isEmpty() && !state.busy && state.error == null) {
                Body(
                    "Nothing is shared. An album is shared from its own page, and every live link appears here.",
                    style = captionStyle,
                )
            }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(state.links, key = { it.link.id }) { shared ->
                if (state.revoking == shared.link.id) {
                    Revoking(shared, busy = state.busy, onRevoke = model::confirmRevoke, onKeep = model::cancelRevoke)
                } else {
                    ListRow(
                        title = shared.albumTitle,
                        detail = detailOf(shared),
                        onClick = { model.openAlbum(shared.albumId) },
                        trailing = {
                            IconButton(Icons.Share2, "Share the link to ${shared.albumTitle}", {
                                model.shareUrl(shared.link.url)
                            })
                            IconButton(
                                Icons.X,
                                "Revoke the link to ${shared.albumTitle}",
                                { model.askRevoke(shared.link.id) },
                                tint = Tokens.Colour.muted,
                            )
                        },
                    )
                }
            }
        }
    }
}

/** The second press. Revoking cannot be undone, so the row says so in words before it happens. */
@Composable
private fun Revoking(
    shared: SharedLink,
    busy: Boolean,
    onRevoke: () -> Unit,
    onKeep: () -> Unit,
) {
    Column {
        Column(
            Modifier.padding(horizontal = Tokens.Space.page, vertical = Tokens.Space.rowY),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
        ) {
            Body("Revoke the link to ${shared.albumTitle}?")
            Body("Anyone who has it sees nothing from the next time they open it. This cannot be undone.", style = captionStyle)
            ButtonRow {
                Button(text = "Revoke", onClick = onRevoke, modifier = Modifier.weight(1f), enabled = !busy)
                Button(text = "Keep it", onClick = onKeep, modifier = Modifier.weight(1f), quiet = true)
            }
        }
        Hairline(Modifier.padding(start = Tokens.Space.page))
    }
}

private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy")

/** Whether it needs a PIN, when it stops, and the address itself, which is what gets pasted. */
private fun detailOf(shared: SharedLink): String {
    val pin = if (shared.link.hasPin) "PIN" else "No PIN"
    val until =
        shared.link.expiresAt
            ?.let { runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).format(DAY) }.getOrNull() }
            ?.let { "until $it" }
            ?: "no expiry"
    return "$pin · $until\n${shared.link.url.substringAfter("://")}"
}

@Preview(name = "Shared", widthDp = 360, heightDp = 720)
@Composable
private fun SharedPreview() = SharedScreen(Screen.Shared(links = Samples.shared), previewModel())

@Preview(name = "Shared: revoking", widthDp = 360, heightDp = 720)
@Composable
private fun RevokingPreview() =
    SharedScreen(Screen.Shared(links = Samples.shared, revoking = Samples.shared.first().link.id), previewModel())

@Preview(name = "Shared: nothing", widthDp = 360, heightDp = 720)
@Composable
private fun NothingSharedPreview() = SharedScreen(Screen.Shared(), previewModel())
