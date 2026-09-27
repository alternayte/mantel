package com.mantel.app

import com.mantel.app.media.Conditions
import com.mantel.app.media.SyncState
import com.mantel.app.media.backupLine
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The line beside the avatar names the one thing that explains what a person sees. Which thing wins
 * when several are true cannot be checked by hand without arranging all of them on a real phone.
 */
class BackupLineTest {
    private val on = SyncState(enabled = true, unmeteredOnly = true, whileCharging = true)
    private val ready = Conditions(unmetered = true, charging = true)

    @Test
    fun `off says so, whatever else is true`() {
        assertEquals("Backup off", backupLine(SyncState(enabled = false), ready, null, waiting = 5).text)
    }

    @Test
    fun `a running batch wins over what it is waiting for`() {
        val running = UploadStatus("IMG_1.jpg", 10, 100, index = 2, count = 9)
        val line = backupLine(on, Conditions(unmetered = false, charging = false), running, waiting = 5)
        assertEquals("Backing up 3 of 9", line.text)
        assertEquals(true, line.active)
    }

    @Test
    fun `a full library is named, not reported as a failure`() {
        val refused = UploadStatus("", 0, 0, 0, 0, failed = "This batch needs more space", failedCode = "quota_exceeded")
        assertEquals("Library full", backupLine(on, ready, refused, waiting = 5).text)
    }

    @Test
    fun `waiting names the network before the charger, and only when there is something to send`() {
        assertEquals("Waiting for wi-fi", backupLine(on, Conditions(unmetered = false, charging = false), null, waiting = 2).text)
        assertEquals("Waiting to charge", backupLine(on, Conditions(unmetered = true, charging = false), null, waiting = 2).text)
        assertEquals("Up to date", backupLine(on, Conditions(unmetered = false, charging = false), null, waiting = 0).text)
    }

    @Test
    fun `files left out as too large are said once nothing else is`() {
        assertEquals("2 files too large", backupLine(on.copy(tooLarge = setOf("a.mov", "b.mov")), ready, null, waiting = 0).text)
    }
}
