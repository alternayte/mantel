package com.mantel.app.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mantel.app.R
import com.mantel.app.api.ApiException
import com.mantel.app.api.MantelApi
import com.mantel.app.api.PresignedPart
import com.mantel.app.auth.StoredSettings
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.flow.first
import java.io.InputStream
import java.util.UUID

/**
 * The upload, off the screen.
 *
 * Forty photographs take longer than a person will hold an app open, so the work belongs to
 * WorkManager and not to a screen. It runs in the foreground with a notification because a data
 * transfer the system may pause at will is a transfer that stops when the phone is pocketed.
 *
 * The bytes go from here to object storage and never through the API (SDD.md 6.3). This worker
 * holds presigned URLs, and the only thing it tells the API is which items arrived.
 */
class UploadWorker(
    private val context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    private val batches = UploadBatches(context)
    private val settings = StoredSettings(context)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Uploading", 0, 0)

    override suspend fun doWork(): Result {
        // A batch with no album is a backup: it lands in the library and is selected later.
        val albumId = inputData.getString(ALBUM_ID)
        val batchId = inputData.getString(BATCH_ID) ?: return Result.failure()
        val serverUrl = settings.serverUrl.first() ?: return Result.failure()
        val session = settings.session.first() ?: return Result.failure()

        val api = MantelApi(serverUrl, session)
        try {
            setForeground(foregroundInfo("Uploading", 0, 0))
            var batch = batches.load(batchId) ?: return Result.failure()
            if (batch.items.any { it.itemId == null }) {
                batch = declare(api, batch)
                batches.save(batchId, batch)
            }

            batch.items.forEachIndexed { index, item ->
                if (item.uploaded) return@forEachIndexed
                report(batch, item.filename, index)
                val uploaded = send(api, item)
                batch = batch.copy(items = batch.items.map { if (it.uri == item.uri) uploaded else it })
                batches.save(batchId, batch)
                report(batch, item.filename, index)
            }

            // One call for the whole batch: forty photographs are one request, not forty.
            val itemIds = batch.items.mapNotNull { it.itemId }
            val fresh = batch.items.filterNot { it.alreadyHeld }.mapNotNull { it.itemId }
            if (fresh.isNotEmpty()) api.completeUploads(fresh)
            // The library holds the media; the album is the selection made from it.
            if (albumId != null && itemIds.isNotEmpty()) api.addToAlbum(albumId, itemIds)
            batches.forget(batchId)
            // Only now is this batch backed up, so only now may the backup step past it. A batch
            // that never gets here is offered again by the next sweep.
            inputData.getLong(WATERMARK, 0).takeIf { it > 0 }?.let {
                SyncSettings(context).recordWatermark(it)
            }
            return Result.success()
        } catch (e: ApiException) {
            // The server refusing is not a network blip: retrying the same bytes gets the same
            // answer, so the batch stays on disk and the failure is reported as it is.
            return Result.failure(workDataOf(ERROR to e.message))
        } catch (e: java.io.IOException) {
            return if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(
                    workDataOf(ERROR to "The upload could not finish"),
                )
            }
        } finally {
            api.close()
        }
    }

    /**
     * Declares the batch and reserves quota. It happens once and is written down, because a second
     * intent would reserve the same bytes again and leave the first set of rows behind.
     */
    private suspend fun declare(
        api: MantelApi,
        batch: UploadBatch,
    ): UploadBatch {
        // Hashed first, so the API can say it already holds a file and no bytes move for it.
        val declared = batch.items.map { it.declared(sha256Of(Uri.parse(it.uri))) }
        val intent = api.uploadIntent(declared)
        return batch.copy(
            items =
                batch.items.mapIndexed { index, item ->
                    val granted = intent.items.getOrNull(index)
                    item.copy(
                        itemId = granted?.itemId,
                        uploadUrl = granted?.uploadUrl,
                        uploadId = granted?.uploadId,
                        alreadyHeld = granted?.alreadyHeld ?: false,
                        uploaded = granted?.alreadyHeld ?: false,
                    )
                },
        )
    }

    /**
     * The SHA-256 of a file, read in blocks rather than into memory: this runs on a phone and the
     * file may be a two gigabyte video.
     */
    private fun sha256Of(uri: Uri): String? =
        runCatching {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            open(uri).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()

    /**
     * One file. A small one is a single PUT; a large one is parts, and on a second attempt the
     * server is asked which parts storage already holds so that only the rest is sent again.
     */
    private suspend fun send(
        api: MantelApi,
        item: BatchItem,
    ): BatchItem {
        val itemId = item.itemId ?: return item
        val uri = Uri.parse(item.uri)

        if (item.uploadId == null) {
            val url = item.uploadUrl ?: return item
            api.putBytes(url, item.contentType, item.sizeBytes) { channel ->
                open(uri).use { it.copyTo(channel, item.sizeBytes) }
            }
            return item.copy(uploaded = true)
        }

        // Storage is asked what already arrived, so only the rest is sent again.
        val progress = api.uploadProgress(itemId = itemId)
        var offset = 0L
        val received = progress.received.associateBy { it.partNumber }
        val remaining = progress.remaining.associateBy { it.partNumber }
        val everyPart = (received.keys + remaining.keys).sorted()

        for (partNumber in everyPart) {
            val size = received[partNumber]?.sizeBytes ?: remaining.getValue(partNumber).sizeBytes
            val part: PresignedPart? = remaining[partNumber]
            if (part != null) {
                val start = offset
                api.putBytes(part.uploadUrl, item.contentType, size) { channel ->
                    open(uri).use {
                        it.skipExactly(start)
                        it.copyTo(channel, size)
                    }
                }
            }
            offset += size
        }
        return item.copy(uploaded = true)
    }

    /**
     * The picker grants read access to the package, not to a screen, so the grant outlives this
     * process. It does not outlive a restart of the device, and a batch resumed after one says so
     * rather than uploading nothing.
     */
    private fun open(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("That file is no longer readable")

    private suspend fun report(
        batch: UploadBatch,
        filename: String,
        index: Int,
    ) {
        setProgress(
            workDataOf(
                PROGRESS_DONE to batch.doneBytes,
                PROGRESS_TOTAL to batch.totalBytes,
                PROGRESS_FILE to filename,
                PROGRESS_INDEX to index,
                PROGRESS_COUNT to batch.items.size,
            ),
        )
        setForeground(foregroundInfo(filename, batch.doneBytes, batch.totalBytes))
    }

    private fun foregroundInfo(
        text: String,
        done: Long,
        total: Long,
    ): ForegroundInfo {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Uploads", NotificationManager.IMPORTANCE_LOW))
        val percent = if (total > 0) ((done * 100) / total).toInt() else 0
        val notification: Notification =
            Notification.Builder(context, CHANNEL)
                .setContentTitle("Mantel")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_upload)
                .setProgress(100, percent, total == 0L)
                .setOngoing(true)
                .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ALBUM_ID = "albumId"
        const val BATCH_ID = "batchId"

        /** How far the backup sweep had read when it handed this batch over. Zero for a hand-picked batch. */
        const val WATERMARK = "watermark"
        const val ERROR = "error"
        const val PROGRESS_DONE = "done"
        const val PROGRESS_TOTAL = "total"
        const val PROGRESS_FILE = "file"
        const val PROGRESS_INDEX = "index"
        const val PROGRESS_COUNT = "count"

        private const val CHANNEL = "uploads"
        private const val NOTIFICATION_ID = 1
        private const val MAX_ATTEMPTS = 5

        fun tagFor(albumId: String?) = "upload:${albumId ?: "library"}"

        /**
         * Queued, not replaced: a second selection while the first is still going is more
         * photographs for the same album, not a change of mind about them.
         */
        suspend fun enqueue(
            context: Context,
            albumId: String?,
            uris: List<Uri>,
            watermark: Long = 0,
        ): Int {
            // The picked files are described and written down here rather than passed to the
            // worker: WorkManager's input data is a few kilobytes, and forty URIs is already most
            // of it. The worker reads the batch, which has no size limit worth naming.
            val items = uris.mapNotNull { describe(context, it) }
            if (items.isEmpty()) return 0
            val batchId = UUID.randomUUID().toString()
            UploadBatches(context).save(batchId, UploadBatch(albumId, items))

            val request =
                OneTimeWorkRequestBuilder<UploadWorker>()
                    .addTag(tagFor(albumId))
                    .setInputData(
                        Data.Builder()
                            .apply { albumId?.let { putString(ALBUM_ID, it) } }
                            .apply { if (watermark > 0) putLong(WATERMARK, watermark) }
                            .putString(BATCH_ID, batchId)
                            .build(),
                    )
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(tagFor(albumId), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            return items.size
        }
    }
}

/** Copies exactly `length` bytes, because a presigned PUT is signed for exactly that many. */
private suspend fun InputStream.copyTo(
    channel: ByteWriteChannel,
    length: Long,
) {
    val buffer = ByteArray(64 * 1024)
    var left = length
    while (left > 0) {
        val read = read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
        if (read <= 0) throw java.io.IOException("The file ended before its declared length")
        channel.writeFully(buffer, 0, read)
        left -= read
    }
    channel.flushAndClose()
}

/** `skip` is allowed to skip less than asked, which on a part boundary would corrupt the upload. */
private fun InputStream.skipExactly(bytes: Long) {
    var left = bytes
    while (left > 0) {
        val skipped = skip(left)
        if (skipped <= 0) throw java.io.IOException("That file is shorter than it was")
        left -= skipped
    }
}
