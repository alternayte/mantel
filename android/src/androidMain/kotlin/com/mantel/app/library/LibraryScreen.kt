package com.mantel.app.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.mantel.app.AppModel
import com.mantel.app.Screen
import com.mantel.app.design.Body
import com.mantel.app.design.Button
import com.mantel.app.design.Hairline
import com.mantel.app.design.ItemTile
import com.mantel.app.design.Samples
import com.mantel.app.design.ScreenHeader
import com.mantel.app.design.Tokens
import com.mantel.app.design.captionStyle
import com.mantel.app.design.failStyle
import com.mantel.app.design.pressable
import com.mantel.app.previewModel
import kotlinx.coroutines.flow.first

/**
 * The library, opened over an album to choose what goes into it. Everything here is already in the
 * library, so adding it costs no upload and no quota: an album is a selection.
 */
@Composable
fun LibraryScreen(
    state: Screen.Library,
    model: AppModel,
) {
    BackHandler { model.back() }

    val grid = rememberLazyGridState()

    // The next page is asked for before the grid runs out, not when it has. The effect re-arms on
    // every page, and reads the count from the current state: a remembered lambda would hold the
    // list it first saw and stop asking after page one.
    val loaded = state.items.size
    val cursor = state.cursor
    LaunchedEffect(loaded, cursor) {
        if (cursor == null) return@LaunchedEffect
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .first { it >= loaded - LOOKAHEAD }
        model.loadMoreLibrary()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Colour.surface)
            .safeDrawingPadding(),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
    ) {
        ScreenHeader("Add to album", onBack = model::back)
        Column(
            Modifier.padding(horizontal = Tokens.Space.page),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
        ) {
            Body(if (state.totalItems == 1L) "1 item" else "${state.totalItems} items", style = captionStyle)
            if (state.error != null) {
                Body(state.error, style = failStyle)
                if (state.retryable) Button(text = "Try again", onClick = model::retry, quiet = true)
            }
            if (state.items.isEmpty() && !state.busy && state.error == null) {
                Body(
                    "Nothing here yet. Everything uploaded, from this phone or from a browser, arrives here.",
                    style = captionStyle,
                )
            }
        }

        // The grid runs to the screen's edges: photographs are the page (DESIGN.md).
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Fixed(4),
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
            Column(Modifier.fillMaxWidth()) {
                Hairline()
                Button(
                    text = if (state.selected.size == 1) "Add 1 to the album" else "Add ${state.selected.size} to the album",
                    onClick = model::addSelectionToPickingAlbum,
                    enabled = !state.busy,
                    modifier = Modifier.padding(horizontal = Tokens.Space.page, vertical = Tokens.Space.rowY),
                )
            }
        }
    }
}

private const val LOOKAHEAD = 12

@Preview(name = "Add to album", widthDp = 360, heightDp = 720)
@Composable
private fun LibraryPreview() =
    LibraryScreen(
        Screen.Library(
            pickingFor = "a1",
            items = List(12) { Samples.ready.copy(id = "i$it") },
            totalItems = 12,
            selected = setOf("i1", "i4"),
        ),
        previewModel(),
    )
