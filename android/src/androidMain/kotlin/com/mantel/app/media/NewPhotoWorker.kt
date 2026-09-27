package com.mantel.app.media

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import java.time.Duration

/**
 * Starts a backup within a minute of a new photograph.
 *
 * A backup that waits six hours for its sweep looks broken to somebody who has just taken a
 * photograph and is watching for it. This work waits on MediaStore itself: it runs when the camera
 * roll changes, hands a sweep to SyncWorker on the network and power the person chose, and waits
 * again. The six-hourly sweep stays, as the safety net for anything this misses.
 */
class NewPhotoWorker(
    private val context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val state = SyncSettings(context).state.first()
        if (!state.enabled) return Result.success()
        sweep(context, state)
        // A content trigger fires once. The next one is queued behind this run, so it waits on the
        // camera roll again the moment this finishes.
        arm(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        private const val NAME = "sync:new-photo"
        private const val SWEEP = "sync:after-new-photo"

        /**
         * A sweep on the network and power chosen now. It replaces one already waiting, so a sweep
         * held for wi-fi goes as soon as somebody says a mobile network will do.
         */
        fun sweep(
            context: Context,
            state: SyncState,
        ) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    SWEEP,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(chosen(state)).build(),
                )
        }

        /** What the backup waits for: the network and the power somebody chose on the backup screen. */
        fun chosen(state: SyncState): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(if (state.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(state.whileCharging)
                .build()

        /** Waits on the camera roll. It runs a few seconds after it changes, and never more than a minute. */
        fun arm(
            context: Context,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
        ) {
            val trigger =
                Constraints.Builder()
                    .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                    .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                    .setTriggerContentUpdateDelay(Duration.ofSeconds(5))
                    .setTriggerContentMaxDelay(Duration.ofMinutes(1))
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(NAME, policy, OneTimeWorkRequestBuilder<NewPhotoWorker>().setConstraints(trigger).build())
        }

        fun disarm(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
            WorkManager.getInstance(context).cancelUniqueWork(SWEEP)
        }
    }
}
