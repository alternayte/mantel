package com.mantel.app.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mantel.app.api.DeclaredFile
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Separate from the settings store: a batch is transient work, not a preference. */
private val Context.uploadStore: DataStore<Preferences> by preferencesDataStore(name = "uploads")

/**
 * One file in a batch, from the moment it is picked to the moment the server has it.
 *
 * `itemId` exists as soon as the intent is granted, so a batch that is interrupted can be picked up
 * where it stopped rather than declared again: a second intent would reserve quota twice and leave
 * the first set of rows behind.
 */
@Serializable
data class BatchItem(
    val uri: String,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val itemId: String? = null,
    val uploadUrl: String? = null,
    val uploadId: String? = null,
    val uploaded: Boolean = false,
)

@Serializable
data class UploadBatch(
    val albumId: String,
    val items: List<BatchItem>,
) {
    val totalBytes: Long get() = items.sumOf { it.sizeBytes }
    val doneBytes: Long get() = items.filter { it.uploaded }.sumOf { it.sizeBytes }
}

/**
 * A batch outlives the process that started it, because WorkManager does. It is deleted when the
 * server has confirmed the bytes, which is the only point at which nothing is owed.
 */
class UploadBatches(private val context: Context) {
    private fun key(id: String) = stringPreferencesKey("batch:$id")

    suspend fun load(id: String): UploadBatch? = context.uploadStore.data.first()[key(id)]?.let { Json.decodeFromString<UploadBatch>(it) }

    suspend fun save(
        id: String,
        batch: UploadBatch,
    ) {
        context.uploadStore.edit { it[key(id)] = Json.encodeToString(batch) }
    }

    suspend fun forget(id: String) {
        context.uploadStore.edit { it.remove(key(id)) }
    }
}

/**
 * What the picker hands back is a URI and nothing else, so the name, type and size come from the
 * content resolver. The size is declared to the server before any bytes move, and the presigned PUT
 * is signed for exactly it, so a wrong answer here is refused by storage rather than absorbed.
 */
fun describe(
    context: Context,
    uri: Uri,
): BatchItem? {
    val contentType = context.contentResolver.getType(uri) ?: return null
    val cursor =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?: return null
    cursor.use {
        if (!it.moveToFirst()) return null
        val name = it.getString(0) ?: uri.lastPathSegment ?: return null
        val size = it.getLong(1)
        if (size <= 0) return null
        return BatchItem(uri = uri.toString(), filename = name, contentType = contentType, sizeBytes = size)
    }
}

fun BatchItem.declared() = DeclaredFile(filename = filename, contentType = contentType, sizeBytes = sizeBytes)
