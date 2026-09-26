package com.mantel.app.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mantel.app.AppModel
import com.mantel.app.Screen
import com.mantel.app.design.Body
import com.mantel.app.design.Button
import com.mantel.app.design.Card
import com.mantel.app.design.Title
import com.mantel.app.design.Tokens
import com.mantel.app.design.captionStyle
import com.mantel.app.design.failStyle
import com.mantel.app.design.pressable
import com.mantel.app.previewModel
import java.text.DateFormat
import java.util.Date

/**
 * The backup, and what it is allowed to do.
 *
 * Everything on this screen is a promise about restraint: which folders, on what connection, and
 * that nothing here ever deletes. The permission is asked for at the moment sync is switched on,
 * because that is the moment it means something.
 */
@Composable
fun SyncScreen(
    state: Screen.Sync,
    model: AppModel,
) {
    BackHandler(enabled = true) { model.back() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Colour.surface)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
    ) {
        Spacer(Modifier.height(Tokens.Space.titleY))
        Body("Back", style = captionStyle, modifier = Modifier.pressable { model.back() })
        Title("Backup")

        Card {
            Body(
                if (state.enabled) {
                    "New photographs and videos in the folders below go to your library."
                } else {
                    "Off. Nothing on this phone is being backed up."
                },
                style = captionStyle,
            )
            Button(
                text = if (state.enabled) "Turn backup off" else "Turn backup on",
                onClick = model::toggleSync,
                enabled = !state.busy,
                quiet = state.enabled,
            )
            if (state.enabled) {
                Body(
                    "Turning it off stops new uploads. It removes nothing, here or on your phone.",
                    style = captionStyle,
                )
            }
        }

        if (state.error != null) Body(state.error, style = failStyle)

        if (state.enabled) {
            Card {
                Body("Folders", style = captionStyle)
                if (state.folders.isEmpty()) {
                    Body("No media folders on this phone yet.", style = captionStyle)
                }
                state.folders.forEach { folder ->
                    Choice(
                        label = "${folder.name} · ${folder.count}",
                        chosen = folder.id in state.selected,
                        onClick = { model.toggleFolder(folder.id) },
                    )
                }
            }

            Card {
                Body("When", style = captionStyle)
                Choice(
                    label = "Only on wi-fi",
                    chosen = state.unmeteredOnly,
                    onClick = { model.setUnmeteredOnly(!state.unmeteredOnly) },
                )
                Choice(
                    label = "Only while charging",
                    chosen = state.whileCharging,
                    onClick = { model.setWhileCharging(!state.whileCharging) },
                )
                Body(
                    if (state.lastRunAt == 0L) {
                        "Not run yet."
                    } else {
                        "Last run ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(state.lastRunAt))}"
                    },
                    style = captionStyle,
                )
                Button(text = "Back up now", onClick = model::syncNow, enabled = !state.busy, quiet = true)
            }

            // A file the server will not take is left out rather than allowed to stop the backup.
            // It is still not backed up, and that is said here, by name, rather than nowhere.
            if (state.tooLarge.isNotEmpty()) {
                Card {
                    Body(
                        if (state.tooLarge.size == 1) {
                            "One file is larger than your server accepts, so it is not backed up."
                        } else {
                            "${state.tooLarge.size} files are larger than your server accepts, so they are not backed up."
                        },
                        style = failStyle,
                    )
                    val named = state.tooLarge.sorted()
                    Body(
                        named.take(TOO_LARGE_NAMED).joinToString("\n") +
                            if (named.size > TOO_LARGE_NAMED) "\nand ${named.size - TOO_LARGE_NAMED} more" else "",
                        style = captionStyle,
                    )
                }
            }

            Card {
                Body(
                    "If photographs are missing from your library, offer every one in these folders " +
                        "again rather than only the new ones. Your library already knows what it holds, " +
                        "so nothing is sent or charged twice.",
                    style = captionStyle,
                )
                Button(
                    text = "Back up everything again",
                    onClick = model::backUpEverythingAgain,
                    enabled = !state.busy,
                    quiet = true,
                )
            }
        }

        Body(
            "Mantel only ever reads. It never deletes anything from this phone, and a photograph you " +
                "delete here stays in your library until you delete it there.",
            style = captionStyle,
        )
        Spacer(Modifier.height(Tokens.Space.tail))
    }
}

/** A row that is on or off. A checkbox, in the album's own type rather than the system's. */
@Composable
private fun Choice(
    label: String,
    chosen: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().pressable { onClick() },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .background(
                    if (chosen) Tokens.Colour.ink else Tokens.Colour.surface,
                    RoundedCornerShape(3.dp),
                ),
        )
        Body(label)
    }
}

@Preview(name = "Backup: off", widthDp = 360, heightDp = 720)
@Composable
private fun SyncOffPreview() = SyncScreen(Screen.Sync(), previewModel())

@Preview(name = "Backup: on", widthDp = 360, heightDp = 780)
@Composable
private fun SyncOnPreview() =
    SyncScreen(
        Screen.Sync(
            enabled = true,
            folders =
                listOf(
                    MediaFolder("1", "Camera", 4213),
                    MediaFolder("2", "Screenshots", 812),
                    MediaFolder("3", "WhatsApp Images", 2904),
                ),
            selected = setOf("1"),
            lastRunAt = 1_758_000_000_000,
        ),
        previewModel(),
    )

/** How many too-large files the backup screen names before it counts the rest. */
private const val TOO_LARGE_NAMED = 5
