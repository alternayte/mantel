package com.mantel.storage

import com.mantel.kernel.StorageConfig
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import java.time.Duration

class S3ObjectStorage(
    private val config: StorageConfig,
) : ObjectStorage, AutoCloseable {
    private val credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(config.accessKeyId, config.secretAccessKey),
        )

    private val serviceConfiguration =
        S3Configuration.builder().pathStyleAccessEnabled(config.forcePathStyle).build()

    private val client: S3Client =
        S3Client.builder()
            .endpointOverride(URI.create(config.endpoint))
            .region(Region.of(config.region))
            .credentialsProvider(credentials)
            .serviceConfiguration(serviceConfiguration)
            .build()

    private val presigner: S3Presigner =
        S3Presigner.builder()
            .endpointOverride(URI.create(config.endpoint))
            .region(Region.of(config.region))
            .credentialsProvider(credentials)
            .serviceConfiguration(serviceConfiguration)
            .build()

    override fun presignPut(
        key: String,
        contentType: String,
        contentLength: Long,
        expiresIn: Duration,
    ): String {
        val put =
            PutObjectRequest.builder()
                .bucket(config.bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(contentLength)
                .build()
        return presigner.presignPutObject(
            PutObjectPresignRequest.builder().signatureDuration(expiresIn).putObjectRequest(put).build(),
        ).url().toString()
    }

    override fun sizeOf(key: String): Long? =
        try {
            client.headObject(HeadObjectRequest.builder().bucket(config.bucket).key(key).build()).contentLength()
        } catch (_: NoSuchKeyException) {
            null
        }

    override fun delete(keys: List<String>) {
        // One request per key. The batch DeleteObjects call needs a Content-MD5 header that the SDK
        // no longer sends, and S3-compatible stores reject it; deletion is rare enough that the
        // extra requests cost less than a compatibility trap in someone else's MinIO.
        keys.forEach { key ->
            client.deleteObject(DeleteObjectRequest.builder().bucket(config.bucket).key(key).build())
        }
    }

    override fun deletePrefix(prefix: String) {
        var continuationToken: String? = null
        do {
            val listing =
                client.listObjectsV2(
                    ListObjectsV2Request.builder()
                        .bucket(config.bucket)
                        .prefix(prefix)
                        .continuationToken(continuationToken)
                        .build(),
                )
            delete(listing.contents().map { it.key() })
            continuationToken = listing.nextContinuationToken()
        } while (listing.isTruncated == true)
    }

    override fun close() {
        presigner.close()
        client.close()
    }
}
