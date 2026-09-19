package com.mantel.app.media

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.syncStore: DataStore<Preferences> by preferencesDataStore(name = "sync")

/** What sync is set to, and how far it has got. */
data class SyncState(
    val enabled: Boolean = false,
    val folders: Set<String> = emptySet(),
    val unmeteredOnly: Boolean = true,
    val whileCharging: Boolean = true,
    val watermark: Long = 0,
    val lastRunAt: Long = 0,
)

/**
 * Sync is one-way and additive, so what this holds is small: whether it runs, which folders it
 * offers, when to run, and how far it got.
 *
 * The watermark is the point it reached, not a record of what was uploaded. The server decides what
 * it already holds, by hash, so this never has to remember a file.
 */
class SyncSettings(private val context: Context) {
    private val enabledKey = booleanPreferencesKey("enabled")
    private val foldersKey = stringSetPreferencesKey("folders")
    private val unmeteredKey = booleanPreferencesKey("unmetered_only")
    private val chargingKey = booleanPreferencesKey("while_charging")
    private val watermarkKey = longPreferencesKey("watermark")
    private val lastRunKey = longPreferencesKey("last_run_at")

    val state: Flow<SyncState> =
        context.syncStore.data.map {
            SyncState(
                enabled = it[enabledKey] ?: false,
                folders = it[foldersKey] ?: emptySet(),
                unmeteredOnly = it[unmeteredKey] ?: true,
                whileCharging = it[chargingKey] ?: true,
                watermark = it[watermarkKey] ?: 0,
                lastRunAt = it[lastRunKey] ?: 0,
            )
        }

    /**
     * Turning sync off stops new uploads and removes nothing: not from the library, and not from the
     * phone. Off means off, not undo.
     */
    suspend fun setEnabled(enabled: Boolean) {
        context.syncStore.edit { it[enabledKey] = enabled }
    }

    suspend fun setFolders(folders: Set<String>) {
        context.syncStore.edit { it[foldersKey] = folders }
    }

    suspend fun setUnmeteredOnly(value: Boolean) {
        context.syncStore.edit { it[unmeteredKey] = value }
    }

    suspend fun setWhileCharging(value: Boolean) {
        context.syncStore.edit { it[chargingKey] = value }
    }

    suspend fun recordRun(at: Long) {
        context.syncStore.edit { it[lastRunKey] = at }
    }

    /**
     * Forgets how far the backup has got, so the next sweep offers every photograph in the chosen
     * folders again. It is how somebody recovers from a backup that skipped photographs before the
     * watermark was fixed: the library knows what it already holds by hash, so nothing is sent
     * twice. It is the only thing that writes the watermark backwards.
     */
    suspend fun forgetProgress() {
        context.syncStore.edit { it[watermarkKey] = 0L }
    }

    /**
     * How far the backup has actually got.
     *
     * It only ever moves forward: two batches may land out of order, and the later one must not
     * step the earlier one's photographs over. It is written when a batch lands, never when one is
     * handed to the uploader, because a photograph whose bytes never left the phone is not backed
     * up and must be offered again.
     */
    suspend fun recordWatermark(watermark: Long) {
        context.syncStore.edit {
            it[watermarkKey] = maxOf(it[watermarkKey] ?: 0L, watermark)
        }
    }
}
