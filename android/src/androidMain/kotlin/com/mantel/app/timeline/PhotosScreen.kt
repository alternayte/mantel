package com.mantel.app.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.mantel.app.AppModel
import com.mantel.app.Screen
import com.mantel.app.design.Body
import com.mantel.app.design.Button
import com.mantel.app.design.ButtonRow
import com.mantel.app.design.Field
import com.mantel.app.design.Hairline
import com.mantel.app.design.Icon
import com.mantel.app.design.IconButton
import com.mantel.app.design.Icons
import com.mantel.app.design.ListRow
import com.mantel.app.design.PullToRefresh
import com.mantel.app.design.ScreenHeader
import com.mantel.app.design.Tokens
import com.mantel.app.design.UploadProgress
import com.mantel.app.design.bodyStyle
import com.mantel.app.design.captionStyle
import com.mantel.app.design.failStyle
import com.mantel.app.design.pressable
import com.mantel.app.design.rememberPull
import com.mantel.app.design.rowStyle
import com.mantel.app.design.screenTitleStyle
import com.mantel.app.design.stateColour
import com.mantel.app.previewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Photos: one timeline of the phone's camera roll and the library, grouped by the day each
 * photograph was taken. A photograph shows the moment it is taken, before it is backed up, and a
 * photograph on the phone and in the library shows once.
 */
@Composable
fun PhotosScreen(
    state: Screen.Photos,
    model: AppModel,
) {
    val grid = rememberLazyGridState()
    val pull = rememberPull(model::refresh)
    val backup by model.backupStatus.collectAsState()

    // The next page of the library is asked for before the grid runs out of what it has.
    val cells = state.timeline.cells
    val cursor = state.cursor
    LaunchedEffect(cells.size, cursor) {
        if (cursor == null) return@LaunchedEffect
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .first { it >= cells.size - state.columns * LOOKAHEAD_ROWS }
        model.loadMorePhotos()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Colour.surface)
            .safeDrawingPadding()
            .nestedScroll(pull),
    ) {
        if (state.selecting) {
            SelectionHeader(state, model)
        } else {
            ScreenHeader("Photos") {
                IconButton(Icons.CircleUserRound, "Account", model::openAccount)
            }
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
            // The phone's backup reports here, where its results land, until it has a line of its own.
            backup?.let { status ->
                if (status.failed != null) {
                    Body(status.failed, style = failStyle)
                    Button(text = "Try the backup again", onClick = model::retryBackup, quiet = true)
                } else {
                    Body("Backing up ${status.index + 1} of ${status.count}", style = captionStyle)
                    UploadProgress(status.filename, status.doneBytes, status.totalBytes)
                }
            }
            state.note?.let { Body(it, style = captionStyle) }
            if (state.error != null) {
                Body(state.error, style = failStyle)
                if (state.retryable) Button(text = "Try again", onClick = model::retry, quiet = true)
            }
        }

        if (!state.phoneAccess && !state.selecting) {
            ListRow(
                "Show this phone's photographs",
                detail = "Here beside the library, the moment they are taken.",
                onClick = model::showPhonePhotos,
            )
        }

        if (state.choosingAlbum) {
            AlbumChoice(state, model)
        }

        if (cells.isEmpty() && !state.busy && state.error == null) {
            Body(
                "Nothing here yet. Everything uploaded, from this phone or from a browser, arrives here.",
                style = captionStyle,
                modifier = Modifier.padding(horizontal = Tokens.Space.page, vertical = Tokens.Space.rowY),
            )
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            TimelineGrid(state, model, grid)
            MonthHandle(state.timeline, grid, Modifier.align(Alignment.TopEnd))
        }
    }
}

/** The selection's own header: how many, and the three things a selection is for. */
@Composable
private fun SelectionHeader(
    state: Screen.Photos,
    model: AppModel,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Tokens.Space.gutter * 2)
            .height(56.dp)
            .padding(horizontal = Tokens.Space.page - 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(Icons.X, "Clear the selection", model::clearSelection)
        BasicText(
            "${state.selected.size}",
            style = screenTitleStyle.copy(color = Tokens.Colour.accent),
            modifier = Modifier.padding(start = 4.dp).weight(1f),
        )
        IconButton(Icons.Share2, "Share", model::shareSelection, enabled = !state.busy)
        IconButton(Icons.FolderPlus, "Add to album", model::chooseAlbumForSelection, enabled = !state.busy)
        IconButton(Icons.Trash2, "Delete", model::deleteSelection, enabled = !state.busy)
    }
}

/** Which album a selection goes into, or a new one named here. */
@Composable
private fun AlbumChoice(
    state: Screen.Photos,
    model: AppModel,
) {
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = Tokens.Space.page, vertical = Tokens.Space.gutter),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
        ) {
            Field(
                value = state.newAlbumTitle,
                onValueChange = model::setSelectionAlbumTitle,
                label = "New album",
                imeAction = ImeAction.Go,
                onSubmit = model::newAlbumFromSelection,
                enabled = !state.busy,
            )
            ButtonRow {
                Button(
                    text = "Create with ${state.selected.size}",
                    onClick = model::newAlbumFromSelection,
                    modifier = Modifier.weight(1f),
                    enabled = !state.busy && state.newAlbumTitle.isNotBlank(),
                )
                Button(text = "Cancel", onClick = model::cancelChooseAlbum, modifier = Modifier.weight(1f), quiet = true)
            }
        }
        Hairline()
        state.albums.take(6).forEach { album ->
            ListRow(album.title, onClick = { model.addSelectionToAlbum(album.id) })
        }
    }
}

@Composable
private fun TimelineGrid(
    state: Screen.Photos,
    model: AppModel,
    grid: LazyGridState,
) {
    val cells = state.timeline.cells
    // How fast the grid scrolls while a drag-selection sits near its top or bottom edge.
    var autoScroll by remember { mutableFloatStateOf(0f) }
    // While a drag extends a selection the grid must not scroll under the finger; it scrolls only
    // when the drag reaches an edge, by itself.
    var dragSelecting by remember { mutableStateOf(false) }
    LaunchedEffect(autoScroll) {
        if (autoScroll == 0f) return@LaunchedEffect
        while (isActive) {
            grid.scrollBy(autoScroll)
            delay(16)
        }
    }
    val edge = with(LocalDensity.current) { 72.dp.toPx() }

    LazyVerticalGrid(
        state = grid,
        columns = GridCells.Fixed(state.columns),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.gutterTight),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutterTight),
        userScrollEnabled = !dragSelecting,
        modifier =
            Modifier
                .fillMaxSize()
                .pinchToZoom(model::zoom)
                .pointerInput(cells) {
                    // Long-press starts a selection; the drag that follows extends it over everything
                    // between where it started and where the finger is now.
                    var anchor = -1
                    detectDragGesturesAfterLongPress(
                        onDragStart = { at ->
                            val index = grid.cellAt(at) ?: return@detectDragGesturesAfterLongPress
                            when (val cell = cells.getOrNull(index)) {
                                is Cell.Photo -> {
                                    anchor = index
                                    dragSelecting = true
                                    model.startSelection(cell.tile.key)
                                }
                                is Cell.Heading -> model.toggleDay(cell.day.date)
                                null -> Unit
                            }
                        },
                        onDrag = { change, _ ->
                            if (anchor < 0) return@detectDragGesturesAfterLongPress
                            val y = change.position.y
                            autoScroll =
                                when {
                                    y < edge -> -(edge - y) / 4
                                    y > size.height - edge -> (y - (size.height - edge)) / 4
                                    else -> 0f
                                }
                            val index = grid.cellAt(change.position) ?: return@detectDragGesturesAfterLongPress
                            val range = if (index >= anchor) anchor..index else index..anchor
                            model.selectRange(range.mapNotNull { (cells.getOrNull(it) as? Cell.Photo)?.tile?.key })
                        },
                        onDragEnd = {
                            anchor = -1
                            autoScroll = 0f
                            dragSelecting = false
                        },
                        onDragCancel = {
                            anchor = -1
                            autoScroll = 0f
                            dragSelecting = false
                        },
                    )
                },
    ) {
        items(
            cells,
            key = { it.key },
            span = { cell -> if (cell is Cell.Heading) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            contentType = { if (it is Cell.Heading) 0 else 1 },
        ) { cell ->
            when (cell) {
                is Cell.Heading ->
                    DayHeading(
                        day = cell.day,
                        selecting = state.selecting,
                        allSelected = cell.day.tiles.all { it.key in state.selected },
                        onToggle = { model.toggleDay(cell.day.date) },
                    )
                is Cell.Photo ->
                    TimelineTile(
                        tile = cell.tile,
                        selecting = state.selecting,
                        selected = cell.tile.key in state.selected,
                        onClick = {
                            if (state.selecting) model.toggleTile(cell.tile.key)
                        },
                    )
            }
        }
    }
}

/** Which cell is under a point in the grid, if any. */
private fun LazyGridState.cellAt(point: Offset): Int? =
    layoutInfo.visibleItemsInfo.firstOrNull { item ->
        point.x >= item.offset.x && point.x < item.offset.x + item.size.width &&
            point.y >= item.offset.y && point.y < item.offset.y + item.size.height
    }?.index

/**
 * Two fingers moving apart or together change the density. One finger is left alone for the grid to
 * scroll, and a pinch does not scroll it.
 */
private fun Modifier.pinchToZoom(onZoom: (closer: Boolean) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var scale = 1f
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size < 2) continue
                scale *= event.calculateZoom()
                event.changes.forEach { it.consume() }
                if (scale > 1.25f) {
                    onZoom(true)
                    scale = 1f
                } else if (scale < 0.8f) {
                    onZoom(false)
                    scale = 1f
                }
            }
        }
    }

/** A day's heading. While selecting, it selects or clears its whole day. */
@Composable
private fun DayHeading(
    day: Day,
    selecting: Boolean,
    allSelected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (selecting) Modifier.pressable(onClick = onToggle) else Modifier)
            .padding(start = Tokens.Space.page, end = Tokens.Space.page, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(dayLabel(day.date), style = rowStyle, modifier = Modifier.weight(1f))
        if (selecting) {
            Icon(
                if (allSelected) Icons.CircleCheck else Icons.Circle,
                if (allSelected) "Clear this day" else "Select this day",
                tint = if (allSelected) Tokens.Colour.accent else Tokens.Colour.muted,
            )
        }
    }
}

/**
 * One photograph. A photograph on the phone draws from MediaStore's thumbnail, one only in the library
 * from its own; either way the badge says whether the library holds it yet.
 */
@Composable
private fun TimelineTile(
    tile: Tile,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val frame = stateColour(if (selected) Tokens.Colour.accent else Color.Transparent, "frame:${tile.key}")
    Box(
        Modifier
            .aspectRatio(1f)
            .background(Tokens.Colour.surfaceLift)
            .pressable(onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = if (tile.backedUp) "Photograph, backed up" else "Photograph, on this phone only"
            },
    ) {
        val model: Any? =
            tile.phone?.let { RollThumb(it.uri, it.video) } ?: tile.item?.thumbUrl
        if (model != null) {
            AsyncImage(
                model =
                    ImageRequest.Builder(LocalPlatformContext.current)
                        .data(model)
                        .crossfade(Tokens.Motion.medium)
                        .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Badge(
            if (tile.backedUp) Icons.CloudCheck else Icons.CloudUpload,
            Modifier.align(Alignment.TopEnd).padding(4.dp),
        )
        if (tile.video) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Badge(Icons.Play)
                duration(tile)?.let {
                    BasicText(it, style = captionStyle.copy(color = Tokens.Colour.ink), modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        if (selecting) {
            Icon(
                if (selected) Icons.CircleCheck else Icons.Circle,
                null,
                tint = if (selected) Tokens.Colour.accent else Tokens.Colour.ink,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            )
        }
        Box(Modifier.fillMaxSize().border(3.dp, frame))
    }
}

/** A small glyph on a photograph, on a dark disc so it reads on a bright one. */
@Composable
private fun Badge(
    vector: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.size(22.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.graphics.vector.rememberVectorPainter(vector),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Tokens.Colour.ink),
            modifier = Modifier.size(14.dp),
        )
    }
}

private fun duration(tile: Tile): String? {
    val ms = tile.phone?.durationMs ?: tile.item?.durationMs?.toLong() ?: return null
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * The handle on the right edge that scrolls by month. Years of photographs are a long way down, and a
 * thumb dragging this lands on the month it names rather than wherever momentum left it.
 */
@Composable
private fun MonthHandle(
    timeline: Timeline,
    grid: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val months = timeline.months
    if (months.size < 3) return
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf<Month?>(null) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val total = timeline.cells.size.coerceAtLeast(1)
    val fraction = dragging?.let { dragFraction } ?: (grid.firstVisibleItemIndex.toFloat() / total)

    BoxWithConstraints(modifier.fillMaxHeight(), contentAlignment = Alignment.TopEnd) {
        val travel = constraints.maxHeight - with(LocalDensity.current) { HANDLE_HEIGHT.toPx() }
        // A strip narrower than a tile's width, so the right-hand column stays pressable.
        Box(
            Modifier
                .fillMaxHeight()
                .width(24.dp)
                .pointerInput(months) {
                    detectVerticalDragGestures(
                        onDragStart = { at ->
                            dragFraction = (at.y / size.height).coerceIn(0f, 1f)
                            dragging = monthAt(months, dragFraction, total)
                        },
                        onVerticalDrag = { change, _ ->
                            dragFraction = (change.position.y / size.height).coerceIn(0f, 1f)
                            val month = monthAt(months, dragFraction, total)
                            if (month != dragging) {
                                dragging = month
                                scope.launch { grid.scrollToItem(month.cell) }
                            }
                        },
                        onDragEnd = { dragging = null },
                        onDragCancel = { dragging = null },
                    )
                },
        )
        Box(
            Modifier
                .offset { IntOffset(0, (travel * fraction).toInt().coerceAtLeast(0)) }
                .padding(end = 4.dp)
                .size(width = 6.dp, height = HANDLE_HEIGHT)
                .background(if (dragging != null) Tokens.Colour.accent else Tokens.Colour.muted, RoundedCornerShape(3.dp)),
        )
        dragging?.let { month ->
            BasicText(
                month.label,
                style = bodyStyle,
                modifier =
                    Modifier
                        .offset { IntOffset(-with(this) { 180.dp.roundToPx() }, (travel * fraction).toInt().coerceAtLeast(0)) }
                        .background(Tokens.Colour.surfaceLift, RoundedCornerShape(Tokens.Radius.card))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

private val HANDLE_HEIGHT = 40.dp

/** The month whose first cell is nearest above a point that far down the whole timeline. */
private fun monthAt(
    months: List<Month>,
    fraction: Float,
    total: Int,
): Month {
    val cell = (fraction * total).toInt()
    return months.lastOrNull { it.cell <= cell } ?: months.first()
}

/** How many rows ahead of the end the next page of the library is asked for. */
private const val LOOKAHEAD_ROWS = 8

@Preview(name = "Photos", widthDp = 360, heightDp = 720)
@Composable
private fun PhotosPreview() {
    val roll =
        (0 until 14).map {
            RollPhoto(
                uri = "content://media/external/images/media/$it",
                video = it == 3,
                takenAt = 1_758_900_000_000L - it * 18_000_000L,
                size = 1000L + it,
                dateModified = 0,
                width = 4000,
                height = 3000,
                durationMs = if (it == 3) 12_000 else null,
            )
        }
    PhotosScreen(
        Screen.Photos(roll = roll, phoneAccess = true, timeline = buildTimeline(roll, emptyList(), true)),
        previewModel(),
    )
}

@Preview(name = "Photos: selecting", widthDp = 360, heightDp = 720)
@Composable
private fun SelectingPreview() {
    val roll =
        (0 until 8).map {
            RollPhoto("content://media/external/images/media/$it", false, 1_758_900_000_000L - it * 3_600_000L, 1000, 0, 4000, 3000)
        }
    val timeline = buildTimeline(roll, emptyList(), true)
    PhotosScreen(
        Screen.Photos(roll = roll, phoneAccess = true, timeline = timeline, selected = timeline.tiles.take(3).map { it.key }.toSet()),
        previewModel(),
    )
}

@Preview(name = "Photos: library only", widthDp = 360, heightDp = 720)
@Composable
private fun LibraryOnlyPreview() = PhotosScreen(Screen.Photos(), previewModel())
