package com.mantel.app.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mantel.app.AppModel
import com.mantel.app.Screen
import com.mantel.app.design.Body
import com.mantel.app.design.Button
import com.mantel.app.design.ButtonRow
import com.mantel.app.design.Card
import com.mantel.app.design.ItemTile
import com.mantel.app.design.Peer
import com.mantel.app.design.PeerSwitch
import com.mantel.app.design.PullToRefresh
import com.mantel.app.design.Samples
import com.mantel.app.design.Title
import com.mantel.app.design.Tokens
import com.mantel.app.design.captionStyle
import com.mantel.app.design.failStyle
import com.mantel.app.design.pressable
import com.mantel.app.design.rememberPull
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
    // The library is a peer, so back leaves the app; opened from an album, it returns to it.
    val canGoBack by model.canGoBack.collectAsState()
    BackHandler(enabled = canGoBack) { model.back() }

    val grid = rememberLazyGridState()
    val pull = rememberPull(model::refresh)

    // The next page is asked for before the grid runs out, not when it has.
    val nearTheEnd by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.items.size - LOOKAHEAD
        }
    }
    LaunchedEffect(nearTheEnd, state.cursor) {
        if (nearTheEnd && state.cursor != null) model.loadMoreLibrary()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Colour.surface)
            .safeDrawingPadding()
            .nestedScroll(pull)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
    ) {
        Spacer(Modifier.height(Tokens.Space.titleY))
        if (state.pickingFor != null) {
            Body("Back", style = captionStyle, modifier = Modifier.pressable { model.back() })
            Title("Library")
        } else {
            PeerSwitch(onAlbums = model::openAlbums, onLibrary = {}, current = Peer.LIBRARY)
        }
        PullToRefresh(refreshing = state.refreshing, pull = pull.fraction)
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
            state = grid,
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.gutterTight),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutterTight),
        ) {
            items(state.items, key = { it.id }) { item ->
                ItemTile(
                    item = item,
                    isSelected = item.id in state.selected,
                    modifier = Modifier.pressable { model.toggleSelection(item.id) },
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

/** How many tiles short of the end the next page is asked for: two rows of three. */
private const val LOOKAHEAD = 6
