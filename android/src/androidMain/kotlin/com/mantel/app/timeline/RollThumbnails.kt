package com.mantel.app.timeline

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A photograph on the phone, at thumbnail size, for the timeline's grid.
 *
 * Decoding a twelve-megapixel original to draw a quarter of the screen's width is what makes a long
 * grid stutter. MediaStore already keeps a thumbnail of every photograph and every video's frame, so
 * the grid asks for that instead, and a video needs no decoder of its own.
 */
data class RollThumb(val uri: String, val video: Boolean)

/** The edge MediaStore is asked for. It keeps thumbnails near this size, so the answer is quick. */
private const val EDGE = 320

class RollThumbFetcher(
    private val data: RollThumb,
    private val context: Context,
) : Fetcher {
    override suspend fun fetch(): FetchResult =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(data.uri)
            val bitmap: Bitmap =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(uri, Size(EDGE, EDGE), null)
                } else {
                    legacyThumbnail(uri) ?: throw java.io.IOException("No thumbnail for ${data.uri}")
                }
            ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
        }

    @Suppress("DEPRECATION")
    private fun legacyThumbnail(uri: Uri): Bitmap? {
        val id = ContentUris.parseId(uri)
        return if (data.video) {
            MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
        } else {
            MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<RollThumb> {
        override fun create(
            data: RollThumb,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = RollThumbFetcher(data, context)
    }
}

/** The key Coil keeps a thumbnail in memory under. Without one it keeps none, and every scroll decodes again. */
class RollThumbKeyer : Keyer<RollThumb> {
    override fun key(
        data: RollThumb,
        options: Options,
    ): String = "roll:${data.uri}"
}
