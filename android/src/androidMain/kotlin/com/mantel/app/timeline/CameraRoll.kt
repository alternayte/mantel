package com.mantel.app.timeline

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mantel.app.media.DeviceMedia
import com.mantel.app.media.SyncSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** One photograph or video on the phone, as the timeline needs it. */
data class RollPhoto(
    /** The MediaStore content URI: what opens it, what the index keys on, what a backup sends. */
    val uri: String,
    val video: Boolean,
    /** When it was taken, in epoch milliseconds: `DATE_TAKEN`, or when it arrived if the file says nothing. */
    val takenAt: Long,
    val size: Long,
    val dateModified: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long? = null,
    /** Its SHA-256, if the index holds one for the file as it is now. */
    val hash: String? = null,
    /** When it arrived on the phone, in epoch seconds: what the backup's watermark is measured in. */
    val added: Long = 0,
)

/**
 * The phone's own photographs. `AppModel` is tested off a device and every part of this needs one,
 * which is the only reason it is an interface; there is one real implementation.
 */
interface PhoneMedia {
    /** Whether the app may read the phone's photographs at all. */
    fun hasAccess(): Boolean

    /** The camera roll, newest first, each with the hash the index holds for it. */
    suspend fun roll(): List<RollPhoto>

    /** Hashes what the index lacks, the next time the phone is charging. */
    fun hashWhenCharging()

    /** Fires when the camera roll changes: a photograph taken, a file arriving. */
    fun changes(): Flow<Unit>
}

class DevicePhoneMedia(private val context: Context) : PhoneMedia {
    private val index = IndexDatabase.of(context).entries()

    override fun hasAccess(): Boolean =
        DeviceMedia.permissions().any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    override suspend fun roll(): List<RollPhoto> =
        withContext(Dispatchers.IO) {
            if (!hasAccess()) return@withContext emptyList()
            val photos = CameraRoll.read(context, CameraRoll.folders(context))
            val known = index.all().associateBy { it.uri }
            // A row for a file that is gone is forgotten, in chunks the database will take.
            val present = photos.mapTo(HashSet()) { it.uri }
            known.keys.filterNot { it in present }.chunked(500).forEach { index.forget(it) }
            photos.map { photo ->
                val entry = known[photo.uri]
                val current = entry != null && entry.size == photo.size && entry.dateModified == photo.dateModified
                if (current) photo.copy(hash = entry?.hash) else photo
            }
        }

    override fun hashWhenCharging() = HashWorker.enqueue(context)

    override fun changes(): Flow<Unit> =
        callbackFlow {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(Unit)
                    }
                }
            val resolver = context.contentResolver
            resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
            awaitClose { resolver.unregisterContentObserver(observer) }
        }
}

object CameraRoll {
    private val COLLECTIONS =
        listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true,
        )

    /**
     * The folders the timeline shows: the ones the backup is set to, because those are the
     * photographs this person keeps, or the camera's own folder when the backup has not been set.
     */
    suspend fun folders(context: Context): Set<String> {
        val chosen = SyncSettings(context).state.first().folders
        if (chosen.isNotEmpty()) return chosen
        return DeviceMedia.folders(context).filter { it.name == DeviceMedia.CAMERA }.map { it.id }.toSet()
    }

    /** Every photograph and video in the folders, newest first. A pending file is not one yet. */
    fun read(
        context: Context,
        folders: Set<String>,
    ): List<RollPhoto> {
        if (folders.isEmpty()) return emptyList()
        val found = ArrayList<RollPhoto>()
        COLLECTIONS.forEach { (collection, video) ->
            val columns =
                buildList {
                    add(MediaStore.MediaColumns._ID)
                    add(MediaStore.Images.ImageColumns.DATE_TAKEN)
                    add(MediaStore.MediaColumns.DATE_ADDED)
                    add(MediaStore.MediaColumns.DATE_MODIFIED)
                    add(MediaStore.MediaColumns.SIZE)
                    add(MediaStore.MediaColumns.WIDTH)
                    add(MediaStore.MediaColumns.HEIGHT)
                    if (video) add(MediaStore.Video.VideoColumns.DURATION)
                }
            var selection = "${MediaStore.MediaColumns.BUCKET_ID} IN (${folders.joinToString(",") { "?" }})"
            // MediaStore hides a file still being written from other apps, and so does this.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) selection += " AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
            context.contentResolver.query(collection, columns.toTypedArray(), selection, folders.toTypedArray(), null)
                ?.use { cursor ->
                    val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val taken = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.DATE_TAKEN)
                    val added = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val modified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val width = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                    val height = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                    val duration = if (video) cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION) else -1
                    while (cursor.moveToNext()) {
                        val takenAt =
                            cursor.getLong(taken).takeIf { it > 0 } ?: (cursor.getLong(added) * 1000)
                        found +=
                            RollPhoto(
                                uri = ContentUris.withAppendedId(collection, cursor.getLong(id)).toString(),
                                video = video,
                                takenAt = takenAt,
                                size = cursor.getLong(size),
                                dateModified = cursor.getLong(modified),
                                width = cursor.getInt(width),
                                height = cursor.getInt(height),
                                durationMs = if (video) cursor.getLong(duration) else null,
                                added = cursor.getLong(added),
                            )
                    }
                }
        }
        found.sortByDescending { it.takenAt }
        return found
    }
}

/** The hashing, scheduled for when the phone is charging and never otherwise. */
internal fun charging(): Constraints = Constraints.Builder().setRequiresCharging(true).build()

internal fun WorkManager.enqueueHashing() {
    enqueueUniqueWork(
        HashWorker.NAME,
        ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<HashWorker>().setConstraints(charging()).build(),
    )
}
