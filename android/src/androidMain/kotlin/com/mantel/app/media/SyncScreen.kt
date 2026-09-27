package com.mantel.app.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mantel.app.AppModel
import com.mantel.app.Screen
import com.mantel.app.design.Body
import com.mantel.app.design.Hairline
import com.mantel.app.design.ListRow
import com.mantel.app.design.ScreenHeader
import com.mantel.app.design.SectionHeading
import com.mantel.app.design.ToggleRow
import com.mantel.app.design.Tokens
import com.mantel.app.design.captionStyle
import com.mantel.app.design.failStyle
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
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader("Backup", onBack = model::back)

        ToggleRow(
            title = "Back up this phone",
            detail =
                if (state.enabled) {
                    "New photographs and videos in the folders below go to your library. Turning it off removes nothing."
                } else {
                    "Off. Nothing on this phone is being backed up."
                },
            checked = state.enabled,
            onToggle = model::toggleSync,
            enabled = !state.busy,
        )
        if (state.error != null) {
            Body(
                state.error,
                style = failStyle,
                modifier = Modifier.padding(horizontal = Tokens.Space.page, vertical = Tokens.Space.gutter),
            )
        }

        if (state.enabled) {
            SectionHeading("Folders")
            if (state.folders.isEmpty()) {
                Body(
                    "No media folders on this phone yet.",
                    style = captionStyle,
                    modifier = Modifier.padding(horizontal = Tokens.Space.page, vertical = Tokens.Space.gutter),
                )
            }
            state.folders.forEach { folder ->
                ToggleRow(
                    title = folder.name,
                    detail = if (folder.count == 1) "1 item" else "${folder.count} items",
                    checked = folder.id in state.selected,
                    onToggle = { model.toggleFolder(folder.id) },
                )
            }

            SectionHeading("When")
            ToggleRow(
                title = "Only on wi-fi",
                checked = state.unmeteredOnly,
                onToggle = { model.setUnmeteredOnly(!state.unmeteredOnly) },
            )
            ToggleRow(
                title = "Only while charging",
                checked = state.whileCharging,
                onToggle = { model.setWhileCharging(!state.whileCharging) },
            )
            ListRow(
                title = "Back up now",
                detail =
                    if (state.lastRunAt == 0L) {
                        "Not run yet."
                    } else {
                        "Last run ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(state.lastRunAt))}"
                    },
                onClick = if (state.busy) null else model::syncNow,
            )

            // A file the server will not take is left out rather than allowed to stop the backup.
            // It is still not backed up, and that is said here, by name, rather than nowhere.
            if (state.tooLarge.isNotEmpty()) {
                SectionHeading("Not backed up")
                val named = state.tooLarge.sorted()
                Column(
                    Modifier.padding(horizontal = Tokens.Space.page, vertical = Tokens.Space.gutter),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Space.gutter),
                ) {
                    Body(
                        if (state.tooLarge.size == 1) {
                            "One file is larger than your server accepts, so it is not backed up."
                        } else {
                            "${state.tooLarge.size} files are larger than your server accepts, so they are not backed up."
                        },
                        style = failStyle,
                    )
                    Body(
                        named.take(TOO_LARGE_NAMED).joinToString("\n") +
                            if (named.size > TOO_LARGE_NAMED) "\nand ${named.size - TOO_LARGE_NAMED} more" else "",
                        style = captionStyle,
                    )
                }
                Hairline(Modifier.padding(start = Tokens.Space.page))
            }

            SectionHeading("Missing photographs")
            ListRow(
                title = "Back up everything again",
                detail =
                    "Offers every photograph in these folders again, not only the new ones. Your library already " +
                        "knows what it holds, so nothing is sent or charged twice.",
                onClick = if (state.busy) null else model::backUpEverythingAgain,
            )
        }

        Body(
            "Mantel only ever reads. It never deletes anything from this phone, and a photograph you " +
                "delete here stays on the phone.",
            style = captionStyle,
            modifier = Modifier.padding(horizontal = Tokens.Space.page, vertical = 24.dp),
        )
        Spacer(Modifier.height(Tokens.Space.tail))
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
