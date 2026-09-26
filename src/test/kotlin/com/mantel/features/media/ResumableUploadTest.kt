package com.mantel.features.media

import com.mantel.features.album.AlbumSummary
import com.mantel.features.album.AlbumView
import com.mantel.kernel.Bytes
import com.mantel.kernel.StorageConfig
import com.mantel.storage.S3ObjectStorage
import com.mantel.support.MINIO_IMAGE
import com.mantel.support.createAlbum
import com.mantel.support.signedIn
import com.mantel.support.uploadIntent
import com.mantel.support.withApp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Random

/**
 * Guarantee: an interrupted large upload resumes without re-sending what arrived (SDD.md 12).
 *
 * Real MinIO, real presigned part URLs, real HTTP from outside the application. A fake would agree
 * with whatever the code believes about parts, which is the one thing worth checking.
 */
@Testcontainers
class ResumableUploadTest {
    companion object {
        @Container
        @JvmStatic
        val minio =
            MinIOContainer(MINIO_IMAGE)

        private const val PART = 5 * 1024 * 1024
        private const val TOTAL = (PART * 2) + 1024
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val http: HttpClient = HttpClient.newHttpClient()

    private fun storageConfig() =
        StorageConfig(
            endpoint = minio.s3URL,
            region = "us-east-1",
            bucket = "mantel",
            accessKeyId = minio.userName,
            secretAccessKey = minio.password,
            forcePathStyle = true,
            // Anything over a megabyte uploads in parts here, so the test does not move 64 MiB.
            multipartThreshold = Bytes(1024 * 1024),
            partSize = Bytes(PART.toLong()),
        )

    private fun bucket(config: StorageConfig) {
        val client =
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
        client.use { runCatching { it.createBucket(CreateBucketRequest.builder().bucket(config.bucket).build()) } }
    }

    private fun bytes(
        size: Int,
        seed: Long,
    ): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun put(
        url: String,
        body: ByteArray,
    ): Int =
        http.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "video/mp4")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode()

    @Test
    fun `an upload interrupted after one part sends only what is missing`() {
        val config = storageConfig()
        bucket(config)
        val storage = S3ObjectStorage(config)

        withApp(storage = storage, storageConfig = config) { harness ->
            val creator = signedIn(harness)
            val album = creator.createAlbum("Video").body<AlbumSummary>()
            val clip = bytes(TOTAL, seed = 7)

            val intent =
                json.decodeFromString<UploadIntentResponse>(
                    creator.uploadIntent(
                        album.id,
                        """{"files":[{"filename":"clip.mp4","contentType":"video/mp4","sizeBytes":$TOTAL}]}""",
                    ).bodyAsText(),
                )
            val item = intent.items.single()
            assertNull(item.uploadUrl, "a large file is not a single PUT")
            assertNotNull(item.uploadId)
            assertEquals(listOf(PART.toLong(), PART.toLong(), 1024L), item.parts!!.map { it.sizeBytes })

            // The first part arrives. Then the connection drops and the client forgets everything.
            assertEquals(200, put(item.parts!![0].uploadUrl, clip.copyOfRange(0, PART)))

            // Completing now must not succeed: two thirds of the file is missing.
            val early =
                creator.post("/api/albums/${album.id}/uploads/complete") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"itemIds":["${item.itemId}"]}""")
                }
            assertEquals(listOf(item.itemId), json.decodeFromString<CompleteUploadsResponse>(early.bodyAsText()).missing)

            // The client asks what arrived. Storage answers, not the client's memory.
            val progress =
                json.decodeFromString<UploadProgress>(
                    creator.get("/api/albums/${album.id}/items/${item.itemId}/upload-progress").bodyAsText(),
                )
            assertEquals(listOf(1), progress.received.map { it.partNumber })
            assertEquals(PART.toLong(), progress.received.single().sizeBytes)
            assertEquals(listOf(2, 3), progress.remaining.map { it.partNumber }, "part 1 must not be re-sent")

            progress.remaining.forEach { part ->
                val from = (part.partNumber - 1) * PART
                assertEquals(200, put(part.uploadUrl, clip.copyOfRange(from, from + part.sizeBytes.toInt())))
            }

            val complete =
                creator.post("/api/albums/${album.id}/uploads/complete") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"itemIds":["${item.itemId}"]}""")
                }
            assertEquals(HttpStatusCode.OK, complete.status)
            assertEquals(
                listOf(item.itemId),
                json.decodeFromString<CompleteUploadsResponse>(complete.bodyAsText()).uploaded,
            )

            // The object in storage is the file, whole and the right size.
            val key =
                transaction {
                    MediaItems.selectAll()
                        .where { MediaItems.id eq ItemId(java.util.UUID.fromString(item.itemId)) }
                        .single()[MediaItems.originalKey]
                }
            assertEquals(TOTAL.toLong(), storage.sizeOf(key))
            val items = creator.get("/api/albums/${album.id}").body<AlbumView>().items
            assertEquals("uploaded", items.single().status)
        }
        storage.close()
    }

    @Test
    fun `a small file still uploads in one request`() {
        val config = storageConfig()
        bucket(config)
        val storage = S3ObjectStorage(config)

        withApp(storage = storage, storageConfig = config) { harness ->
            val creator = signedIn(harness)
            val album = creator.createAlbum().body<AlbumSummary>()

            val intent =
                json.decodeFromString<UploadIntentResponse>(
                    creator.uploadIntent(
                        album.id,
                        """{"files":[{"filename":"a.jpg","contentType":"image/jpeg","sizeBytes":2048}]}""",
                    ).bodyAsText(),
                )
            val item = intent.items.single()
            assertNotNull(item.uploadUrl)
            assertNull(item.uploadId)
            assertNull(item.parts)

            assertEquals(200, putJpeg(item.uploadUrl!!, bytes(2048, seed = 3)))
            val complete =
                creator.post("/api/albums/${album.id}/uploads/complete") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"itemIds":["${item.itemId}"]}""")
                }
            assertTrue(json.decodeFromString<CompleteUploadsResponse>(complete.bodyAsText()).uploaded.isNotEmpty())
        }
        storage.close()
    }

    private fun putJpeg(
        url: String,
        body: ByteArray,
    ): Int =
        http.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "image/jpeg")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode()
}
