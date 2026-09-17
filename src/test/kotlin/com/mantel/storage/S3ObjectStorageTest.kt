package com.mantel.storage

import com.mantel.kernel.StorageConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI

/**
 * Account deletion promises the bytes go, and the promise is only worth what the real storage does.
 * MinIO here, R2 in deployment, the same S3 API.
 */
@Testcontainers
class S3ObjectStorageTest {
    companion object {
        @Container
        @JvmStatic
        val minio =
            MinIOContainer(
                // The same image and registry docker-compose.yml uses.
                DockerImageName.parse("quay.io/minio/minio:RELEASE.2024-01-16T16-07-38Z")
                    .asCompatibleSubstituteFor("minio/minio"),
            )
    }

    private fun config() =
        StorageConfig(
            endpoint = minio.s3URL,
            region = "us-east-1",
            bucket = "mantel",
            accessKeyId = minio.userName,
            secretAccessKey = minio.password,
            forcePathStyle = true,
        )

    private fun client(config: StorageConfig): S3Client =
        S3Client.builder()
            .endpointOverride(URI.create(config.endpoint))
            .region(Region.of(config.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(config.accessKeyId, config.secretAccessKey),
                ),
            )
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()

    @Test
    fun `deleting a prefix removes every object under it and nothing else`() {
        val config = config()
        val s3 = client(config)
        s3.createBucket(CreateBucketRequest.builder().bucket(config.bucket).build())

        val mine = "accounts/11111111-1111-1111-1111-111111111111/"
        val theirs = "accounts/22222222-2222-2222-2222-222222222222/"
        val keys = List(5) { "${mine}media/$it/original.jpg" } + List(2) { "${theirs}media/$it/original.jpg" }
        keys.forEach { key ->
            s3.putObject(
                PutObjectRequest.builder().bucket(config.bucket).key(key).build(),
                RequestBody.fromString("bytes"),
            )
        }

        S3ObjectStorage(config).use { it.deletePrefix(mine) }

        val remaining =
            s3.listObjectsV2(ListObjectsV2Request.builder().bucket(config.bucket).build())
                .contents()
                .map { it.key() }
                .sorted()
        assertEquals(List(2) { "${theirs}media/$it/original.jpg" }.sorted(), remaining)
    }
}
