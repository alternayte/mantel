package com.mantel.storage

import com.mantel.kernel.Bytes
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
import java.time.Instant
import java.time.temporal.ChronoUnit

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
            multipartThreshold = Bytes(64L * 1024 * 1024),
            partSize = Bytes(5L * 1024 * 1024),
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
    fun `asking for the expiry rule never breaks startup, and an abort takes the parts now`() {
        val config = config()
        val s3 = client(config)
        runCatching { s3.createBucket(CreateBucketRequest.builder().bucket(config.bucket).build()) }

        S3ObjectStorage(config).use { storage ->
            // Asking for the rule never breaks startup, whatever the provider answers. This MinIO
            // release refuses it (it wants a Content-Md5 the SDK no longer sends), which is exactly
            // the case the warning path exists for; docs/operations/storage.md has the manual rule.
            storage.ensureIncompleteUploadsExpire(afterDays = 1)

            // An abandoned upload holds bytes that no listing shows, so aborting must really remove
            // the parts rather than leaving them to the rule.
            val key = "accounts/44444444-4444-4444-4444-444444444444/albums/a/i/original.mp4"
            val uploadId = storage.startMultipartUpload(key, "video/mp4")
            assertEquals(emptyList<Int>(), storage.listParts(key, uploadId).map { it.partNumber })

            storage.abortMultipartUpload(key, uploadId)
            assertEquals(emptyList<Int>(), storage.listParts(key, uploadId).map { it.partNumber })
            // Aborting twice is not an error: the lifecycle rule may have gone first.
            storage.abortMultipartUpload(key, uploadId)
        }
    }

    @Test
    fun `every viewer in the same hour is handed the same URL, and it works`() {
        val config = config()
        val s3 = client(config)
        runCatching { s3.createBucket(CreateBucketRequest.builder().bucket(config.bucket).build()) }

        val key = "accounts/55555555-5555-5555-5555-555555555555/albums/a/i/display.webp"
        s3.putObject(
            PutObjectRequest.builder().bucket(config.bucket).key(key).build(),
            RequestBody.fromString("pretend this is a photo"),
        )

        // Inside the hour that is running now: a URL signed for a past hour is genuinely expired.
        val thisHour = Instant.now().truncatedTo(ChronoUnit.HOURS)
        val early = com.mantel.kernel.Clock { thisHour.plusSeconds(1) }
        val late = com.mantel.kernel.Clock { Instant.now() }
        val previousHour = com.mantel.kernel.Clock { thisHour.minusSeconds(1) }

        val first = S3ObjectStorage(config, early).use { it.presignGetForThisHour(key, Duration.ofHours(6)) }
        val second = S3ObjectStorage(config, late).use { it.presignGetForThisHour(key, Duration.ofHours(6)) }
        val other =
            S3ObjectStorage(config, previousHour).use { it.presignGetForThisHour(key, Duration.ofHours(6)) }

        // Two viewers in the same hour: one cache entry, not two.
        assertEquals(first, second)
        assertNotEquals(first, other, "a different hour must mint a different URL")

        // And the URL actually fetches the object.
        val response =
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(first)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(200, response.statusCode(), response.body())
        assertEquals("pretend this is a photo", response.body())
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
