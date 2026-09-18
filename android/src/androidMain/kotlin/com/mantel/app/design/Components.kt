package com.mantel.app.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
