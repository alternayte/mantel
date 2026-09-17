package com.mantel

import com.mantel.http.startServer
import com.mantel.kernel.Config
import com.mantel.kernel.Schema
import com.mantel.worker.runWorker

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
            startServer(config)
        }
    }
}
