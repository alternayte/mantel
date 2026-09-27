package com.mantel.app.timeline

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.security.MessageDigest

/**
 * Reads the content hash of every photograph on the phone the index does not yet know, so the
 * timeline can tell a photograph on the phone from the same photograph in the library.
 *
 * Hashing a camera roll reads every byte of it, which costs battery, so this runs only while the
 * phone is charging and stops when it is unplugged. A file is read once, and again only if it
 * changes. A photograph the backup sent never needs this: the upload hashed it and wrote it down.
 */
class HashWorker(
    private val context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val media = DevicePhoneMedia(context)
        if (!media.hasAccess()) return Result.success()
        val index = IndexDatabase.of(context).entries()
        media.roll()
            .filter { it.hash == null }
            .forEach { photo ->
                // Unplugged: the constraint no longer holds, and what is left waits for next time.
                if (isStopped) return Result.success()
                val hash = sha256Of(context, Uri.parse(photo.uri)) ?: return@forEach
                val known = index.byUri(photo.uri)
                index.upsert(
                    RollEntry(
                        uri = photo.uri,
                        size = photo.size,
                        dateModified = photo.dateModified,
                        hash = hash,
                        // Same bytes, same item. Changed bytes are a different photograph.
                        itemId = known?.itemId?.takeIf { known.hash == hash },
                    ),
                )
            }
        return Result.success()
    }

    companion object {
        const val NAME = "hash-camera-roll"

        fun enqueue(context: Context) = WorkManager.getInstance(context).enqueueHashing()
    }
}

/**
 * The SHA-256 of a file, read in blocks rather than into memory: this runs on a phone and the file
 * may be a two gigabyte video. Null when the file cannot be read, which is not worth failing over.
 */
fun sha256Of(
    context: Context,
    uri: Uri,
): String? =
    runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        } ?: return null
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
