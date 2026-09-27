package com.mantel.app.timeline

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.mantel.app.AppModel
import com.mantel.app.Screen
import com.mantel.app.design.Body
import com.mantel.app.design.IconButton
import com.mantel.app.design.Icons
import com.mantel.app.design.Tokens
import com.mantel.app.design.bodyStyle
import com.mantel.app.design.bytes
import com.mantel.app.design.captionStyle
import com.mantel.app.design.rowStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.saket.telephoto.flick.FlickToDismiss
import me.saket.telephoto.flick.FlickToDismissState
import me.saket.telephoto.flick.rememberFlickToDismissState
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The scopes a photograph needs to grow from its tile into the viewer and shrink back: the grid's
 * tile and the viewer's page name the same key, and Compose moves one into the other.
 */
@kotlin.OptIn(ExperimentalSharedTransitionApi::class)
class ViewerTransition(
    val shared: SharedTransitionScope,
    val visibility: AnimatedVisibilityScope,
)

val LocalViewerTransition = compositionLocalOf<ViewerTransition?> { null }

/** The tile's image, marked as the same thing as the viewer's, so the one grows into the other. */
@kotlin.OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.growsInto(key: String): Modifier {
    val transition = LocalViewerTransition.current ?: return this
    return with(transition.shared) {
        this@growsInto.sharedElement(rememberSharedContentState(key), transition.visibility)
    }
}

/**
 * One photograph at full screen, from Photos: the timeline's tiles, with Share, Add to album, Info
 * and Delete.
 */
@Composable
fun Viewer(
    state: Screen.Photos,
    model: AppModel,
) = MediaViewer(
    tiles = state.timeline.tiles,
    viewing = state.viewing,
    chrome = state.chrome,
    onMoved = model::viewerMoved,
    onClose = model::closeViewer,
    onTap = model::toggleChrome,
    onBack = { if (state.showingInfo) model.toggleInfo() else model.closeViewer() },
) { tile -> Chrome(state, model, tile) }

/**
 * Photographs at full screen, whichever screen opened them. Swipe moves on, pinch and double-tap
 * zoom, swipe down closes; a video plays with a scrubber. A photograph on the phone opens from the
 * phone, at full quality and at once; one only in the library opens from its display WebP. The
 * screen that opened it supplies the controls.
 */
@Composable
fun MediaViewer(
    tiles: List<Tile>,
    viewing: String?,
    chrome: Boolean,
    onMoved: (String) -> Unit,
    onClose: () -> Unit,
    onTap: () -> Unit,
    onBack: () -> Unit,
    controls: @Composable (Tile?) -> Unit,
) {
    val start = tiles.indexOfFirst { it.key == viewing }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { tiles.size }
    val flick = rememberFlickToDismissState(dismissThresholdRatio = 0.15f)

    BackHandler(onBack = onBack)

    // A photograph taken away moves the viewer to the one that took its place.
    LaunchedEffect(viewing, tiles) {
        val index = tiles.indexOfFirst { it.key == viewing }
        if (index >= 0 && index != pager.currentPage) pager.scrollToPage(index)
    }
    LaunchedEffect(pager.settledPage) {
        tiles.getOrNull(pager.settledPage)?.let { if (it.key != viewing) onMoved(it.key) }
    }
    LaunchedEffect(flick.gestureState) {
        if (flick.gestureState is FlickToDismissState.GestureState.Dismissing) onClose()
    }

    val dim = (1f - flick.offsetFraction * 2).coerceIn(0f, 1f)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim))) {
        HorizontalPager(
            state = pager,
            key = { tiles.getOrNull(it)?.key ?: it },
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val tile = tiles.getOrNull(page) ?: return@HorizontalPager
            val current = page == pager.currentPage
            FlickToDismiss(state = flick) {
                // Only the page on screen grows from its tile. The pager composes its neighbours too,
                // and each would otherwise leave its own tile at the same moment.
                if (tile.video) {
                    VideoPage(tile, grows = current, playing = current && pager.settledPage == page, onTap = onTap)
                } else {
                    PhotoPage(tile, grows = current, onTap = onTap)
                }
            }
        }

        AnimatedVisibility(
            visible = chrome && flick.offsetFraction == 0f,
            enter = fadeIn(tween(Tokens.Motion.fast)),
            exit = fadeOut(tween(Tokens.Motion.fast)),
            modifier = Modifier.fillMaxSize(),
        ) {
            controls(tiles.getOrNull(pager.currentPage))
        }
    }
}

@Composable
private fun PhotoPage(
    tile: Tile,
    grows: Boolean,
    onTap: () -> Unit,
) {
    val context = LocalPlatformContext.current
    val source: Any? = tile.phone?.uri?.let(Uri::parse) ?: tile.item?.displayUrl ?: tile.item?.thumbUrl
    // What the grid already drew stands in until the full photograph arrives, so nothing flashes.
    val placeholder = tile.phone?.let { "roll:${it.uri}" } ?: tile.item?.thumbUrl
    ZoomableAsyncImage(
        model =
            ImageRequest.Builder(context)
                .data(source)
                .placeholderMemoryCacheKey(placeholder)
                .build(),
        contentDescription = "Photograph",
        modifier = Modifier.fillMaxSize().then(if (grows) Modifier.growsInto(tile.key) else Modifier),
        contentScale = ContentScale.Fit,
        onClick = { onTap() },
    )
}

/**
 * A video: its poster until it plays, then Media3, with a scrubber that seeks where the finger is. It
 * plays only while it is the page on screen, and its player goes when the page does.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoPage(
    tile: Tile,
    grows: Boolean,
    playing: Boolean,
    onTap: () -> Unit,
) {
    val source = tile.phone?.uri ?: tile.item?.originalUrl
    val poster: Any? = tile.phone?.let { RollThumb(it.uri, video = true) } ?: tile.item?.displayUrl ?: tile.item?.thumbUrl
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = poster,
            contentDescription = "Video",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().then(if (grows) Modifier.growsInto(tile.key) else Modifier),
        )
        if (playing && source != null) Player(source)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Player(source: String) {
    val context = LocalContext.current
    val player =
        remember(source) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(source))
                repeatMode = Player.REPEAT_MODE_OFF
                prepare()
                playWhenReady = true
            }
        }
    DisposableEffect(player) { onDispose { player.release() } }
    val presentation = rememberPresentationState(player)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        PlayerSurface(
            player = player,
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            modifier = Modifier.resizeWithContentScale(ContentScale.Fit, presentation.videoSizeDp),
        )
        Scrubber(player, Modifier.align(Alignment.BottomCenter))
    }
}

/** Where the video is, and a line to drag to somewhere else in it. A tap on the time plays or pauses. */
@Composable
private fun Scrubber(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
) {
    var position by remember { mutableLongStateOf(0L) }
    var length by remember { mutableLongStateOf(0L) }
    var paused by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(player) {
        while (isActive) {
            if (dragging == null) position = player.currentPosition
            length = player.duration.coerceAtLeast(0)
            paused = !player.isPlaying
            delay(100)
        }
    }
    val fraction = dragging ?: if (length > 0) (position.toFloat() / length).coerceIn(0f, 1f) else 0f

    Row(
        modifier
            .fillMaxWidth()
            .safeDrawingPadding()
            .padding(horizontal = Tokens.Space.page, vertical = 88.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(
            if (paused) Icons.Play else Icons.Pause,
            if (paused) "Play" else "Pause",
            { if (player.isPlaying) player.pause() else player.play() },
        )
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .height(32.dp)
                .pointerInput(length) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                        onHorizontalDrag = { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragging?.let { player.seekTo((it * length).toLong()) }
                            dragging = null
                        },
                        onDragCancel = { dragging = null },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(Tokens.Colour.line))
            Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(Tokens.Colour.accent))
        }
        BasicText(
            clock(if (dragging != null) (fraction * length).toLong() else position),
            style = captionStyle.copy(color = Tokens.Colour.ink),
        )
    }
}

private fun clock(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** Close at the top; Share, Add to album, Info and Delete at the foot. */
@Composable
private fun Chrome(
    state: Screen.Photos,
    model: AppModel,
    tile: Tile?,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            IconButton(Icons.X, "Close", model::closeViewer)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.showingInfo && tile != null) Info(tile, Modifier.align(Alignment.BottomCenter))
        }
        state.note?.let {
            Body(it, style = captionStyle.copy(color = Tokens.Colour.ink), modifier = Modifier.padding(horizontal = Tokens.Space.page))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            IconButton(Icons.Share2, "Share", model::shareViewed)
            IconButton(Icons.FolderPlus, "Add to album", model::addViewedToAlbum)
            IconButton(
                Icons.Info,
                "Info",
                model::toggleInfo,
                tint = if (state.showingInfo) Tokens.Colour.accent else Tokens.Colour.ink,
            )
            IconButton(Icons.Trash2, "Delete", model::deleteViewed, enabled = !state.busy)
        }
    }
}

private val TAKEN = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm")

/** When it was taken, its size, and where it lives: on this phone, in the library, or both. */
@Composable
fun Info(
    tile: Tile,
    modifier: Modifier = Modifier,
) {
    val where =
        when {
            tile.phone != null && tile.item != null -> "On this phone and in your library"
            tile.trashed -> "On this phone. Its library copy is in the trash, and the backup will not send it again."
            tile.phone != null -> "Only on this phone. It is not backed up yet."
            else -> "Only in your library"
        }
    val width = tile.phone?.width ?: tile.item?.width
    val height = tile.phone?.height ?: tile.item?.height
    val size = tile.phone?.size ?: tile.item?.byteSize
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(Tokens.Colour.surfaceLift, RoundedCornerShape(Tokens.Radius.card))
            .padding(Tokens.Space.page),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(TAKEN.format(Instant.ofEpochMilli(tile.takenAt).atZone(ZoneId.systemDefault())), style = rowStyle)
        BasicText(where, style = bodyStyle)
        val details =
            listOfNotNull(
                size?.let { bytes(it) },
                if (width != null && height != null && width > 0) "$width × $height" else null,
                tile.item?.filename,
            ).joinToString(" · ")
        if (details.isNotEmpty()) BasicText(details, style = captionStyle)
    }
}

/** Keeps the viewer's scopes available to everything drawn inside the transition. */
@Composable
fun ProvideViewerTransition(
    transition: ViewerTransition,
    content: @Composable () -> Unit,
) = CompositionLocalProvider(LocalViewerTransition provides transition, content = content)
