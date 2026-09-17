package com.mantel.worker

import com.mantel.kernel.Config
import org.slf4j.LoggerFactory

/**
 * The same binary in worker mode. It reads originals from object storage, writes derivatives, and
 * reports completion to the API over HTTP. It holds no database credentials (SDD.md 6.3 step 7),
 * which is why nothing here may import a JDBC, Exposed or Flyway type; ConventionTest enforces it.
 *
 * The claim loop and the media pipelines arrive with M3 and M4.
 */
private val log = LoggerFactory.getLogger("com.mantel.worker")

fun runWorker(config: Config) {
    log.info("worker mode against {}", config.publicBaseUrl)
    error("Worker mode has no pipeline yet. It is built in M3 (photos) and M4 (video).")
}
