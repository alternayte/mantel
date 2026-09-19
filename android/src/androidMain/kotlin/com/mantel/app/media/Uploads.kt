package com.mantel.app.media

import android.content.Context
import android.net.Uri
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** What the upload worker is doing for one album, as the screen needs to know it. */
sealed interface UploadReport {
    data class Running(
        val filename: String,
        val doneBytes: Long,
        val totalBytes: Long,
        val index: Int,
        val count: Int,
    ) : UploadReport

    data class Failed(val message: String) : UploadReport

    /** A batch finished. The album has items it did not have before, so it is worth re-reading. */
    data object Finished : UploadReport
}

/**
 * The upload, as everything above it sees it: start one, and hear how it goes.
 *
 * `AppModel` is tested off a device and WorkManager needs one, which is the whole reason this is an
 * interface. There is one real implementation.
 */
interface Uploads {
    suspend fun enqueue(
        albumId: String?,
        uris: List<Uri>,
    )

    fun reports(albumId: String?): Flow<UploadReport>
}

class WorkManagerUploads(private val context: Context) : Uploads {
    override suspend fun enqueue(
        albumId: String?,
        uris: List<Uri>,
    ) {
        UploadWorker.enqueue(context, albumId, uris)
    }

    override fun reports(albumId: String?): Flow<UploadReport> =
        flow {
            // An album nobody has uploaded to reports nothing. Without this, opening any album
            // reads as a batch that just finished and costs an immediate second read of it.
            var running = false
            WorkManager.getInstance(context)
                .getWorkInfosByTagFlow(UploadWorker.tagFor(albumId))
                .collect { infos ->
                    val report = report(infos) ?: return@collect
                    if (report is UploadReport.Finished && !running) return@collect
                    running = report !is UploadReport.Finished
                    emit(report)
                }
        }

    private fun report(infos: List<WorkInfo>): UploadReport? {
        val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        if (running != null) {
            val data = running.progress
            return UploadReport.Running(
                filename = data.getString(UploadWorker.PROGRESS_FILE).orEmpty(),
                doneBytes = data.getLong(UploadWorker.PROGRESS_DONE, 0),
                totalBytes = data.getLong(UploadWorker.PROGRESS_TOTAL, 0),
                index = data.getInt(UploadWorker.PROGRESS_INDEX, 0),
                count = data.getInt(UploadWorker.PROGRESS_COUNT, 0),
            )
        }
        val failed = infos.firstOrNull { it.state == WorkInfo.State.FAILED }
        if (failed != null) {
            return UploadReport.Failed(failed.outputData.getString(UploadWorker.ERROR) ?: "The upload failed")
        }
        return if (infos.all { it.state.isFinished }) UploadReport.Finished else null
    }
}
