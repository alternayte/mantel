package com.mantel.app.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mantel.app.AppModel
import com.mantel.app.Screen
import com.mantel.app.api.ShareLinkView
import com.mantel.app.design.Body
import com.mantel.app.design.Button
import com.mantel.app.design.ButtonRow
import com.mantel.app.design.Card
import com.mantel.app.design.Choices
import com.mantel.app.design.Field
import com.mantel.app.design.Tokens
import com.mantel.app.design.captionStyle
import com.mantel.app.design.failStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Sharing an album: making a link, and taking one away.
 *
 * One album can carry several links, each with its own PIN and its own expiry, because sending an
 * album to a family group and to one person are different acts with different risks (SDD.md 4.3).
 * The PIN is a second factor precisely because the token leaks with the URL.
 */
@Composable
fun ShareSheet(
    state: Screen.Album,
    model: AppModel,
) {
    var confirming by remember { mutableStateOf<String?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
        ) {
            state.links.forEach { link ->
                Card {
                    Body(link.url, style = captionStyle)
                    Body(describe(link), style = if (link.live) captionStyle else failStyle)
                    if (link.live) {
                        ButtonRow {
                            Button(
                                text = "Send",
                                onClick = { model.shareUrl(link.url) },
                                modifier = Modifier.weight(1f),
                                enabled = !state.busy,
                            )
                            // Revocation is immediate and total, so it takes two taps and the
                            // second one says what it does.
                            Button(
                                text = if (confirming == link.id) "Revoke, for good" else "Revoke",
                                onClick = {
                                    if (confirming == link.id) {
                                        model.revokeShareLink(link.id)
                                        confirming = null
                                    } else {
                                        confirming = link.id
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !state.busy,
                                quiet = true,
                            )
                        }
                    }
                }
            }

            Card {
                Body(
                    if (state.links.isEmpty()) {
                        "Nobody can see this album yet. A link is how it is shared, and it is the only way in."
                    } else {
                        "Another link, with its own PIN and its own expiry."
                    },
                    style = captionStyle,
                )
                Field(
                    value = state.pin,
                    onValueChange = model::setPin,
                    label = "PIN, if you want one",
                    keyboardType = KeyboardType.NumberPassword,
                    enabled = !state.busy,
                )
                Body("Expires", style = captionStyle)
                Choices(
                    options = listOf("Never" to null, "7 days" to 7, "30 days" to 30, "90 days" to 90),
                    selected = state.expiresInDays,
                    onSelect = model::setExpiry,
                )
                Button(
                    text = if (state.links.isEmpty()) "Publish" else "Make another link",
                    onClick = model::createShareLink,
                    enabled = !state.busy,
                )
            }

            if (state.error != null) Body(state.error, style = failStyle)
            Button(text = "Done", onClick = model::closeSharing, quiet = true)
        }
    }
}

/** What a link is, in one line: protected, dated, or gone. */
fun describe(
    link: ShareLinkView,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (link.revokedAt != null) return "Revoked. This link is gone."
    if (!link.live) return "Expired."
    val pin = if (link.hasPin) "PIN" else "No PIN"
    val expiry = link.expiresAt?.let { "expires ${DATE.withZone(zone).format(Instant.parse(it))}" } ?: "never expires"
    return "$pin · $expiry"
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy")
