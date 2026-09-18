package com.mantel.app.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * The creator's furniture, in Compose, from the same tokens the web client reads (DESIGN.md).
 *
 * The web components are not shared and not imitated line for line: SDD.md 10 says the two clients
 * implement the same inventory, not the same code. What is shared is every value below.
 */
val titleStyle =
    TextStyle(
        color = Tokens.Colour.muted,
        fontSize = Tokens.Type.title,
        letterSpacing = Tokens.Type.titleTrack,
    )

val bodyStyle = TextStyle(color = Tokens.Colour.ink, fontSize = Tokens.Type.body)

val captionStyle = TextStyle(color = Tokens.Colour.muted, fontSize = Tokens.Type.caption)

val failStyle = TextStyle(color = Tokens.Colour.fail, fontSize = Tokens.Type.caption)

/** The page. One mode, near-black, and the content centred on a phone-width column. */
@Composable
fun Page(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Tokens.Colour.surface)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
            content = content,
        )
    }
}

/** A title page, not a masthead: small, tracked, upper case (DESIGN.md). */
@Composable
fun Title(
    text: String,
    modifier: Modifier = Modifier,
) = BasicText(text.uppercase(), modifier, style = titleStyle.copy(textAlign = TextAlign.Center))

@Composable
fun Body(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = bodyStyle,
) = BasicText(text, modifier, style = style)

/** The creator is a desk with a lamp on it: one shade lighter than the viewer (DESIGN.md). */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Tokens.Colour.surfaceLift, RoundedCornerShape(Tokens.Radius.card))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: () -> Unit = {},
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(label, style = captionStyle)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = bodyStyle,
            cursorBrush = SolidColor(Tokens.Colour.ink),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }, onGo = { onSubmit() }),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Tokens.Colour.line, RoundedCornerShape(Tokens.Radius.card))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}

/**
 * Storage used against quota. It is in the header because it is noticed there and nowhere else
 * (DESIGN.md), and it is a line rather than a dial because the number is the point.
 */
@Composable
fun Meter(
    used: Long,
    quota: Long,
    modifier: Modifier = Modifier,
    label: String = "${bytes(used)} of ${bytes(quota)} used",
) {
    val fraction = if (quota > 0) (used.toFloat() / quota).coerceIn(0f, 1f) else 0f
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(label, style = captionStyle)
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Tokens.Colour.line),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(2.dp)
                    .background(if (fraction > 0.95f) Tokens.Colour.fail else Tokens.Colour.muted),
            )
        }
    }
}

/** One upload, so that a slow one is distinguishable from a dead one (DESIGN.md). */
@Composable
fun UploadProgress(
    filename: String,
    done: Long,
    total: Long,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(filename, style = captionStyle, maxLines = 1)
        Meter(done, total, label = "${bytes(done)} of ${bytes(total)}")
    }
}

/** Bytes in the units a person reads. A number alone tells nobody anything. */
fun bytes(value: Long): String {
    val gb = value.toDouble() / (1024 * 1024 * 1024)
    if (gb >= 1) return "${oneDecimal(gb)} GB"
    val mb = value.toDouble() / (1024 * 1024)
    if (mb >= 1) return "${oneDecimal(mb)} MB"
    return "${value / 1024} kB"
}

private fun oneDecimal(value: Double): String {
    val rounded = Math.round(value * 10) / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

@Composable
fun Button(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    quiet: Boolean = false,
) {
    val background = if (quiet) Tokens.Colour.surface else Tokens.Colour.ink
    val ink = if (quiet) Tokens.Colour.ink else Tokens.Colour.surface
    Box(
        modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(Tokens.Radius.card))
            .border(1.dp, if (quiet) Tokens.Colour.line else background, RoundedCornerShape(Tokens.Radius.card))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            style = bodyStyle.copy(color = if (enabled) ink else Tokens.Colour.muted),
        )
    }
}

/**
 * A choice between a few named things, in one row. It is not a dropdown: four options that fit on
 * the screen are four options, and hiding them behind a control is a tap nobody needed.
 */
@Composable
fun <T> Choices(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (label, value) ->
            val chosen = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(
                        if (chosen) Tokens.Colour.ink else Tokens.Colour.surface,
                        RoundedCornerShape(Tokens.Radius.card),
                    )
                    .border(1.dp, Tokens.Colour.line, RoundedCornerShape(Tokens.Radius.card))
                    .clickable { onSelect(value) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    label,
                    style = captionStyle.copy(color = if (chosen) Tokens.Colour.surface else Tokens.Colour.muted),
                )
            }
        }
    }
}

/** Two controls side by side, when neither is the lesser of the two. */
@Composable
fun ButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) = Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)

// --- previews -------------------------------------------------------------------------------
//
// Every one of these draws in the IDE with no device and no server. They exist because the states
// that are hardest to reach by hand — a full quota, a failed upload, a disabled control — are the
// ones most likely to be wrong, and a preview reaches them in a keystroke.

@Preview(name = "Type", widthDp = 360)
@Composable
private fun TypePreview() =
    Page {
        Title("Cornwall, August")
        Body("The body size, which carries everything a person reads.")
        Body("A caption, a state, an address.", style = captionStyle)
        Body("What went wrong, and only that.", style = failStyle)
    }

@Preview(name = "Buttons", widthDp = 360)
@Composable
private fun ButtonsPreview() =
    Page {
        Button(text = "Create", onClick = {})
        Button(text = "Continue with GitHub", onClick = {}, quiet = true)
        Button(text = "Sending…", onClick = {}, enabled = false)
    }

@Preview(name = "Field and card", widthDp = 360)
@Composable
private fun FieldPreview() =
    Page {
        Card {
            Field(value = "", onValueChange = {}, label = "Server")
            Field(value = "nate@example.com", onValueChange = {}, label = "Email")
            Field(value = "locked", onValueChange = {}, label = "While busy", enabled = false)
        }
    }

@Preview(name = "Meter", widthDp = 360)
@Composable
private fun MeterPreview() =
    Page {
        Card {
            Meter(used = 0, quota = 10L * 1024 * 1024 * 1024)
            Meter(used = 3L * 1024 * 1024 * 1024, quota = 10L * 1024 * 1024 * 1024)
            Meter(used = 10L * 1024 * 1024 * 1024, quota = 10L * 1024 * 1024 * 1024)
        }
    }

@Preview(name = "Upload progress", widthDp = 360)
@Composable
private fun UploadProgressPreview() =
    Page {
        Card {
            UploadProgress("09-panorama.jpg", done = 0, total = 24L * 1024 * 1024)
            UploadProgress("09-panorama.jpg", done = 9L * 1024 * 1024, total = 24L * 1024 * 1024)
        }
    }
