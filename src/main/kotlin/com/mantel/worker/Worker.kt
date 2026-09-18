package com.mantel.worker

import com.mantel.kernel.Config
import com.mantel.storage.ObjectStorage
import com.mantel.storage.S3ObjectStorage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively

@Serializable
private data class ClaimRequest(val limit: Int)

@Serializable
data class ClaimedItem(
    val itemId: String,
    val kind: String,
    val attempt: Int,
    val originalKey: String,
    val thumbKey: String,
    val displayWebpKey: String,
    val displayAvifKey: String,
    val posterKey: String,
    val mp4Key: String,
    val heartbeatSeconds: Long,
)

@Serializable
private data class DerivativesWritten(
    val thumbKey: String,
    val displayWebpKey: String? = null,
    val displayAvifKey: String? = null,
    val posterKey: String? = null,
    val mp4Key: String? = null,
    val width: Int,
    val height: Int,
    val durationMs: Int? = null,
)

@Serializable
private data class WorkFailed(val error: String)

@Serializable
data class BundleEntry(
    val filename: String,
    val key: String,
    val kind: String,
    val caption: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class ClaimedBundle(
    val bundleId: String,
    val variant: String,
    val albumTitle: String,
    val albumDescription: String? = null,
    val targetKey: String,
    val includesOriginals: Boolean,
    val entries: List<BundleEntry>,
    val heartbeatSeconds: Long,
)

@Serializable
private data class BundleBuilt(val key: String, val byteSize: Long)

private val log = LoggerFactory.getLogger("com.mantel.worker")

/**
 * The same binary in worker mode. It reads originals from object storage, writes derivatives, and
 * reports to the API over HTTP. It holds no database credentials (SDD.md 6.3 step 7), which is why
 * nothing here may import a JDBC, Exposed or Flyway type; ConventionTest enforces it.
 *
 * Processing is sequential: one item at a time, so a 4K video cannot starve the machine, and the
 * only concurrency is more worker processes.
 */
fun runWorker(config: Config) {
    val token = config.worker.token ?: error("MANTEL_WORKER_TOKEN must be set to run a worker")
    val http =
        HttpClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    S3ObjectStorage(config.storage).use { storage ->
        runBlocking {
            log.info("worker mode against {}", config.publicBaseUrl)
            PhotoPipeline().verifyCodecs()
            val worker = Worker(config, token, http, storage, PhotoPipeline())
            while (true) {
                val processed = worker.tick()
                if (processed == 0) delay(config.worker.pollInterval.toMillis())
            }
        }
    }
}

class Worker(
    private val config: Config,
    private val token: String,
    private val http: HttpClient,
    private val storage: ObjectStorage,
    private val photos: PhotoPipeline,
    private val videos: VideoPipeline = VideoPipeline(photos = photos),
) {
    /** One pass: claim a batch and process it. Returns how many items were handled. */
    suspend fun tick(): Int {
        val response =
            http.post("${config.publicBaseUrl}/api/worker/claim") {
                authenticate()
                contentType(ContentType.Application.Json)
                setBody(ClaimRequest(config.worker.batchSize))
            }
        // A refused claim is a configuration problem, and it must read as one rather than as a
        // deserialisation crash three frames deeper.
        if (!response.status.isSuccess()) {
            error("the API refused the claim (${response.status}): ${response.bodyAsText()}")
        }
        val claimed: List<ClaimedItem> = response.body()

        claimed.forEach { item ->
            runCatching { withHeartbeat(item) { process(item) } }
                .onFailure { failure ->
                    log.warn("item {} failed on attempt {}: {}", item.itemId, item.attempt, failure.message)
                    report(item, "/failure", WorkFailed(failure.message ?: failure::class.simpleName.orEmpty()))
                }
        }
        return claimed.size + packBundles()
    }

    /**
     * Album bundles are the same shape of work: claim, do something slow, report. They are claimed
     * after media, because a photograph nobody has rendered yet is a photograph missing from every
     * bundle built until it is.
     */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    suspend fun packBundles(): Int {
        val response =
            http.post("${config.publicBaseUrl}/api/worker/bundles/claim") {
                authenticate()
                contentType(ContentType.Application.Json)
                setBody(ClaimRequest(1))
            }
        if (!response.status.isSuccess()) {
            error("the API refused the bundle claim (${response.status}): ${response.bodyAsText()}")
        }
        val bundles: List<ClaimedBundle> = response.body()

        bundles.forEach { bundle ->
            val scratch = Files.createTempDirectory("mantel-bundle-${bundle.bundleId}")
            try {
                val beat =
                    kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext).launch {
                        while (true) {
                            delay(bundle.heartbeatSeconds.coerceAtLeast(1) * 1000)
                            runCatching {
                                http.post(
                                    "${config.publicBaseUrl}/api/worker/bundles/${bundle.bundleId}/heartbeat",
                                ) { authenticate() }
                            }
                        }
                    }
                try {
                    val zip =
                        BundleBuilder(storage).build(
                            into = scratch,
                            title = bundle.albumTitle,
                            description = bundle.albumDescription,
                            includesOriginals = bundle.includesOriginals,
                            items =
                                bundle.entries.map {
                                    BundleItem(it.filename, it.key, it.kind, it.caption, it.width, it.height)
                                },
                        )
                    storage.upload(bundle.targetKey, zip, "application/zip")
                    http.post("${config.publicBaseUrl}/api/worker/bundles/${bundle.bundleId}/built") {
                        authenticate()
                        contentType(ContentType.Application.Json)
                        setBody(BundleBuilt(bundle.targetKey, Files.size(zip)))
                    }
                } finally {
                    beat.cancel()
                }
            } catch (failure: Exception) {
                log.warn("bundle {} failed: {}", bundle.bundleId, failure.message)
                http.post("${config.publicBaseUrl}/api/worker/bundles/${bundle.bundleId}/failure") {
                    authenticate()
                    contentType(ContentType.Application.Json)
                    setBody(WorkFailed(failure.message ?: failure::class.simpleName.orEmpty()))
                }
            } finally {
                scratch.deleteRecursively()
            }
        }
        return bundles.size
    }

    /**
     * Says the job is still running while it runs. A 4K transcode takes longer than the claim
     * timeout on slow hardware, and a lapsed claim means a second worker starts the same file.
     */
    private suspend fun <T> withHeartbeat(
        item: ClaimedItem,
        work: suspend () -> T,
    ): T =
        coroutineScope {
            val beat =
                launch {
                    while (true) {
                        delay(item.heartbeatSeconds.coerceAtLeast(1) * 1000)
                        runCatching { report(item, "/heartbeat", Unit) }
                            .onFailure { log.warn("heartbeat for {} failed: {}", item.itemId, it.message) }
                    }
                }
            try {
                work()
            } finally {
                beat.cancel()
            }
        }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private suspend fun process(item: ClaimedItem) {
        val scratch: Path = Files.createTempDirectory("mantel-${item.itemId}")
        try {
            val original = scratch.resolve("original")
            storage.download(item.originalKey, original)

            when (item.kind) {
                "photo" -> {
                    val rendered = photos.render(original, scratch)
                    storage.upload(item.thumbKey, rendered.thumb, "image/webp")
                    storage.upload(item.displayWebpKey, rendered.displayWebp, "image/webp")
                    storage.upload(item.displayAvifKey, rendered.displayAvif, "image/avif")
                    report(
                        item,
                        "/derivatives",
                        DerivativesWritten(
                            thumbKey = item.thumbKey,
                            displayWebpKey = item.displayWebpKey,
                            displayAvifKey = item.displayAvifKey,
                            width = rendered.width,
                            height = rendered.height,
                        ),
                    )
                }
                "video" -> {
                    val rendered = videos.render(original, scratch)
                    storage.upload(item.thumbKey, rendered.thumb, "image/webp")
                    storage.upload(item.posterKey, rendered.poster, "image/webp")
                    storage.upload(item.mp4Key, rendered.mp4, "video/mp4")
                    report(
                        item,
                        "/derivatives",
                        DerivativesWritten(
                            thumbKey = item.thumbKey,
                            posterKey = item.posterKey,
                            mp4Key = item.mp4Key,
                            width = rendered.width,
                            height = rendered.height,
                            durationMs = rendered.durationMs,
                        ),
                    )
                }
                else -> throw PipelineFailure("${item.kind} is not a kind this renders")
            }
        } finally {
            scratch.deleteRecursively()
        }
    }

    private suspend inline fun <reified T> report(
        item: ClaimedItem,
        path: String,
        body: T,
    ): HttpResponse =
        http.post("${config.publicBaseUrl}/api/worker/items/${item.itemId}$path") {
            authenticate()
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun io.ktor.client.request.HttpRequestBuilder.authenticate() {
        header(HttpHeaders.Authorization, "Bearer $token")
    }
}
