package com.mantel.app.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

    /** Whether the phone is on an unmetered network and on power now: what a backup may be waiting for. */
    val conditions: Flow<Conditions>

    suspend fun setEnabled(enabled: Boolean)

    suspend fun setFolders(folders: Set<String>)

    suspend fun setUnmeteredOnly(value: Boolean)

    suspend fun setWhileCharging(value: Boolean)

    /** The folders on this phone. Reading them needs the media permission. */
    suspend fun folders(): List<MediaFolder>

    /** Forgets how far the backup has got, so the next sweep offers everything again. */
    suspend fun forgetProgress()

    /** Re-reads the settings and tells the scheduler about them. */
    suspend fun reschedule()

    fun runNow()
}

class PhoneBackup(private val context: Context) : Backup {
    private val settings = SyncSettings(context)

    override val state: Flow<SyncState> = settings.state

    override val conditions: Flow<Conditions> =
        combine(unmetered(context), charging(context)) { unmetered, charging -> Conditions(unmetered, charging) }
            .distinctUntilChanged()

    override suspend fun setEnabled(enabled: Boolean) = settings.setEnabled(enabled)

    override suspend fun setFolders(folders: Set<String>) = settings.setFolders(folders)

    override suspend fun setUnmeteredOnly(value: Boolean) = settings.setUnmeteredOnly(value)

    override suspend fun setWhileCharging(value: Boolean) = settings.setWhileCharging(value)

    override suspend fun folders(): List<MediaFolder> = DeviceMedia.folders(context)

    override suspend fun forgetProgress() = settings.forgetProgress()

    override suspend fun reschedule() = SyncWorker.schedule(context, settings.state.first())

    override fun runNow() = SyncWorker.runNow(context)
}

/** What the phone is on right now, as far as a backup's constraints care. */
data class Conditions(val unmetered: Boolean, val charging: Boolean)

/** Whether the default network is one the person does not pay for by the byte. */
private fun unmetered(context: Context): Flow<Boolean> =
    callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)

        fun current(): Boolean =
            manager.getNetworkCapabilities(manager.activeNetwork)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    (
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
                    )
            } ?: false
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    trySend(current())
                }

                override fun onLost(network: Network) {
                    trySend(current())
                }
            }
        trySend(current())
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }

/** Whether the phone is plugged in. The battery broadcast is sticky, so the first answer is immediate. */
private fun charging(context: Context): Flow<Boolean> =
    callbackFlow {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                    trySend(plugged != 0)
                }
            }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { receiver.onReceive(context, it) }
        awaitClose { context.unregisterReceiver(receiver) }
    }
