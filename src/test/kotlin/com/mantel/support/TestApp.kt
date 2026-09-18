package com.mantel.support

import com.mantel.allTables
import com.mantel.http.Services
import com.mantel.http.module
import com.mantel.kernel.Bytes
import com.mantel.kernel.Clock
import com.mantel.kernel.Config
import com.mantel.kernel.DatabaseConfig
import com.mantel.kernel.GitHubConfig
import com.mantel.kernel.Mail
import com.mantel.kernel.Mailer
import com.mantel.kernel.Schema
import com.mantel.kernel.StorageConfig
import com.mantel.kernel.WorkerConfig
import com.mantel.storage.ObjectStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Duration

/**
 * One Postgres for the whole test run, migrated once and truncated between tests. Testcontainers,
 * not a mock: the claim loop, the partial unique indexes and the timestamp types are the parts most
 * likely to be wrong, and none of them exist in a fake.
 */
object TestDatabase {
    private val container =
        PostgreSQLContainer("postgres:17-alpine").apply {
            withReuse(false)
            start()
        }

    val config = DatabaseConfig(container.jdbcUrl, container.username, container.password)

    init {
        val dataSource = Schema.dataSource(config)
        Schema.migrate(dataSource)
        Schema.connect(dataSource)
    }

    fun truncate() {
        transaction {
            val names = allTables.joinToString(", ") { it.tableName }
            exec("TRUNCATE $names RESTART IDENTITY CASCADE")
        }
    }
}

/** Every mail the application sent, in order. */
class RecordingMailer : Mailer {
    val sent = mutableListOf<Mail>()

    override fun send(mail: Mail) {
        sent += mail
    }

    fun lastLink(): String = sent.last().body.lines().first { it.startsWith("http") }
}

class RecordingStorage : ObjectStorage {
    data class Presign(val key: String, val contentType: String, val contentLength: Long)

    val objects = mutableMapOf<String, ByteArray>()
    val deletedPrefixes = mutableListOf<String>()
    val presigns = mutableListOf<Presign>()
    val uploaded = mutableListOf<Pair<String, String>>()
    val aborted = mutableListOf<String>()
    var lifecycleDays: Int? = null
    val copies = mutableListOf<Pair<String, String>>()
    val publicPrefixes = mutableListOf<String>()

    override fun presignPut(
        key: String,
        contentType: String,
        contentLength: Long,
        expiresIn: Duration,
    ): String {
        presigns += Presign(key, contentType, contentLength)
        return "https://storage.test/$key?signature=test&length=$contentLength"
    }

    override fun sizeOf(key: String): Long? = objects[key]?.size?.toLong()

    // Multipart in memory: enough for the tests that only need an upload id to exist. The real
    // behaviour is proved against MinIO in S3ObjectStorageTest and ResumableUploadTest.
    private val multipart = mutableMapOf<String, MutableList<com.mantel.storage.UploadedPart>>()

    /** Test-only: how old each object pretends to be, for the reconciliation sweep. */
    val ages = mutableMapOf<String, java.time.Instant>()

    override fun list(
        prefix: String,
        after: String?,
    ): Pair<List<com.mantel.storage.StoredObject>, String?> =
        objects.keys
            .filter { it.startsWith(prefix) }
            .sorted()
            .map {
                com.mantel.storage.StoredObject(it, objects[it]!!.size.toLong(), ages[it] ?: java.time.Instant.now())
            } to null

    override fun presignGetForThisHour(
        key: String,
        validFor: Duration,
    ): String = "https://storage.test/$key?signed-for=hour"

    override fun copy(
        fromKey: String,
        toKey: String,
    ) {
        objects[toKey] = objects[fromKey] ?: error("no object at $fromKey")
        copies += fromKey to toKey
    }

    override fun makePrefixPublic(prefix: String) {
        publicPrefixes += prefix
    }

    override fun ensureIncompleteUploadsExpire(afterDays: Int) {
        lifecycleDays = afterDays
    }

    override fun startMultipartUpload(
        key: String,
        contentType: String,
    ): String {
        val uploadId = "upload-${multipart.size + 1}"
        multipart["$key/$uploadId"] = mutableListOf()
        return uploadId
    }

    override fun presignPart(
        key: String,
        uploadId: String,
        partNumber: Int,
        contentLength: Long,
        expiresIn: Duration,
    ): String = "https://storage.test/$key?uploadId=$uploadId&partNumber=$partNumber&length=$contentLength"

    override fun listParts(
        key: String,
        uploadId: String,
    ): List<com.mantel.storage.UploadedPart> = multipart["$key/$uploadId"].orEmpty()

    override fun completeMultipartUpload(
        key: String,
        uploadId: String,
        parts: List<com.mantel.storage.UploadedPart>,
    ) {
        objects[key] = ByteArray(parts.sumOf { it.sizeBytes }.toInt())
        multipart.remove("$key/$uploadId")
    }

    override fun abortMultipartUpload(
        key: String,
        uploadId: String,
    ) {
        aborted += uploadId
        multipart.remove("$key/$uploadId")
    }

    /** Test-only: pretend a part arrived at storage. */
    fun receivePart(
        key: String,
        uploadId: String,
        partNumber: Int,
        sizeBytes: Long,
    ) {
        multipart.getOrPut("$key/$uploadId") { mutableListOf() }
            .add(com.mantel.storage.UploadedPart(partNumber, "etag-$partNumber", sizeBytes))
    }

    override fun download(
        key: String,
        to: java.nio.file.Path,
    ) {
        java.nio.file.Files.write(to, objects[key] ?: error("no object at $key"))
    }

    override fun upload(
        key: String,
        from: java.nio.file.Path,
        contentType: String,
    ) {
        objects[key] = java.nio.file.Files.readAllBytes(from)
        uploaded += key to contentType
    }

    override fun delete(keys: List<String>) {
        keys.forEach { objects.remove(it) }
    }

    override fun deletePrefix(prefix: String) {
        deletedPrefixes += prefix
        objects.keys.filter { it.startsWith(prefix) }.forEach { objects.remove(it) }
    }
}

const val TEST_WORKER_TOKEN = "worker-token-for-tests"

/** Time the tests move by hand, for claim timeouts and retry backoff. */
class MutableClock(private var instant: java.time.Instant = java.time.Instant.parse("2026-01-01T00:00:00Z")) : Clock {
    override fun now(): java.time.Instant = instant

    fun advance(duration: java.time.Duration) {
        instant = instant.plus(duration)
    }
}

fun testConfig(
    github: GitHubConfig? = null,
    storage: StorageConfig? = null,
) = Config(
    port = 0,
    publicBaseUrl = "http://localhost",
    defaultQuota = Bytes(10L * 1024 * 1024 * 1024),
    maxFileBytes = Bytes(5L * 1024 * 1024 * 1024),
    database = TestDatabase.config,
    storage =
        storage
            ?: StorageConfig(
                endpoint = "http://storage.test",
                region = "auto",
                bucket = "mantel",
                accessKeyId = "test",
                secretAccessKey = "test",
                forcePathStyle = true,
                multipartThreshold = Bytes(64L * 1024 * 1024),
                partSize = Bytes(16L * 1024 * 1024),
            ),
    cookieSecret = "test-cookie-secret",
    devAssetsOrigin = null,
    smtp = null,
    github = github,
    worker =
        WorkerConfig(
            token = TEST_WORKER_TOKEN,
            claimTimeout = Duration.ofMinutes(10),
            maxAttempts = 3,
            batchSize = 4,
            pollInterval = Duration.ofSeconds(1),
            reconcileInterval = Duration.ofHours(6),
        ),
)

class Harness(
    val mailer: RecordingMailer,
    val storage: RecordingStorage,
    val clock: MutableClock,
) {
    /** The running application, for tests that ask the routing table what exists. */
    lateinit var application: Application

    /**
     * Marks an item ready as the worker would, without running libvips. Tests about sharing care
     * that an item is ready, not how it got there.
     */
    fun renderItem(
        itemId: String,
        originalKey: String,
    ) {
        val prefix = originalKey.substringBeforeLast('/')
        listOf("thumb.webp", "display.webp", "display.avif").forEach {
            storage.objects["$prefix/$it"] = "derivative".toByteArray()
        }
        org.jetbrains.exposed.sql.transactions.transaction {
            com.mantel.features.media.MediaItems.update(
                { com.mantel.features.media.MediaItems.id eq com.mantel.features.media.ItemId(java.util.UUID.fromString(itemId)) },
            ) {
                it[com.mantel.features.media.MediaItems.status] = com.mantel.features.media.ItemState.SHAREABLE
                it[com.mantel.features.media.MediaItems.thumbKey] = "$prefix/thumb.webp"
                it[com.mantel.features.media.MediaItems.displayWebpKey] = "$prefix/display.webp"
                it[com.mantel.features.media.MediaItems.displayAvifKey] = "$prefix/display.avif"
                it[com.mantel.features.media.MediaItems.width] = 2400
                it[com.mantel.features.media.MediaItems.height] = 1600
            }
            // The API does this when the worker reports, and an album's status follows its items.
            com.mantel.features.album.settleAlbumsHolding(
                com.mantel.features.media.ItemId(java.util.UUID.fromString(itemId)),
                java.time.OffsetDateTime.now(),
            )
        }
    }
}

/**
 * Boots the real routing over the real database with a recording mailer and storage. `github`
 * supplies a mock GitHub; without one the OAuth routes report that GitHub is not configured.
 */
fun withApp(
    github: GitHubConfig? = null,
    githubResponder: MockEngine? = null,
    clock: MutableClock = MutableClock(),
    storage: ObjectStorage? = null,
    storageConfig: StorageConfig? = null,
    block: suspend ApplicationTestBuilder.(Harness) -> Unit,
) {
    TestDatabase.truncate()
    val harness = Harness(RecordingMailer(), RecordingStorage(), clock)
    testApplication {
        val client =
            githubResponder?.let { engine ->
                HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            } ?: HttpClient(
                MockEngine {
                    respond(
                        "{}",
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                },
            ) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        application {
            harness.application = this
            module(
                Services(
                    config = testConfig(github, storageConfig),
                    clock = clock,
                    storage = storage ?: harness.storage,
                    mailer = harness.mailer,
                    httpClient = client,
                ),
            )
        }
        block(harness)
    }
}

/** A client that keeps cookies, which is how a browser behaves and how a session is exercised. */
fun ApplicationTestBuilder.browser() =
    createClient {
        install(HttpCookies)
        install(ContentNegotiation) { json() }
        followRedirects = false
    }
