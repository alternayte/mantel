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
import kotlinx.coroutines.delay
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
)

@Serializable
private data class DerivativesWritten(
    val thumbKey: String,
    val displayWebpKey: String,
    val displayAvifKey: String,
    val width: Int,
    val height: Int,
    val durationMs: Int? = null,
)

@Serializable
private data class WorkFailed(val error: String)

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
            runCatching { process(item) }
                .onFailure { failure ->
                    log.warn("item {} failed on attempt {}: {}", item.itemId, item.attempt, failure.message)
                    report(item, "/failure", WorkFailed(failure.message ?: failure::class.simpleName.orEmpty()))
                }
        }
        return claimed.size
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
                // Video arrives at M4. Until then a video item fails honestly rather than
                // sitting in the queue looking like it is being worked on.
                else -> throw PipelineFailure("${item.kind} is not rendered yet")
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
