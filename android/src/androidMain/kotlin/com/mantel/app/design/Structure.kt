package com.mantel.app.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mantel.app.Section

/**
 * The app's structure, as gauntlet run C left it (DESIGN.md): one large title per screen, flat rows
 * with a hairline between them, one accent for structure and state, and Lucide icons at the design's
 * stroke. The recipient's viewer has none of this; it is the phone app's alone.
 */
val screenTitleStyle =
    TextStyle(
        color = Tokens.Colour.ink,
        fontSize = Tokens.Type.screenTitle,
        fontWeight = FontWeight(Tokens.Type.screenTitleWeight),
    )

val rowStyle =
    TextStyle(
        color = Tokens.Colour.ink,
        fontSize = Tokens.Type.row,
        fontWeight = FontWeight(Tokens.Type.rowWeight),
    )

/** A glyph, tinted where it is drawn. It says what it is to a screen reader unless it is decoration. */
@Composable
fun Icon(
    vector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Tokens.Colour.ink,
) {
    Image(
        painter = rememberVectorPainter(vector),
        contentDescription = contentDescription,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(Tokens.Icon.size),
    )
}

/** An icon a person can press. The glyph is 24dp; what the finger has to hit is 48. */
@Composable
fun IconButton(
    vector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Tokens.Colour.ink,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(48.dp)
            .pressable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(vector, null, tint = if (enabled) tint else Tokens.Colour.muted)
    }
}

/**
 * The top of a screen: a back control when the screen was visited, then the title, large and bold,
 * with the screen's own actions at the right. A person always knows where they are.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    /** False inside a screen that already sits on the page margin. */
    onMargin: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth().then(if (onMargin) Modifier.padding(horizontal = Tokens.Space.page) else Modifier)) {
        if (onBack != null) {
            Box(Modifier.padding(top = 4.dp).height(48.dp)) {
                IconButton(
                    Icons.ChevronLeft,
                    "Back",
                    onBack,
                    // The glyph lines up with the title under it, not the target round it.
                    Modifier.offset(x = -EDGE),
                    tint = Tokens.Colour.muted,
                )
            }
        } else {
            Spacer(Modifier.height(Tokens.Space.gutter * 2))
        }
        Row(
            Modifier.fillMaxWidth().height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                title,
                style = screenTitleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // The last action's glyph sits on the page margin, like the title's first letter.
            Row(Modifier.offset(x = EDGE), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** A 48dp target is wider than its glyph, so an edge control is pulled out by the difference. */
private val EDGE = 12.dp

/**
 * One row of a list: flat on the page, a hairline under it. The first line is the thing; the second
 * says what state it is in. It is not a card: a list of cards is a list of boxes (run C).
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    detailStyle: TextStyle = captionStyle,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {
        if (onClick != null) Icon(Icons.ChevronRight, null, tint = Tokens.Colour.muted)
    },
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
                .padding(horizontal = Tokens.Space.page, vertical = Tokens.Space.rowY),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                BasicText(title, style = rowStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) BasicText(detail, style = detailStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            trailing()
        }
        Hairline(Modifier.padding(start = Tokens.Space.page))
    }
}

/** The one-pixel line, between rows and under a heading. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Tokens.Colour.line),
    )
}

/**
 * The three sections at the foot of the app. The current one is the accent; the others are muted.
 * Each is an icon with its word under it, because an icon alone is a guess.
 */
@Composable
fun NavBar(
    current: Section,
    onSelect: (Section) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(Tokens.Colour.surface)) {
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(Tokens.Layout.barHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Section.entries.forEach { section ->
                val chosen = section == current
                val colour = stateColour(if (chosen) Tokens.Colour.accent else Tokens.Colour.muted, "nav:$section")
                Column(
                    Modifier
                        .weight(1f)
                        .height(Tokens.Layout.barHeight)
                        .pressable(enabled = !chosen) { onSelect(section) }
                        .semantics(mergeDescendants = true) {
                            role = Role.Tab
                            selected = chosen
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    Icon(section.icon, null, tint = colour)
                    BasicText(section.label, style = captionStyle.copy(color = colour))
                }
            }
        }
    }
}

private val Section.icon: ImageVector
    get() =
        when (this) {
            Section.PHOTOS -> Icons.Images
            Section.ALBUMS -> Icons.Album
            Section.SHARED -> Icons.Link2
        }

private val Section.label: String
    get() =
        when (this) {
            Section.PHOTOS -> "Photos"
            Section.ALBUMS -> "Albums"
            Section.SHARED -> "Shared"
        }

// --- previews -------------------------------------------------------------------------------

@Preview(name = "Header and rows", widthDp = 360)
@Composable
private fun StructurePreview() =
    Column(Modifier.background(Tokens.Colour.surface)) {
        ScreenHeader("Albums") { IconButton(Icons.Plus, "New album", {}) }
        ListRow("Cornwall", detail = "13 items · 24 MB · published", onClick = {})
        ListRow("Kitchen, before", detail = "2 items · 4 MB · draft", onClick = {})
        Spacer(Modifier.height(24.dp))
        NavBar(Section.ALBUMS, {})
    }

@Preview(name = "A visited screen", widthDp = 360)
@Composable
private fun VisitedPreview() =
    Column(Modifier.background(Tokens.Colour.surface)) {
        ScreenHeader("Trash", onBack = {})
        ListRow("Backup", detail = "On · wi-fi only", onClick = {})
    }
