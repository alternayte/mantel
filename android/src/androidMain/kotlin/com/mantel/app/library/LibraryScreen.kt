package com.mantel.app.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mantel.app.AppModel
import com.mantel.app.Screen
import com.mantel.app.design.Body
import com.mantel.app.design.Button
import com.mantel.app.design.ButtonRow
import com.mantel.app.design.Card
import com.mantel.app.design.ItemTile
import com.mantel.app.design.Samples
import com.mantel.app.design.Title
import com.mantel.app.design.Tokens
import com.mantel.app.design.captionStyle
import com.mantel.app.design.failStyle
import com.mantel.app.previewModel

/**
 * Every photograph the account owns, newest first.
 *
 * A grid and a selection and nothing else. Search is what people stay on a photo library for, and
 * it is deliberately absent: this exists so a backup is visible, and so an album can be assembled
 * from more than what was picked in this session.
 */
@Composable
fun LibraryScreen(
    state: Screen.Library,
    model: AppModel,
) {
    BackHandler(enabled = true) { model.back() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Colour.surface)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
    ) {
        Spacer(Modifier.height(Tokens.Space.titleY))
        Body("Albums", style = captionStyle, modifier = Modifier.clickable { model.back() })
        Title("Library")
        Body(
            if (state.totalItems == 1L) "1 item" else "${state.totalItems} items",
            style = captionStyle,
        )

        if (state.error != null) {
            Card {
                Body(state.error, style = failStyle)
                if (state.retryable) Button(text = "Try again", onClick = model::retry, quiet = true)
            }
        }

        if (state.items.isEmpty() && !state.busy && state.error == null) {
            Body(
                "Nothing here yet. Everything uploaded, from this phone or from a browser, arrives here.",
                style = captionStyle,
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.gutterTight),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutterTight),
        ) {
            items(state.items, key = { it.id }) { item ->
                val chosen = item.id in state.selected
                ItemTile(
                    item = item,
                    modifier =
                        Modifier
                            .then(if (chosen) Modifier.border(2.dp, Tokens.Colour.ink) else Modifier)
                            .clickable { model.toggleSelection(item.id) },
                )
            }
        }

        if (state.selected.isNotEmpty()) {
            Card {
                Body("${state.selected.size} selected", style = captionStyle)
                if (state.addingTo != null) {
                    Body("Which album?", style = captionStyle)
                    state.albums.forEach { album ->
                        Button(
                            text = album.title,
                            onClick = { model.addSelectionTo(album.id) },
                            enabled = !state.busy,
                            quiet = true,
                        )
                    }
                    Button(text = "Cancel", onClick = model::cancelAdd, quiet = true)
                } else {
                    ButtonRow {
                        Button(
                            text = "Add to album",
                            onClick = model::chooseAlbum,
                            modifier = Modifier.weight(1f),
                            enabled = !state.busy,
                        )
                        Button(
                            text = "Delete",
                            onClick = model::deleteSelection,
                            modifier = Modifier.weight(1f),
                            enabled = !state.busy,
                            quiet = true,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(Tokens.Space.gutter))
    }
}

@Preview(name = "Library", widthDp = 360, heightDp = 720)
@Composable
private fun LibraryPreview() =
    LibraryScreen(
        Screen.Library(items = Samples.items, totalItems = Samples.items.size.toLong()),
        previewModel(),
    )

@Preview(name = "Library: selected", widthDp = 360, heightDp = 720)
@Composable
private fun SelectedPreview() =
    LibraryScreen(
        Screen.Library(
            items = Samples.items,
            totalItems = Samples.items.size.toLong(),
            selected = setOf(Samples.ready.id, Samples.video.id),
            albums = Samples.albums,
        ),
        previewModel(),
    )

/** The screen nobody remembers to design, and the first one a new account sees. */
@Preview(name = "Library: empty", widthDp = 360, heightDp = 720)
@Composable
private fun EmptyLibraryPreview() = LibraryScreen(Screen.Library(), previewModel())
