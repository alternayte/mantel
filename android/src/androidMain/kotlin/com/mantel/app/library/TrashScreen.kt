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
import com.mantel.app.design.ButtonRow
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
 * What was deleted in the last 30 days. Delete in Mantel is a move to here, never a removal, and
 * nothing here was ever removed from the phone: the backup only reads. A restore puts a photograph
 * back into the library and into every album it was in.
 */
@Composable
fun TrashScreen(
    state: Screen.Trash,
    model: AppModel,
) {
    BackHandler { model.back() }
    val grid = rememberLazyGridState()

    val loaded = state.items.size
    val cursor = state.cursor
    LaunchedEffect(loaded, cursor) {
        if (cursor == null) return@LaunchedEffect
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .first { it >= loaded - LOOKAHEAD }
        model.loadMoreTrash()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Colour.surface)
            .safeDrawingPadding(),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
    ) {
        ScreenHeader("Trash", onBack = model::back)
        Column(
            Modifier.padding(horizontal = Tokens.Space.page),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
        ) {
            Body(
                "Kept for 30 days after it was deleted, then removed for good. Nothing here was taken off the phone.",
                style = captionStyle,
            )
            if (state.error != null) {
                Body(state.error, style = failStyle)
                if (state.retryable) Button(text = "Try again", onClick = model::retry, quiet = true)
            }
            if (state.items.isEmpty() && !state.busy && state.error == null) {
                Body("The trash is empty.", style = captionStyle)
            }
        }

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
                    modifier = Modifier.pressable { model.toggleTrashSelection(item.id) },
                )
            }
        }

        if (state.selected.isNotEmpty()) {
            Column(Modifier.fillMaxWidth()) {
                Hairline()
                Column(
                    Modifier.padding(horizontal = Tokens.Space.page, vertical = Tokens.Space.rowY),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
                ) {
                    val count = state.selected.size
                    val them = if (count == 1) "it" else "them"
                    if (state.confirmingRemove) {
                        Body(if (count == 1) "Delete this photograph for good?" else "Delete these $count for good?")
                        Body("They cannot be restored after this. The phone keeps its own copy.", style = captionStyle)
                        ButtonRow {
                            Button(
                                text = "Delete for good",
                                onClick = model::removeSelectionForGood,
                                modifier = Modifier.weight(1f),
                                enabled = !state.busy,
                            )
                            Button(text = "Keep $them", onClick = model::cancelRemove, modifier = Modifier.weight(1f), quiet = true)
                        }
                    } else {
                        Body("$count selected", style = captionStyle)
                        ButtonRow {
                            Button(
                                text = "Restore",
                                onClick = model::restoreSelection,
                                modifier = Modifier.weight(1f),
                                enabled = !state.busy,
                            )
                            Button(
                                text = "Delete now",
                                onClick = model::askRemoveForGood,
                                modifier = Modifier.weight(1f),
                                enabled = !state.busy,
                                quiet = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val LOOKAHEAD = 12

@Preview(name = "Trash", widthDp = 360, heightDp = 720)
@Composable
private fun TrashPreview() =
    TrashScreen(
        Screen.Trash(items = List(10) { Samples.ready.copy(id = "t$it") }, totalItems = 10, selected = setOf("t1")),
        previewModel(),
    )

@Preview(name = "Trash: deleting for good", widthDp = 360, heightDp = 720)
@Composable
private fun RemovingPreview() =
    TrashScreen(
        Screen.Trash(
            items = List(10) { Samples.ready.copy(id = "t$it") },
            totalItems = 10,
            selected = setOf("t1", "t2"),
            confirmingRemove = true,
        ),
        previewModel(),
    )

@Preview(name = "Trash: empty", widthDp = 360, heightDp = 720)
@Composable
private fun EmptyTrashPreview() = TrashScreen(Screen.Trash(), previewModel())
