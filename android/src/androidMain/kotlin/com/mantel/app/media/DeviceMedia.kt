package com.mantel.app.media

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.serialization.Serializable

/**
 * One folder of media on the device, as MediaStore groups it.
 *
 * Folders rather than all-or-nothing, because the camera roll is photographs and the rest of the
 * device is screenshots, downloads and things somebody sent in a group chat. Backing those up
 * silently costs the person quota and costs the operator storage.
 */
@Serializable
data class MediaFolder(val id: String, val name: String, val count: Int)

/**
 * The device's media, as folders and as files.
 *
 * Everything here needs the media permission, which the app asks for only when somebody turns sync
 * on. Nothing calls it before that.
 */
object DeviceMedia {
    private val COLLECTIONS =
        listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )

    private const val BUCKET_ID = MediaStore.MediaColumns.BUCKET_ID
    private const val BUCKET_NAME = MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
    private const val ADDED = MediaStore.MediaColumns.DATE_ADDED

    /** The folder Android puts the camera in, which is the one sync is on for by default. */
    const val CAMERA = "Camera"

    fun folders(context: Context): List<MediaFolder> {
        val counts = mutableMapOf<String, Pair<String, Int>>()
        COLLECTIONS.forEach { collection ->
            context.contentResolver.query(
                collection,
                arrayOf(BUCKET_ID, BUCKET_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(BUCKET_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(BUCKET_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idColumn) ?: continue
                    val name = cursor.getString(nameColumn) ?: continue
                    val (_, count) = counts[id] ?: (name to 0)
                    counts[id] = name to count + 1
                }
            }
        }
        return counts
            .map { (id, value) -> MediaFolder(id, value.first, value.second) }
            .sortedWith(compareByDescending<MediaFolder> { it.name == CAMERA }.thenBy { it.name })
    }

    /**
     * Media in the chosen folders that arrived after the watermark, each with when it arrived.
     *
     * The watermark is `DATE_ADDED`, in seconds, and the query is inclusive of it: a second may hold
     * more than one photograph, and offering one twice costs nothing because the server already
     * holds it by hash. Missing one costs a backup. The order is left to `chunksOf`, which sorts
     * photographs and videos together; each collection here answers in its own order.
     */
    fun since(
        context: Context,
        folders: Set<String>,
        watermark: Long,
    ): List<Arrival<Uri>> {
        if (folders.isEmpty()) return emptyList()
        val found = mutableListOf<Arrival<Uri>>()

        COLLECTIONS.forEach { collection ->
            val selection = "$BUCKET_ID IN (${folders.joinToString(",") { "?" }}) AND $ADDED >= ?"
            val arguments = (folders + watermark.toString()).toTypedArray()
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, ADDED),
                selection,
                arguments,
                "$ADDED ASC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val addedColumn = cursor.getColumnIndexOrThrow(ADDED)
                while (cursor.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                    found += Arrival(uri, cursor.getLong(addedColumn))
                }
            }
        }
        return found
    }

    /** What the app must hold to read the device's media at all. */
    fun permissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
}
