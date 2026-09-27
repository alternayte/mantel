package com.mantel.app.album

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.mantel.app.design.ButtonRow
import com.mantel.app.design.Field
import com.mantel.app.design.IconButton
import com.mantel.app.design.Icons
import com.mantel.app.design.ItemTile
import com.mantel.app.design.Samples
import com.mantel.app.design.ScreenHeader
import com.mantel.app.design.Tokens
import com.mantel.app.design.UploadProgress
import com.mantel.app.design.bodyStyle
import com.mantel.app.design.captionStyle
import com.mantel.app.design.failStyle
import com.mantel.app.design.pressable
import com.mantel.app.design.rememberGridReorder
import com.mantel.app.design.reorderable
import com.mantel.app.previewModel
import com.mantel.app.share.ShareSheet
import com.mantel.app.timeline.Info
import com.mantel.app.timeline.MediaViewer
import com.mantel.app.timeline.ProvideViewerTransition
import com.mantel.app.timeline.Tile
import com.mantel.app.timeline.ViewerTransition
import com.mantel.app.timeline.takenAtOf

/**
 * One album: what is in it, what is still arriving, and what can be done to it.
 *
 * Run B chose one workspace over a wizard — drop, watch, publish, with nothing between the creator
 * and the album (DESIGN.md). Everything an album needs happens on this screen: a tap opens an item
 * full screen, where its caption, the cover and removal are; a long press drags it to a new place.
 */
@kotlin.OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AlbumScreen(
    state: Screen.Album,
    upload: UploadStatus?,
    model: AppModel,
) {
    // The grid's place is held outside the transition, so closing the viewer returns to it.
    val grid = rememberLazyGridState()
    SharedTransitionLayout(Modifier.fillMaxSize().background(Tokens.Colour.surface)) {
        AnimatedContent(
            targetState = state.selected != null,
            transitionSpec = {
                val spec = tween<Float>(Tokens.Motion.medium, easing = Tokens.Motion.ease)
                fadeIn(spec) togetherWith fadeOut(spec)
            },
            label = "album-viewer",
        ) { open ->
            ProvideViewerTransition(ViewerTransition(this@SharedTransitionLayout, this)) {
                if (open) AlbumViewer(state, model) else AlbumBody(state, upload, model, grid)
            }
        }
    }
    if (state.sharing) ShareSheet(state, model)
}

@Composable
private fun AlbumBody(
    state: Screen.Album,
    upload: UploadStatus?,
    model: AppModel,
    grid: LazyGridState,
) {
    BackHandler(enabled = true) {
        if (state.sharing) model.closeSharing() else model.back()
    }
    val reorder = rememberGridReorder(grid, onMove = model::move, onDrop = model::dropOrder)

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Colour.surface)
            .safeDrawingPadding(),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
    ) {
        ScreenHeader(state.album.title, onBack = model::back) {
            IconButton(Icons.Plus, "Add photos from this phone", model::pickMedia, enabled = !state.busy)
            IconButton(Icons.Images, "Add from the library", model::addFromLibrary, enabled = !state.busy)
            IconButton(
                Icons.Link2,
                if (state.liveLinks.isEmpty()) "Share" else "Links",
                model::openSharing,
                enabled = !state.busy,
                tint = if (state.liveLinks.isEmpty()) Tokens.Colour.ink else Tokens.Colour.accent,
            )
        }
        Column(
            Modifier.padding(horizontal = Tokens.Space.page),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
        ) {
            val links =
                when (state.liveLinks.size) {
                    0 -> null
                    1 -> "1 live link"
                    else -> "${state.liveLinks.size} live links"
                }
            Body(
                listOfNotNull(summaryOf(state.album.itemCount, state.album.totalBytes, state.album.status), links)
                    .joinToString(" · "),
                style = captionStyle,
            )

            if (upload != null) {
                if (upload.failed != null) {
                    Body(upload.failed, style = failStyle)
                } else {
                    Body("Uploading ${upload.index + 1} of ${upload.count}", style = captionStyle)
                    UploadProgress(upload.filename, upload.doneBytes, upload.totalBytes)
                }
            }

            if (state.error != null && !state.sharing) {
                Body(state.error, style = failStyle)
                // Offline is not a failure to report and forget: the same call works later, so the
                // screen offers it.
                if (state.retryable) Button(text = "Try again", onClick = model::retry, quiet = true)
            }

            if (state.album.items.isEmpty()) {
                Body("Nothing in this album yet. The plus adds photographs from this phone.", style = captionStyle)
            }
        }

        // The grid runs to the screen's edges, like the library's (DESIGN.md).
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
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
                    growKey = "l:${item.id}",
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
                            .pressable { model.select(item.id) },
                )
            }
        }
    }
}

/**
 * An album's items full screen, in the album's order. What is done to one item in an album is done
 * here, over the photograph, rather than in a card over the grid: its caption, whether it is the
 * cover, taking it out of the album, and trying again when it failed.
 */
@Composable
private fun AlbumViewer(
    state: Screen.Album,
    model: AppModel,
) {
    val tiles = remember(state.album.items) { state.album.items.map { Tile(key = "l:${it.id}", takenAt = takenAtOf(it), item = it) } }
    MediaViewer(
        tiles = tiles,
        viewing = state.selected?.let { "l:$it" },
        chrome = state.chrome,
        onMoved = { key -> model.albumViewerMoved(key.removePrefix("l:")) },
        onClose = { model.select(null) },
        onTap = model::toggleAlbumChrome,
        onBack = {
            when {
                state.editingCaption -> model.editCaption(false)
                state.showingInfo -> model.toggleAlbumInfo()
                else -> model.select(null)
            }
        },
    ) { tile -> AlbumControls(state, model, tile) }
}

@Composable
private fun AlbumControls(
    state: Screen.Album,
    model: AppModel,
    tile: Tile?,
) {
    val item = state.selectedItem
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            IconButton(Icons.X, "Close", { model.select(null) })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                if (state.showingInfo && tile != null) Info(tile)
                if (state.editingCaption) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .background(Tokens.Colour.surfaceLift, RoundedCornerShape(Tokens.Radius.card))
                            .padding(Tokens.Space.page),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
                    ) {
                        Field(
                            value = state.caption,
                            onValueChange = model::setCaption,
                            label = "Caption in this album",
                            onSubmit = model::saveCaption,
                            enabled = !state.busy,
                        )
                        ButtonRow {
                            Button(text = "Save", onClick = model::saveCaption, modifier = Modifier.weight(1f), enabled = !state.busy)
                            Button(text = "Cancel", onClick = { model.editCaption(false) }, modifier = Modifier.weight(1f), quiet = true)
                        }
                    }
                } else if (!state.showingInfo && item?.caption != null) {
                    // The words under it in this album, as a recipient will read them.
                    Body(
                        item.caption,
                        style = bodyStyle,
                        modifier = Modifier.padding(horizontal = Tokens.Space.page, vertical = Tokens.Space.gutter),
                    )
                }
                if (state.error != null) {
                    Body(state.error, style = failStyle, modifier = Modifier.padding(horizontal = Tokens.Space.page))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            IconButton(
                Icons.MessageSquareText,
                "Caption",
                { model.editCaption(!state.editingCaption) },
                tint = if (state.editingCaption) Tokens.Colour.accent else Tokens.Colour.ink,
            )
            val isCover = item != null && item.id == state.album.coverItemId
            IconButton(
                Icons.Image,
                if (isCover) "This is the cover" else "Use as cover",
                model::setCover,
                enabled = !state.busy && !isCover,
                tint = if (isCover) Tokens.Colour.accent else Tokens.Colour.ink,
            )
            if (item?.state == ItemStatus.FAILED) {
                IconButton(Icons.RotateCcw, "Try again", model::retryItem, enabled = !state.busy)
            }
            IconButton(
                Icons.Info,
                "Info",
                model::toggleAlbumInfo,
                tint = if (state.showingInfo) Tokens.Colour.accent else Tokens.Colour.ink,
            )
            // Out of the album, not out of the library: an album is a selection.
            IconButton(Icons.CircleMinus, "Remove from this album", model::deleteItem, enabled = !state.busy)
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

@Preview(name = "Album: offline", widthDp = 360, heightDp = 720)
@Composable
private fun OfflineAlbumPreview() =
    AlbumScreen(
        Screen.Album(
            Samples.album,
            error = "That server did not answer. You may be offline.",
            retryable = true,
        ),
        null,
        previewModel(),
    )

/** Nothing is shared yet, which is every album until somebody decides otherwise. */
@Preview(name = "Share: not published", widthDp = 360, heightDp = 720)
@Composable
private fun NotPublishedPreview() = AlbumScreen(Screen.Album(Samples.album, sharing = true), null, previewModel())

@Preview(name = "Share: links", widthDp = 360, heightDp = 720)
@Composable
private fun ShareLinksPreview() =
    AlbumScreen(
        Screen.Album(Samples.album, sharing = true, links = Samples.links),
        null,
        previewModel(),
    )

@Preview(name = "Album: one item open", widthDp = 360, heightDp = 720)
@Composable
private fun ItemSheetPreview() =
    AlbumScreen(
        Screen.Album(Samples.album, selected = Samples.failed.id, caption = ""),
        null,
        previewModel(),
    )
