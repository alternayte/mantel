package com.mantel.app.design

import com.mantel.app.api.AlbumSummary
import com.mantel.app.api.AlbumView
import com.mantel.app.api.ItemView
import com.mantel.app.api.Me

/**
 * The data the `@Preview` functions render.
 *
 * A preview draws a composable in the IDE with no device, no server and no session, which is only
 * possible if every screen takes what it draws as an argument. These are the arguments: one of each
 * state that has ever been got wrong, rather than one happy example.
 *
 * A thumbnail URL is deliberately absent. A preview cannot reach the network, and a tile with no
 * image is also what the grid shows for a second before one arrives.
 */
object Samples {
    val me =
        Me(
            email = "nate@example.com",
            displayName = "Nate",
            storageQuotaBytes = 10L * 1024 * 1024 * 1024,
            storageUsedBytes = 3L * 1024 * 1024 * 1024,
        )

    val ready =
        ItemView(
            id = "01",
            position = 0,
            kind = "photo",
            status = "ready",
            byteSize = 4L * 1024 * 1024,
            caption = "low tide, first morning",
            width = 2400,
            height = 1600,
            filename = "01-cafe-table.jpg",
        )

    val video = ready.copy(id = "02", position = 1, kind = "video", caption = null, durationMs = 41_000)

    val processing = ready.copy(id = "03", position = 2, status = "processing", caption = null)

    val queued = ready.copy(id = "04", position = 3, status = "uploaded", caption = null)

    val failed =
        ready.copy(
            id = "05",
            position = 4,
            status = "failed",
            caption = null,
            lastError = "That file is not a photograph this can render",
        )

    val items = listOf(ready, video, processing, queued, failed)

    val album =
        AlbumView(
            id = "a1",
            title = "Cornwall, August",
            description = "Three days on the north coast.",
            status = "draft",
            itemCount = items.size,
            totalBytes = 21L * 1024 * 1024,
            coverItemId = ready.id,
            createdAt = "2026-08-03T09:00:00Z",
            updatedAt = "2026-08-03T09:40:00Z",
            items = items,
        )

    val albums =
        listOf(
            AlbumSummary(
                id = "a1",
                title = "Cornwall, August",
                status = "live",
                itemCount = 40,
                totalBytes = 180L * 1024 * 1024,
                createdAt = "2026-08-03T09:00:00Z",
                updatedAt = "2026-08-03T09:40:00Z",
            ),
            AlbumSummary(
                id = "a2",
                title = "The move",
                status = "draft",
                itemCount = 0,
                totalBytes = 0,
                createdAt = "2026-09-01T09:00:00Z",
                updatedAt = "2026-09-01T09:00:00Z",
            ),
        )
}
