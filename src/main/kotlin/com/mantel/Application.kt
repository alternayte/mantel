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
 */
fun main(args: Array<String>) {
    val config = Config.fromEnvironment()
    when {
        args.contains("--worker") -> runWorker(config)
        args.contains("--migrate") -> Schema.migrate(Schema.dataSource(config.database))
        else -> {
            val dataSource = Schema.dataSource(config.database)
            Schema.migrate(dataSource)
            Schema.connect(dataSource)
            val storage = S3ObjectStorage(config.storage)
            storage.ensureIncompleteUploadsExpire(afterDays = 1)
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
