package com.mantel.app.design

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Drag to reorder, in a grid.
 *
 * Reordering forty photographs is the thing run B measured (BUILD.md 5), and on a phone that means
 * picking a tile up and putting it somewhere. The order moves under the finger as it passes each
 * tile, so the result is visible before the finger lifts; the server hears about it once, on the
 * drop, as the whole order (SDD.md 6.1).
 */
class GridReorder(
    private val state: LazyGridState,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onDrop: () -> Unit,
) {
    var draggingIndex by mutableStateOf<Int?>(null)
        private set

    var pointer by mutableStateOf(Offset.Zero)
        private set

    private fun indexAt(position: Offset): Int? =
        state.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            val x = position.x - item.offset.x
            val y = position.y - item.offset.y
            x >= 0 && y >= 0 && x <= item.size.width && y <= item.size.height
        }?.index

    fun start(position: Offset) {
        pointer = position
        draggingIndex = indexAt(position)
    }

    fun drag(position: Offset) {
        pointer = position
        val from = draggingIndex ?: return
        val to = indexAt(position) ?: return
        if (to != from) {
            onMove(from, to)
            draggingIndex = to
        }
    }

    fun stop() {
        if (draggingIndex != null) onDrop()
        draggingIndex = null
    }

    /** The offset to draw the held tile at, so it follows the finger rather than the layout. */
    fun heldOffset(index: Int): Offset {
        if (draggingIndex != index) return Offset.Zero
        val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return Offset.Zero
        val centre = Offset(item.offset.x + item.size.width / 2f, item.offset.y + item.size.height / 2f)
        return pointer - centre
    }
}

@Composable
fun rememberGridReorder(
    state: LazyGridState,
    onMove: (from: Int, to: Int) -> Unit,
    onDrop: () -> Unit,
): GridReorder = remember(state) { GridReorder(state, onMove, onDrop) }

fun Modifier.reorderable(reorder: GridReorder): Modifier =
    pointerInput(reorder) {
        detectDragGesturesAfterLongPress(
            onDragStart = { reorder.start(it) },
            onDrag = { change, _ ->
                change.consume()
                reorder.drag(change.position)
            },
            onDragEnd = { reorder.stop() },
            onDragCancel = { reorder.stop() },
        )
    }
