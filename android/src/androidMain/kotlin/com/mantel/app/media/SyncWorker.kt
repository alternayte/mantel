package com.mantel.app.media

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mantel.app.auth.StoredSettings
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * The backup.
 *
 * It offers the device's new media to the library and nothing else. It is one-way and additive: a
 * photograph deleted from the phone stays in the library, and nothing here can delete from the
 * phone. Turning sync off cancels the work and removes nothing.
 *
 * It does not upload. It finds what is new and hands it to UploadWorker, which is the one thing in
 * the app that moves bytes, so a backup and a batch picked by hand behave identically.
 *
 * It does not move the watermark either. The upload happens after this worker has returned, so a
 * watermark written here would step over photographs whose bytes never left the phone, and a backup
 * that quietly skips photographs is not a backup. UploadWorker writes it when the batch lands.
 */
class SyncWorker(
    private val context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val sync = SyncSettings(context)
        val state = sync.state.first()
        if (!state.enabled) return Result.success()

        // A session is needed to upload anything. Signed out is not a failure worth retrying.
        StoredSettings(context).session.first() ?: return Result.success()

        val found = DeviceMedia.since(context, state.folders, state.watermark)
        if (found.uris.isNotEmpty()) {
            UploadWorker.enqueue(context, albumId = null, uris = found.uris, watermark = found.watermark)
        } else {
            // Nothing found means nothing to wait for, and the watermark is where it already was.
            sync.recordWatermark(found.watermark)
        }

        sync.recordRun(System.currentTimeMillis())
        return Result.success()
    }

    companion object {
        private const val NAME = "sync"

        /**
         * Every six hours, on the network and power the person chose. WorkManager will not run a
         * periodic job more often than fifteen minutes, and a backup is not urgent: what matters is
         * that it happens, not that it happens now.
         */
        fun schedule(
            context: Context,
            state: SyncState,
        ) {
            val manager = WorkManager.getInstance(context)
            if (!state.enabled) {
                manager.cancelUniqueWork(NAME)
                return
            }

            val constraints =
                Constraints.Builder()
                    .setRequiredNetworkType(if (state.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresCharging(state.whileCharging)
                    .build()

            manager.enqueueUniquePeriodicWork(
                NAME,
                // The constraints may have changed, so the existing work is replaced rather than kept.
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build(),
            )
        }

        /** Runs the sweep now, for somebody who has just turned sync on and wants to see it work. */
        fun runNow(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "$NAME:now",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    androidx.work.OneTimeWorkRequestBuilder<SyncWorker>().build(),
                )
        }
    }
}
