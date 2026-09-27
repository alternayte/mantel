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
    val full: Boolean = true,
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
    val takenAt: String? = null,
)

@Serializable
private data class WorkFailed(val error: String)

@Serializable
data class BackfillItem(val itemId: String, val originalKey: String, val displayWebpKey: String)

@Serializable
private data class DisplayWritten(val displayWebpKey: String, val takenAt: String? = null)

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

@Serializable
private data class ObservedObject(val key: String, val sizeBytes: Long, val ageSeconds: Long)

@Serializable
private data class ClassifyRequest(val objects: List<ObservedObject>)

@Serializable
private data class ClassifyResponse(val orphans: List<String>, val keptCount: Int)

@Serializable
private data class QuotaRepair(val accountsChecked: Int, val accountsCorrected: Int)

@Serializable
private data class AbandonedSweep(val removed: Int)

@Serializable
private data class TrashSweep(val removed: Int)

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
            var nextSweep = System.currentTimeMillis()
            while (true) {
                val processed = worker.tick()
                if (System.currentTimeMillis() >= nextSweep) {
                    runCatching { worker.reconcile() }
                        .onFailure { log.warn("reconciliation failed: {}", it.message) }
                    nextSweep = System.currentTimeMillis() + config.worker.reconcileInterval.toMillis()
                }
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
        val bundled = packBundles()
        // The backfill is what a worker does with nothing else to do: a photograph somebody just
        // uploaded matters more than one that has waited since before display WebPs existed.
        if (claimed.isNotEmpty() || bundled > 0) return claimed.size + bundled
        return backfill()
    }

    /**
     * Renders the display WebP for photographs backed up before every photograph had one. Each is
     * one download and one resize; the item stays backed up and in the grid throughout.
     */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    suspend fun backfill(): Int {
        val response =
            http.post("${config.publicBaseUrl}/api/worker/backfill/claim") {
                authenticate()
                contentType(ContentType.Application.Json)
                setBody(ClaimRequest(config.worker.batchSize))
            }
        if (!response.status.isSuccess()) {
            error("the API refused the backfill claim (${response.status}): ${response.bodyAsText()}")
        }
        val claimed: List<BackfillItem> = response.body()

        claimed.forEach { item ->
            val scratch: Path = Files.createTempDirectory("mantel-backfill-${item.itemId}")
            try {
                val original = scratch.resolve("original")
                storage.download(item.originalKey, original)
                val takenAt = photos.takenAt(original)
                storage.upload(item.displayWebpKey, photos.displayWebp(original, scratch), "image/webp")
                http.post("${config.publicBaseUrl}/api/worker/items/${item.itemId}/display") {
                    authenticate()
                    contentType(ContentType.Application.Json)
                    setBody(DisplayWritten(item.displayWebpKey, takenAt?.toString()))
                }
            } catch (failure: Exception) {
                log.warn("display WebP for {} failed: {}", item.itemId, failure.message)
                // Unreported, the claim times out and the photograph is offered again; that is
                // slower, not wrong, so a refused report must not take the worker down with it.
                runCatching {
                    http.post("${config.publicBaseUrl}/api/worker/items/${item.itemId}/display/failure") { authenticate() }
                }
            } finally {
                scratch.deleteRecursively()
            }
        }
        return claimed.size
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
                    // Read before any derivative is written: every derivative strips it.
                    val takenAt = photos.takenAt(original)?.toString()
                    if (!item.full) {
                        val copies = photos.libraryCopies(original, scratch)
                        storage.upload(item.thumbKey, copies.thumb, "image/webp")
                        storage.upload(item.displayWebpKey, copies.displayWebp, "image/webp")
                        report(
                            item,
                            "/derivatives",
                            DerivativesWritten(
                                thumbKey = item.thumbKey,
                                displayWebpKey = item.displayWebpKey,
                                width = copies.width,
                                height = copies.height,
                                takenAt = takenAt,
                            ),
                        )
                        return@process
                    }
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
                            takenAt = takenAt,
                        ),
                    )
                }
                "video" -> {
                    if (!item.full) {
                        // The poster frame is the thumbnail's source, so a video costs one decode
                        // to back up rather than a whole transcode.
                        val still = videos.posterOnly(original, scratch)
                        storage.upload(item.thumbKey, still.thumb, "image/webp")
                        storage.upload(item.posterKey, still.poster, "image/webp")
                        report(
                            item,
                            "/derivatives",
                            DerivativesWritten(
                                thumbKey = item.thumbKey,
                                posterKey = item.posterKey,
                                width = still.width,
                                height = still.height,
                                durationMs = still.durationMs,
                                takenAt = still.takenAt?.toString(),
                            ),
                        )
                        return@process
                    }
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
                            takenAt = rendered.takenAt?.toString(),
                        ),
                    )
                }
                else -> throw PipelineFailure("${item.kind} is not a kind this renders")
            }
        } finally {
            scratch.deleteRecursively()
        }
    }

    /**
     * Storage and the database cannot see each other, so something has to walk both (SDD.md 3.3).
     * The worker lists; the API decides. Nothing is deleted on the strength of a listing alone, and
     * an object younger than the grace period is never touched: it may belong to a row that is a
     * second from being committed.
     */
    suspend fun reconcile(): Int {
        var deleted = 0
        val now = java.time.Instant.now()

        for (prefix in listOf("media/", "bundles/", "public/og/")) {
            var after: String? = null
            do {
                val (objects, next) = storage.list(prefix, after)
                after = next
                if (objects.isEmpty()) continue

                val response =
                    http.post("${config.publicBaseUrl}/api/worker/reconcile/classify") {
                        authenticate()
                        contentType(ContentType.Application.Json)
                        setBody(
                            ClassifyRequest(
                                objects.map {
                                    ObservedObject(
                                        key = it.key,
                                        sizeBytes = it.sizeBytes,
                                        ageSeconds = java.time.Duration.between(it.lastModified, now).seconds,
                                    )
                                },
                            ),
                        )
                    }
                if (!response.status.isSuccess()) {
                    error("the API refused to classify objects (${response.status}): ${response.bodyAsText()}")
                }
                val orphans = response.body<ClassifyResponse>().orphans
                if (orphans.isNotEmpty()) {
                    log.info("reconciliation: deleting {} orphaned objects under {}", orphans.size, prefix)
                    storage.delete(orphans)
                    deleted += orphans.size
                }
            } while (after != null)
        }

        // Thirty days in the trash. Before the quota repair, like the next, so the space comes
        // back in the same sweep.
        val emptied =
            http.post("${config.publicBaseUrl}/api/worker/reconcile/trash") { authenticate() }
                .body<TrashSweep>()
        if (emptied.removed > 0) {
            log.info("reconciliation: removed {} items that spent 30 days in the trash", emptied.removed)
        }

        // Declared and never sent. It runs before the quota repair, so the space those rows held
        // is given back in the same sweep.
        val swept =
            http.post("${config.publicBaseUrl}/api/worker/reconcile/abandoned") { authenticate() }
                .body<AbandonedSweep>()
        if (swept.removed > 0) {
            log.info("reconciliation: removed {} uploads that were declared and never sent", swept.removed)
        }

        val repair =
            http.post("${config.publicBaseUrl}/api/worker/reconcile/quota") { authenticate() }
                .body<QuotaRepair>()
        if (repair.accountsCorrected > 0) {
            log.info("reconciliation: corrected storage used for {} accounts", repair.accountsCorrected)
        }
        return deleted
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
