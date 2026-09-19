package com.mantel.app.media

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Phone backup, as the screen above it sees it: what it is set to, what it may read, and a nudge to
 * run now. Sync itself is one-way and additive and lives in `SyncWorker`.
 *
 * `AppModel` is tested off a device and every part of this needs one, which is why it is an
 * interface. There is one real implementation.
 */
interface Backup {
    val state: Flow<SyncState>

    suspend fun setEnabled(enabled: Boolean)

    suspend fun setFolders(folders: Set<String>)

    suspend fun setUnmeteredOnly(value: Boolean)

    suspend fun setWhileCharging(value: Boolean)

    /** The folders on this phone. Reading them needs the media permission. */
    suspend fun folders(): List<MediaFolder>

    /** Re-reads the settings and tells the scheduler about them. */
    suspend fun reschedule()

    fun runNow()
}

class PhoneBackup(private val context: Context) : Backup {
    private val settings = SyncSettings(context)

    override val state: Flow<SyncState> = settings.state

    override suspend fun setEnabled(enabled: Boolean) = settings.setEnabled(enabled)

    override suspend fun setFolders(folders: Set<String>) = settings.setFolders(folders)

    override suspend fun setUnmeteredOnly(value: Boolean) = settings.setUnmeteredOnly(value)

    override suspend fun setWhileCharging(value: Boolean) = settings.setWhileCharging(value)

    override suspend fun folders(): List<MediaFolder> = DeviceMedia.folders(context)

    override suspend fun reschedule() = SyncWorker.schedule(context, settings.state.first())

    override fun runNow() = SyncWorker.runNow(context)
}
