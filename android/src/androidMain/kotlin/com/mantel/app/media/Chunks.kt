package com.mantel.app.media

/** Something found on the phone, and when it arrived there, in `DATE_ADDED` seconds. */
data class Arrival<T>(val item: T, val added: Long)

/** One batch of the backup, and how far the backup has got once it lands. */
data class Chunk<T>(val items: List<T>, val watermark: Long)

/**
 * The backup, cut into batches the server will take.
 *
 * The server refuses a batch of more than two hundred files outright, and a phone's first sweep
 * finds thousands. Before this the whole camera roll went as one batch, was refused, and — once the
 * watermark stopped moving on a failure — was offered and refused again on every sweep for ever.
 *
 * The order is by arrival across photographs and videos together. MediaStore answers each
 * collection in its own order, so without the sort a late photograph could lead one chunk and an
 * early video trail the next: if the first landed and the second failed, the watermark would already
 * be past the video and it would never be offered again. Sorted, each chunk's watermark is the
 * newest thing in it, and everything after it is newer.
 */
fun <T> chunksOf(
    arrivals: List<Arrival<T>>,
    size: Int,
): List<Chunk<T>> {
    require(size > 0) { "a chunk holds at least one item" }
    return arrivals
        .sortedBy { it.added }
        .chunked(size)
        .map { chunk -> Chunk(chunk.map { it.item }, chunk.last().added) }
}
