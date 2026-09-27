package com.mantel.app.media

import com.mantel.app.UploadStatus

/** The one thing true about the backup now, as the line beside the avatar says it. */
data class BackupLine(
    val text: String,
    /** Whether something is moving: the line is the accent while it is. */
    val active: Boolean = false,
)

/**
 * The line beside the avatar on Photos. It names one thing, the one that explains what a person is
 * looking at, because a backup that says nothing reads as a backup that is broken.
 *
 * In order: whether it is on at all; what it is doing; what stops it; what it is waiting for; what
 * it has left out; and otherwise that it is up to date. `waiting` is the photographs on the phone
 * the backup has not yet sent, counted against its own watermark.
 */
fun backupLine(
    state: SyncState,
    conditions: Conditions?,
    upload: UploadStatus?,
    waiting: Int,
): BackupLine {
    if (!state.enabled) return BackupLine("Backup off")
    if (upload != null && upload.failed == null && upload.count > 0) {
        return BackupLine("Backing up ${upload.index + 1} of ${upload.count}", active = true)
    }
    if (upload?.failedCode == QUOTA_EXCEEDED) return BackupLine("Library full")
    if (upload?.failed != null) return BackupLine("Backup failed")
    if (waiting > 0) {
        if (state.unmeteredOnly && conditions?.unmetered == false) return BackupLine("Waiting for wi-fi")
        if (state.whileCharging && conditions?.charging == false) return BackupLine("Waiting to charge")
        return BackupLine("$waiting to back up", active = true)
    }
    val tooLarge = state.tooLarge.size
    if (tooLarge == 1) return BackupLine("1 file too large")
    if (tooLarge > 1) return BackupLine("$tooLarge files too large")
    return BackupLine("Up to date")
}

/** The server's code for a batch that does not fit (SDD.md 6.4). */
const val QUOTA_EXCEEDED = "quota_exceeded"
