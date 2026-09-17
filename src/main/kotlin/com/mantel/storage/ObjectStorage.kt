package com.mantel.storage

import java.time.Duration

/**
 * The S3-compatible surface the product uses: MinIO in development, Cloudflare R2 by default in
 * deployment. Bytes never transit the API, so this port issues URLs and deletes keys; it does not
 * carry upload or download bodies for media.
 */
interface ObjectStorage {
    /** A presigned PUT the client uploads to directly, with the declared size enforced by storage. */
    fun presignPut(
        key: String,
        contentType: String,
        contentLength: Long,
        expiresIn: Duration,
    ): String

    /** Reads an object to a local file. The worker does this; the API never touches media bytes. */
    fun download(
        key: String,
        to: java.nio.file.Path,
    )

    /** Writes a derivative. Only the worker calls this. */
    fun upload(
        key: String,
        from: java.nio.file.Path,
        contentType: String,
    )

    /** The object's size, or null when it is not there. Used to confirm an upload actually arrived. */
    fun sizeOf(key: String): Long?

    fun delete(keys: List<String>)

    /** Every object under a prefix. Account deletion needs this before any row lists the keys. */
    fun deletePrefix(prefix: String)
}
