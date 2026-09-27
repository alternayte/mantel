package com.mantel.app.album

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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import com.mantel.app.AppModel
import com.mantel.app.Screen
import com.mantel.app.design.Body
import com.mantel.app.design.Button
import com.mantel.app.design.ButtonRow
import com.mantel.app.design.Field
import com.mantel.app.design.IconButton
import com.mantel.app.design.Icons
import com.mantel.app.design.ListRow
import com.mantel.app.design.PullToRefresh
import com.mantel.app.design.Samples
import com.mantel.app.design.ScreenHeader
import com.mantel.app.design.Tokens
import com.mantel.app.design.bytes
import com.mantel.app.design.captionStyle
import com.mantel.app.design.failStyle
import com.mantel.app.design.rememberPull
import com.mantel.app.previewModel

/**
 * Every album, one flat row each, and the plus that makes another.
 *
 * The account, the storage figure and sign-out used to sit here; they live behind the avatar on
 * Photos now, because they are visited and not lived in.
 */
@Composable
fun AlbumsScreen(
    state: Screen.Albums,
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
        ScreenHeader("Albums") {
            if (!state.creating) IconButton(Icons.Plus, "New album", model::startNewAlbum)
        }
        PullToRefresh(
            refreshing = state.refreshing,
            pull = pull.fraction,
            modifier = Modifier.padding(horizontal = Tokens.Space.page),
        )

        Column(
            Modifier.padding(horizontal = Tokens.Space.page),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
        ) {
            if (state.creating) {
                Field(
                    value = state.newTitle,
                    onValueChange = model::setNewAlbumTitle,
                    label = "New album",
                    imeAction = ImeAction.Go,
                    onSubmit = model::createAlbum,
                    enabled = !state.busy,
                )
                ButtonRow {
                    Button(
                        text = "Create",
                        onClick = model::createAlbum,
                        modifier = Modifier.weight(1f),
                        enabled = !state.busy && state.newTitle.isNotBlank(),
                    )
                    Button(text = "Cancel", onClick = model::cancelNewAlbum, modifier = Modifier.weight(1f), quiet = true)
                }
            }

            if (state.error != null) {
                Body(state.error, style = failStyle)
                if (state.retryable) Button(text = "Try again", onClick = model::retry, quiet = true)
            }

            // Only when the server actually said there are none. A failed request is not an empty
            // account, and telling somebody their albums are gone because the network dropped is
            // worse than saying nothing.
            if (state.albums.isEmpty() && !state.busy && state.error == null && !state.creating) {
                Body("No albums yet. The first one is a title and forty photographs.", style = captionStyle)
            }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(state.albums, key = { it.id }) { album ->
                ListRow(
                    title = album.title,
                    detail = summaryOf(album.itemCount, album.totalBytes, album.status),
                    onClick = { model.openAlbum(album.id) },
                )
            }
        }
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
private fun AlbumsPreview() = AlbumsScreen(Screen.Albums(albums = Samples.albums), previewModel())

/** The first launch after signing in, which is the screen nobody remembers to design. */
@Preview(name = "Albums: none yet", widthDp = 360, heightDp = 720)
@Composable
private fun NoAlbumsPreview() = AlbumsScreen(Screen.Albums(), previewModel())

@Preview(name = "Albums: a new one", widthDp = 360, heightDp = 720)
@Composable
private fun NewAlbumPreview() = AlbumsScreen(Screen.Albums(albums = Samples.albums, creating = true, newTitle = "Kitchen"), previewModel())

@Preview(name = "Albums: offline", widthDp = 360, heightDp = 720)
@Composable
private fun OfflineAlbumsPreview() =
    AlbumsScreen(
        Screen.Albums(
            albums = Samples.albums,
            error = "That server did not answer. You may be offline.",
            retryable = true,
        ),
        previewModel(),
    )
