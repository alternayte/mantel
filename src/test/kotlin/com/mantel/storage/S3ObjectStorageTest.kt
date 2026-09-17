package com.mantel.storage

import com.mantel.kernel.StorageConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

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
    fun `a presigned PUT accepts the declared length and refuses any other`() {
        val config = config()
        val s3 = client(config)
        runCatching { s3.createBucket(CreateBucketRequest.builder().bucket(config.bucket).build()) }

        val key = "accounts/33333333-3333-3333-3333-333333333333/albums/a/i/original.jpg"
        val url = S3ObjectStorage(config).use { it.presignPut(key, "image/jpeg", 5, Duration.ofMinutes(10)) }
        val http = HttpClient.newHttpClient()

        fun put(
            body: String,
            contentType: String = "image/jpeg",
        ): Int =
            http.send(
                HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", contentType)
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            ).statusCode()

        // Six bytes against a URL signed for five: storage refuses it, not the client's conscience.
        assertNotEquals(200, put("123456"))
        assertNotEquals(200, put("12345", contentType = "application/pdf"))
        assertEquals(200, put("12345"))
        assertEquals(5L, S3ObjectStorage(config).use { it.sizeOf(key) })
    }

    @Test
    fun `deleting a prefix removes every object under it and nothing else`() {
        val config = config()
        val s3 = client(config)
        // One container serves the whole class, so the bucket may already be there.
        runCatching { s3.createBucket(CreateBucketRequest.builder().bucket(config.bucket).build()) }

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

        fun keysUnder(prefix: String) =
            s3.listObjectsV2(ListObjectsV2Request.builder().bucket(config.bucket).prefix(prefix).build())
                .contents()
                .map { it.key() }
                .sorted()

        assertEquals(emptyList<String>(), keysUnder(mine))
        assertEquals(List(2) { "${theirs}media/$it/original.jpg" }.sorted(), keysUnder(theirs))
    }
}
