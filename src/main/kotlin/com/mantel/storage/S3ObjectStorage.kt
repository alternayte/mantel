package com.mantel.storage

import com.mantel.kernel.StorageConfig
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.AbortIncompleteMultipartUpload
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.ExpirationStatus
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.LifecycleRule
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListPartsRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.NoSuchUploadException
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

private val log = org.slf4j.LoggerFactory.getLogger("com.mantel.storage")

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

    // Signed with the address the browser will use, which is not always the one this server uses.
    private val presigner: S3Presigner =
        S3Presigner.builder()
            .endpointOverride(URI.create(config.publicEndpoint ?: config.endpoint))
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

    fun putLifecycleOrThrow(afterDays: Int) {
        run {
            client.putBucketLifecycleConfiguration(
                PutBucketLifecycleConfigurationRequest.builder()
                    .bucket(config.bucket)
                    .lifecycleConfiguration(
                        BucketLifecycleConfiguration.builder()
                            .rules(
                                LifecycleRule.builder()
                                    .id("mantel-abort-incomplete-uploads")
                                    .status(ExpirationStatus.ENABLED)
                                    .filter(LifecycleRuleFilter.builder().prefix("").build())
                                    .abortIncompleteMultipartUpload(
                                        AbortIncompleteMultipartUpload.builder()
                                            .daysAfterInitiation(afterDays)
                                            .build(),
                                    )
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
        }
    }

    override fun ensureIncompleteUploadsExpire(afterDays: Int) {
        try {
            putLifecycleOrThrow(afterDays)
        } catch (refusal: S3Exception) {
            log.warn(
                "storage refused the lifecycle rule for incomplete uploads ({}). " +
                    "Abandoned parts will bill until something else removes them.",
                refusal.awsErrorDetails()?.errorCode(),
            )
        }
    }

    override fun startMultipartUpload(
        key: String,
        contentType: String,
    ): String =
        client.createMultipartUpload(
            CreateMultipartUploadRequest.builder().bucket(config.bucket).key(key).contentType(contentType).build(),
        ).uploadId()

    override fun presignPart(
        key: String,
        uploadId: String,
        partNumber: Int,
        contentLength: Long,
        expiresIn: Duration,
    ): String {
        val part =
            UploadPartRequest.builder()
                .bucket(config.bucket)
                .key(key)
                .uploadId(uploadId)
                .partNumber(partNumber)
                .contentLength(contentLength)
                .build()
        return presigner.presignUploadPart(
            UploadPartPresignRequest.builder().signatureDuration(expiresIn).uploadPartRequest(part).build(),
        ).url().toString()
    }

    override fun listParts(
        key: String,
        uploadId: String,
    ): List<UploadedPart> =
        try {
            client.listParts(
                ListPartsRequest.builder().bucket(config.bucket).key(key).uploadId(uploadId).build(),
            ).parts().map { UploadedPart(it.partNumber(), it.eTag(), it.size()) }
        } catch (_: NoSuchUploadException) {
            emptyList()
        }

    override fun completeMultipartUpload(
        key: String,
        uploadId: String,
        parts: List<UploadedPart>,
    ) {
        client.completeMultipartUpload(
            CompleteMultipartUploadRequest.builder()
                .bucket(config.bucket)
                .key(key)
                .uploadId(uploadId)
                .multipartUpload(
                    CompletedMultipartUpload.builder()
                        .parts(
                            parts.sortedBy { it.partNumber }.map {
                                CompletedPart.builder().partNumber(it.partNumber).eTag(it.etag).build()
                            },
                        )
                        .build(),
                )
                .build(),
        )
    }

    override fun abortMultipartUpload(
        key: String,
        uploadId: String,
    ) {
        try {
            client.abortMultipartUpload(
                AbortMultipartUploadRequest.builder().bucket(config.bucket).key(key).uploadId(uploadId).build(),
            )
        } catch (_: NoSuchUploadException) {
            // Already gone, by lifecycle rule or a previous abort.
        }
    }

    override fun download(
        key: String,
        to: Path,
    ) {
        client.getObject(GetObjectRequest.builder().bucket(config.bucket).key(key).build()).use { source ->
            Files.copy(source, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun upload(
        key: String,
        from: Path,
        contentType: String,
    ) {
        client.putObject(
            PutObjectRequest.builder().bucket(config.bucket).key(key).contentType(contentType).build(),
            from,
        )
    }

    override fun sizeOf(key: String): Long? =
        try {
            client.headObject(HeadObjectRequest.builder().bucket(config.bucket).key(key).build()).contentLength()
        } catch (_: NoSuchKeyException) {
            null
        } catch (failure: S3Exception) {
            // Some providers answer a HEAD for a missing key with a bare 404 and no error code.
            if (failure.statusCode() == 404) null else throw failure
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
