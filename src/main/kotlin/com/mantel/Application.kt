package com.mantel

import com.mantel.http.Services
import com.mantel.http.startServer
import com.mantel.kernel.Config
import com.mantel.kernel.Schema
import com.mantel.kernel.mailerFor
import com.mantel.storage.S3ObjectStorage
import com.mantel.worker.runWorker
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * One binary, two run modes. `--worker` renders derivatives; the default mode serves the API,
 * the OG shell and the SPA.
 *
 * The mode is also readable from `MANTEL_ROLE`, because several container platforms make the image
 * command awkward to override and silently run the entrypoint instead. A worker that quietly starts
 * as a second API server is the failure that costs an afternoon: nothing errors, and no photograph
 * is ever rendered.
 */
fun main(args: Array<String>) {
    val config = Config.fromEnvironment()
    val role = System.getenv("MANTEL_ROLE")?.trim()?.lowercase()
    when {
        args.contains("--worker") || role == "worker" -> runWorker(config)
        args.contains("--migrate") || role == "migrate" -> Schema.migrate(Schema.dataSource(config.database))
        else -> {
            val dataSource = Schema.dataSource(config.database)
            Schema.migrate(dataSource)
            Schema.connect(dataSource)
            val storage = S3ObjectStorage(config.storage)
            storage.ensureIncompleteUploadsExpire(afterDays = 1)
            // Link previews are fetched by crawlers with no credentials, so the covers copied here
            // have to be readable without a signature (SDD.md 4.3).
            storage.makePrefixPublic(com.mantel.features.share.PUBLIC_PREFIX)
            val httpClient =
                HttpClient {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            startServer(
                Services(
                    config = config,
                    storage = storage,
                    mailer = mailerFor(config.smtp),
                    httpClient = httpClient,
                ),
            )
        }
    }
}
