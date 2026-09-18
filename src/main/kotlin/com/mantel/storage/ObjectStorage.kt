package com.mantel.storage

import java.time.Duration

/**
 * The S3-compatible surface the product uses: MinIO in development, Cloudflare R2 by default in
 * deployment. Bytes never transit the API, so this port issues URLs and deletes keys; it does not
 * carry upload or download bodies for media.
 */
data class UploadedPart(val partNumber: Int, val etag: String, val sizeBytes: Long)

interface ObjectStorage {
    /** A presigned PUT the client uploads to directly, with the declared size enforced by storage. */
    fun presignPut(
        key: String,
        contentType: String,
        contentLength: Long,
        expiresIn: Duration,
    ): String

    /**
     * Asks the provider to abort incomplete multipart uploads after a day. Those parts bill for
     * bytes that no listing shows and no row points at, and a self-hoster should not have to know
     * that. Best effort: a provider may refuse, and the product still works.
     */
    fun ensureIncompleteUploadsExpire(afterDays: Int)

    /**
     * Starts a multipart upload and returns the provider's id for it. The parts are uploaded
     * directly by the client, like a single PUT, and the API finalises.
     */
    fun startMultipartUpload(
        key: String,
        contentType: String,
    ): String

    /** A presigned PUT for one part, signed for exactly that part's length. */
    fun presignPart(
        key: String,
        uploadId: String,
        partNumber: Int,
        contentLength: Long,
        expiresIn: Duration,
    ): String

    /** The parts storage has actually received. This is what makes an interrupted upload resumable. */
    fun listParts(
        key: String,
        uploadId: String,
    ): List<UploadedPart>

    fun completeMultipartUpload(
        key: String,
        uploadId: String,
        parts: List<UploadedPart>,
    )

    fun abortMultipartUpload(
        key: String,
        uploadId: String,
    )

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
