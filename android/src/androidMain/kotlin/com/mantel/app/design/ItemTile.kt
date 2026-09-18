package com.mantel.app.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mantel.app.api.ItemStatus
import com.mantel.app.api.ItemView
import com.mantel.app.api.state

/**
 * One item in the creator's grid, in whatever state it is in.
 *
 * The creator's grid is square and cropped, unlike the viewer's, because this is a working view of
 * an album rather than the album. Nothing here is what a recipient sees (DESIGN.md).
 *
 * An item that is not ready keeps its place in the order instead of appearing later somewhere else,
 * and one that failed is visible rather than missing.
 */
@Composable
fun ItemTile(
    item: ItemView,
    modifier: Modifier = Modifier,
    isCover: Boolean = false,
) {
    Box(
        modifier
            .aspectRatio(1f)
            .background(Tokens.Colour.surfaceLift)
            .then(if (isCover) Modifier.border(2.dp, Tokens.Colour.ink) else Modifier),
    ) {
        when (item.state) {
            ItemStatus.READY ->
                if (item.thumbUrl != null) {
                    AsyncImage(
                        model = item.thumbUrl,
                        contentDescription = item.caption ?: item.filename,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            ItemStatus.FAILED -> TileNote(item.lastError ?: "Failed", failStyle.color)
            else -> TileNote(waiting(item), Tokens.Colour.muted)
        }

        if (item.kind.equals("video", ignoreCase = true) && item.state == ItemStatus.READY) {
            BasicText(
                "VIDEO",
                style = titleStyle,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
        }
        if (item.caption != null && item.state == ItemStatus.READY) {
            BasicText(
                "•",
                style = captionStyle,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            )
        }
    }
}

private fun waiting(item: ItemView): String =
    when (item.state) {
        ItemStatus.PENDING_UPLOAD -> "Waiting"
        ItemStatus.UPLOADED -> "Queued"
        ItemStatus.PROCESSING -> "Rendering"
        else -> item.status
    }

@Composable
private fun TileNote(
    text: String,
    colour: androidx.compose.ui.graphics.Color,
) {
    Column(
        Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        BasicText(text, style = captionStyle.copy(color = colour), maxLines = 3, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Every state a tile has, side by side. The processing and failed states are as considered as the
 * full one (DESIGN.md), and this is where that is checked.
 */
@Preview(name = "Item states", widthDp = 360)
@Composable
private fun ItemTilePreview() =
    Page {
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.gutterTight)) {
            ItemTile(Samples.ready, Modifier.weight(1f), isCover = true)
            ItemTile(Samples.video, Modifier.weight(1f))
            ItemTile(Samples.processing, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.gutterTight)) {
            ItemTile(Samples.queued, Modifier.weight(1f))
            ItemTile(Samples.failed, Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }
    }
