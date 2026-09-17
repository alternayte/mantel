package com.mantel.storage

import com.mantel.kernel.StorageConfig
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
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

    override fun delete(keys: List<String>) {
        if (keys.isEmpty()) return
        keys.chunked(1000).forEach { chunk ->
            client.deleteObjects(
                DeleteObjectsRequest.builder()
                    .bucket(config.bucket)
                    .delete(
                        Delete.builder()
                            .objects(chunk.map { ObjectIdentifier.builder().key(it).build() })
                            .build(),
                    )
                    .build(),
            )
        }
    }

    override fun close() {
        presigner.close()
        client.close()
    }
}
