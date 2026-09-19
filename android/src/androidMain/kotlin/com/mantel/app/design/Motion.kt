package com.mantel.app.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/**
 * The creator's motion (DESIGN.md).
 *
 * Four uses and no more: a press, a screen replacing another, a thumbnail arriving, and a tile
 * changing state. Motion marks a change of state here and never decorates a static one, so nothing
 * in this file animates on its own.
 */
private val fast = tween<Float>(Tokens.Motion.fast, easing = Tokens.Motion.ease)
private val fastColour = tween<Color>(Tokens.Motion.fast, easing = Tokens.Motion.ease)

/** A press, acknowledged. The target lifts to `surface-lift` and returns when the finger leaves. */
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val dim by animateFloatAsState(
        targetValue = if (pressed && enabled) PRESSED else 1f,
        animationSpec = fast,
        label = "press",
    )
    return this
        .alpha(dim)
        .then(
            Modifier.pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = { offset ->
                        val press = PressInteraction.Press(offset)
                        interactions.tryEmit(press)
                        val released = tryAwaitRelease()
                        interactions.tryEmit(
                            if (released) {
                                PressInteraction.Release(press)
                            } else {
                                PressInteraction.Cancel(press)
                            },
                        )
                    },
                    onTap = { onClick() },
                )
            },
        )
}

/** A colour that changes because something changed state, not because time passed. */
@Composable
fun stateColour(
    target: Color,
    label: String,
): Color {
    val colour by animateColorAsState(targetValue = target, animationSpec = fastColour, label = label)
    return colour
}

/**
 * The switch between the two peers (DESIGN.md). It is the title, in the title's own type, and it is
 * not a bar: there are no icons anywhere in Mantel and these two words do not need the first.
 */
@Composable
fun PeerSwitch(
    onAlbums: () -> Unit,
    onLibrary: () -> Unit,
    current: Peer,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerWord("Albums", current == Peer.ALBUMS, onAlbums)
        BasicText("·", style = titleStyle)
        PeerWord("Library", current == Peer.LIBRARY, onLibrary)
    }
}

enum class Peer { ALBUMS, LIBRARY }

@Composable
private fun PeerWord(
    text: String,
    current: Boolean,
    onClick: () -> Unit,
) {
    val colour = stateColour(if (current) Tokens.Colour.ink else Tokens.Colour.muted, "peer:$text")
    BasicText(
        text.uppercase(),
        style = titleStyle.copy(color = colour),
        modifier = Modifier.pressable(enabled = !current, onClick = onClick),
    )
}

/**
 * Pull to refresh, in type rather than in a spinner.
 *
 * The app holds what it last read and refreshes underneath, so this exists for the person who
 * doubts what they are looking at. It says what it will do and then what it is doing, because a
 * turning circle says neither.
 */
@Composable
fun PullToRefresh(
    refreshing: Boolean,
    pull: Float,
    modifier: Modifier = Modifier,
) {
    val ready = pull >= 1f
    val text =
        when {
            refreshing -> "Refreshing"
            ready -> "Release to refresh"
            else -> "Pull to refresh"
        }
    val shown by animateFloatAsState(
        targetValue = if (refreshing) 1f else pull.coerceIn(0f, 1f),
        animationSpec = fast,
        label = "pull",
    )
    if (shown <= 0f) return
    Box(
        modifier.fillMaxWidth().height((shown * PULL_HEIGHT).dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = captionStyle.copy(color = Tokens.Colour.muted), modifier = Modifier.alpha(shown))
    }
}

private const val PRESSED = 0.55f
private const val PULL_HEIGHT = 28f

@Preview(name = "Peer switch", widthDp = 360)
@Composable
private fun PeerSwitchPreview() =
    Column(
        Modifier.background(Tokens.Colour.surface).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PeerSwitch(onAlbums = {}, onLibrary = {}, current = Peer.ALBUMS)
        PeerSwitch(onAlbums = {}, onLibrary = {}, current = Peer.LIBRARY)
        PullToRefresh(refreshing = false, pull = 0.4f)
        PullToRefresh(refreshing = false, pull = 1f)
        PullToRefresh(refreshing = true, pull = 1f)
    }

/**
 * The gesture behind [PullToRefresh]. It takes the scroll the list did not use, so it only starts
 * when the list is already at its top, and it asks for the refresh on release rather than on
 * distance — a pull that is let go half way is a pull that changed its mind.
 */
class Pull(
    private val onRefresh: () -> Unit,
) : NestedScrollConnection {
    var offset by mutableFloatStateOf(0f)
        private set

    val fraction: Float get() = offset / THRESHOLD

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (available.y <= 0f) return Offset.Zero
        offset = (offset + available.y * RESISTANCE).coerceAtMost(THRESHOLD * 1.5f)
        return Offset(0f, available.y)
    }

    override fun onPreScroll(
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (available.y >= 0f || offset <= 0f) return Offset.Zero
        val taken = minOf(offset, -available.y * RESISTANCE)
        offset -= taken
        return Offset(0f, -taken / RESISTANCE)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (offset >= THRESHOLD) onRefresh()
        offset = 0f
        return Velocity.Zero
    }

    private companion object {
        const val THRESHOLD = 180f
        const val RESISTANCE = 0.5f
    }
}

@Composable
fun rememberPull(onRefresh: () -> Unit): Pull {
    val latest = rememberUpdatedState(onRefresh)
    return remember { Pull { latest.value() } }
}
