package com.mantel.app.timeline

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

/**
 * What the app knows about one photograph on the phone: its content hash, and the library item it
 * became. The timeline shows a photograph once because of this row.
 *
 * A hash is only good for the file it was read from, so the row keeps what MediaStore said about
 * the file at that moment — its size and when it was last modified. A file edited since has a row
 * that no longer matches, and it is hashed again.
 */
@Entity(tableName = "roll_entry")
data class RollEntry(
    /** The MediaStore content URI. Stable for as long as the file exists. */
    @PrimaryKey val uri: String,
    val size: Long,
    val dateModified: Long,
    /** SHA-256 of the bytes, as the server's `contentHash`. Null until it has been read. */
    val hash: String? = null,
    /** The library item these bytes are, once an upload or a match has said so. */
    val itemId: String? = null,
)

@Dao
interface RollEntries {
    @Query("SELECT * FROM roll_entry")
    suspend fun all(): List<RollEntry>

    @Query("SELECT * FROM roll_entry WHERE uri = :uri")
    suspend fun byUri(uri: String): RollEntry?

    @Upsert
    suspend fun upsert(entry: RollEntry)

    /** Rows for files that no longer exist. A deleted photograph's hash is nobody's business. */
    @Query("DELETE FROM roll_entry WHERE uri IN (:uris)")
    suspend fun forget(uris: List<String>)
}

@Database(entities = [RollEntry::class], version = 1, exportSchema = true)
abstract class IndexDatabase : RoomDatabase() {
    abstract fun entries(): RollEntries

    companion object {
        @Volatile private var instance: IndexDatabase? = null

        fun of(context: Context): IndexDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, IndexDatabase::class.java, "index.db")
                    .build()
                    .also { instance = it }
            }
    }
}

/**
 * Writes what an upload learned: these bytes, at this URI, are this library item. An upload hashes
 * every file before it declares it, so a photograph the backup sent is in the index the moment it
 * lands, and never needs hashing again.
 */
suspend fun RollEntries.remember(
    uri: String,
    size: Long,
    dateModified: Long,
    hash: String,
    itemId: String?,
) {
    upsert(RollEntry(uri = uri, size = size, dateModified = dateModified, hash = hash, itemId = itemId))
}

/**
 * What an upload learned about a photograph from the camera roll. A file the picker handed over has
 * a picker URI the camera roll never shows, so only MediaStore's own URIs are worth remembering.
 */
suspend fun recordUpload(
    context: Context,
    uri: String,
    size: Long,
    hash: String,
    itemId: String?,
) {
    if (!uri.startsWith("content://media/external/")) return
    val modified =
        context.contentResolver.query(
            android.net.Uri.parse(uri),
            arrayOf(android.provider.MediaStore.MediaColumns.DATE_MODIFIED),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null } ?: return
    IndexDatabase.of(context).entries().remember(uri, size, modified, hash, itemId)
}
