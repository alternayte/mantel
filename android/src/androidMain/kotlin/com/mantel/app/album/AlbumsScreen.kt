package com.mantel.app.album

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mantel.app.AppModel
import com.mantel.app.Screen
import com.mantel.app.design.Body
import com.mantel.app.design.Button
import com.mantel.app.design.Card
import com.mantel.app.design.Field
import com.mantel.app.design.Meter
import com.mantel.app.design.Samples
import com.mantel.app.design.Title
import com.mantel.app.design.Tokens
import com.mantel.app.design.bytes
import com.mantel.app.design.captionStyle
import com.mantel.app.design.failStyle
import com.mantel.app.previewModel

/**
 * Every album, and the one field that makes another.
 *
 * The storage meter is here rather than on a settings screen because this is where it is noticed
 * (DESIGN.md), and because a quota nobody sees is a quota that fails an upload by surprise.
 */
@Composable
fun AlbumsScreen(
    state: Screen.Albums,
    model: AppModel,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Colour.surface)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
    ) {
        Spacer(Modifier.height(Tokens.Space.titleY))
        Title("Mantel")
        Spacer(Modifier.height(Tokens.Space.gutter))

        Card {
            Body(state.me.displayName ?: state.me.email)
            Meter(state.me.storageUsedBytes, state.me.storageQuotaBytes)
        }

        Card {
            Field(
                value = state.newTitle,
                onValueChange = model::setNewAlbumTitle,
                label = "New album",
                imeAction = ImeAction.Go,
                onSubmit = model::createAlbum,
                enabled = !state.busy,
            )
            Button(
                text = "Create",
                onClick = model::createAlbum,
                enabled = !state.busy && state.newTitle.isNotBlank(),
            )
        }

        if (state.error != null) {
            Card {
                Body(state.error, style = failStyle)
                if (state.retryable) Button(text = "Try again", onClick = model::retry, quiet = true)
            }
        }

        // Only when the server actually said there are none. A failed request is not an empty
        // account, and telling somebody their albums are gone because the network dropped is worse
        // than saying nothing.
        if (state.albums.isEmpty() && !state.busy && state.error == null) {
            Body("No albums yet. The first one is a title and forty photographs.", style = captionStyle)
        }

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
        ) {
            items(state.albums, key = { it.id }) { album ->
                Card(Modifier.clickable { model.openAlbum(album.id) }) {
                    Body(album.title)
                    Body(summaryOf(album.itemCount, album.totalBytes, album.status), style = captionStyle)
                }
            }
        }

        Button(text = "Library", onClick = model::openLibrary, quiet = true)
        Button(text = "Backup", onClick = model::openSync, quiet = true)
        Button(text = "Sign out", onClick = model::signOut, quiet = true)
        Spacer(Modifier.height(Tokens.Space.gutter))
    }
}

/** What an album is, in one line: how much of it there is, and whether it is live. */
fun summaryOf(
    itemCount: Int,
    totalBytes: Long,
    status: String,
): String {
    val items = if (itemCount == 1) "1 item" else "$itemCount items"
    val state =
        when (status.lowercase()) {
            "draft" -> "draft"
            "live" -> "live"
            "processing" -> "rendering"
            else -> status.lowercase()
        }
    return if (itemCount == 0) state else "$items · ${bytes(totalBytes)} · $state"
}

@Preview(name = "Albums", widthDp = 360, heightDp = 720)
@Composable
private fun AlbumsPreview() = AlbumsScreen(Screen.Albums(me = Samples.me, albums = Samples.albums), previewModel())

/** The first launch after signing in, which is the screen nobody remembers to design. */
@Preview(name = "Albums: none yet", widthDp = 360, heightDp = 720)
@Composable
private fun NoAlbumsPreview() = AlbumsScreen(Screen.Albums(me = Samples.me), previewModel())

@Preview(name = "Albums: offline", widthDp = 360, heightDp = 720)
@Composable
private fun OfflineAlbumsPreview() =
    AlbumsScreen(
        Screen.Albums(
            me = Samples.me,
            albums = Samples.albums,
            error = "That server did not answer. You may be offline.",
            retryable = true,
        ),
        previewModel(),
    )
