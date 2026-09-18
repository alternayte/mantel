package com.mantel.app.album

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mantel.app.AppModel
import com.mantel.app.Screen
import com.mantel.app.UploadStatus
import com.mantel.app.api.ItemStatus
import com.mantel.app.api.state
import com.mantel.app.design.Body
import com.mantel.app.design.Button
import com.mantel.app.design.Card
import com.mantel.app.design.Field
import com.mantel.app.design.ItemTile
import com.mantel.app.design.Samples
import com.mantel.app.design.Title
import com.mantel.app.design.Tokens
import com.mantel.app.design.UploadProgress
import com.mantel.app.design.captionStyle
import com.mantel.app.design.failStyle
import com.mantel.app.design.rememberGridReorder
import com.mantel.app.design.reorderable
import com.mantel.app.previewModel

/**
 * One album: what is in it, what is still arriving, and the four things that can be done to an item.
 *
 * Run B chose one workspace over a wizard — drop, watch, publish, with nothing between the creator
 * and the album (DESIGN.md). Sharing is the next milestone; everything else is here.
 */
@Composable
fun AlbumScreen(
    state: Screen.Album,
    upload: UploadStatus?,
    model: AppModel,
) {
    BackHandler(enabled = true) { if (state.selected != null) model.select(null) else model.back() }

    val grid = rememberLazyGridState()
    val reorder = rememberGridReorder(grid, onMove = model::move, onDrop = model::dropOrder)

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Colour.surface)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
    ) {
        Spacer(Modifier.height(Tokens.Space.titleY))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Body("Albums", style = captionStyle, modifier = Modifier.clickable { model.back() })
        }
        Title(state.album.title)
        Body(
            summaryOf(state.album.itemCount, state.album.totalBytes, state.album.status),
            style = captionStyle,
        )

        if (upload != null) {
            Card {
                if (upload.failed != null) {
                    Body(upload.failed, style = failStyle)
                } else {
                    Body("Uploading ${upload.index + 1} of ${upload.count}", style = captionStyle)
                    UploadProgress(upload.filename, upload.doneBytes, upload.totalBytes)
                }
            }
        }

        if (state.error != null) Body(state.error, style = failStyle)

        if (state.album.items.isEmpty()) {
            Body("Nothing in this album yet.", style = captionStyle)
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = grid,
            modifier = Modifier.fillMaxWidth().weight(1f).reorderable(reorder),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.gutterTight),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutterTight),
        ) {
            items(state.album.items, key = { it.id }) { item ->
                val index = state.album.items.indexOfFirst { it.id == item.id }
                val held = reorder.draggingIndex == index
                val offset = reorder.heldOffset(index)
                ItemTile(
                    item = item,
                    isCover = item.id == state.album.coverItemId,
                    modifier =
                        Modifier
                            .graphicsLayer {
                                if (held) {
                                    translationX = offset.x
                                    translationY = offset.y
                                    scaleX = 1.06f
                                    scaleY = 1.06f
                                }
                            }
                            .alpha(if (held) 0.9f else 1f)
                            .clickable { model.select(item.id) },
                )
            }
        }

        Button(text = "Add photos", onClick = model::pickMedia, enabled = !state.busy)
        Spacer(Modifier.height(Tokens.Space.gutter))
    }

    state.selectedItem?.let { item ->
        ItemSheet(
            caption = state.caption,
            failed = item.state == ItemStatus.FAILED,
            isCover = item.id == state.album.coverItemId,
            busy = state.busy,
            model = model,
        )
    }
}

/**
 * What can be done to one item. It is a card over the grid rather than a screen of its own: the
 * album is the thing being worked on, and losing sight of it to write a caption is a wizard.
 */
@Composable
private fun ItemSheet(
    caption: String,
    failed: Boolean,
    isCover: Boolean,
    busy: Boolean,
    model: AppModel,
) {
    Box(
        Modifier
            .fillMaxSize()
            // The keyboard opens under the caption field, so the card sits above it rather than
            // behind it. Tapping away from the card is how it closes.
            .safeDrawingPadding()
            .clickable { model.select(null) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Card(Modifier.padding(24.dp)) {
            Field(
                value = caption,
                onValueChange = model::setCaption,
                label = "Caption",
                onSubmit = model::saveCaption,
                enabled = !busy,
            )
            Button(text = "Save caption", onClick = model::saveCaption, enabled = !busy)
            if (!isCover) Button(text = "Use as cover", onClick = model::setCover, enabled = !busy, quiet = true)
            if (failed) Button(text = "Try again", onClick = model::retryItem, enabled = !busy, quiet = true)
            Button(text = "Remove", onClick = model::deleteItem, enabled = !busy, quiet = true)
        }
    }
}

@Preview(name = "Album", widthDp = 360, heightDp = 720)
@Composable
private fun AlbumPreview() = AlbumScreen(Screen.Album(Samples.album), null, previewModel())

@Preview(name = "Album: empty", widthDp = 360, heightDp = 720)
@Composable
private fun EmptyAlbumPreview() = AlbumScreen(Screen.Album(Samples.album.copy(items = emptyList(), itemCount = 0)), null, previewModel())

@Preview(name = "Album: uploading", widthDp = 360, heightDp = 720)
@Composable
private fun UploadingPreview() =
    AlbumScreen(
        Screen.Album(Samples.album),
        UploadStatus(
            filename = "09-panorama.jpg",
            doneBytes = 9L * 1024 * 1024,
            totalBytes = 24L * 1024 * 1024,
            index = 2,
            count = 12,
        ),
        previewModel(),
    )

@Preview(name = "Album: upload failed", widthDp = 360, heightDp = 720)
@Composable
private fun UploadFailedPreview() =
    AlbumScreen(
        Screen.Album(Samples.album),
        UploadStatus("", 0, 0, 0, 0, failed = "Your storage quota is full"),
        previewModel(),
    )

@Preview(name = "Album: one item", widthDp = 360, heightDp = 720)
@Composable
private fun ItemSheetPreview() =
    AlbumScreen(
        Screen.Album(Samples.album, selected = Samples.failed.id, caption = ""),
        null,
        previewModel(),
    )
