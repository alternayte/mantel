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

    /**
     * A presigned GET whose signature is fixed to the current hour, so every viewer inside that
     * hour is handed a byte-identical URL and the CDN keeps one cache entry instead of one per
     * viewer. Without this the CDN is decoration (SDD.md 3.2).
     */
    fun presignGetForThisHour(
        key: String,
        validFor: Duration,
    ): String

    /** Copies an object inside the bucket. Used to put a cover thumbnail on the public prefix. */
    fun copy(
        fromKey: String,
        toKey: String,
    )

    /**
     * Allows anonymous reads of one prefix. Link previews are fetched by crawlers with no
     * credentials at unpredictable times, so the cover thumbnail has to be readable without a
     * signature (SDD.md 4.3). Best effort: a provider may refuse, and only previews are lost.
     */
    fun makePrefixPublic(prefix: String)

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
