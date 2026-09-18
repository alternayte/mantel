package com.mantel.kernel

import java.time.Duration

/**
 * Every configuration value the product reads, resolved once at startup.
 * A value belongs here only when a deployment will set it to something other than the default.
 */
data class Config(
    val port: Int,
    val publicBaseUrl: String,
    val defaultQuota: Bytes,
    val database: DatabaseConfig,
    val storage: StorageConfig,
    val smtp: SmtpConfig?,
    val github: GitHubConfig?,
    val worker: WorkerConfig,
) {
    companion object {
        fun fromEnvironment(env: (String) -> String? = Dotenv::lookup): Config =
            Config(
                port = env("MANTEL_PORT")?.toInt() ?: 8080,
                publicBaseUrl = env("MANTEL_PUBLIC_BASE_URL") ?: "http://localhost:8080",
                // One number, no tiers. SDD.md 14.1 leaves pricing open; this is not it.
                defaultQuota = Bytes(env("MANTEL_DEFAULT_QUOTA_BYTES")?.toLong() ?: (10L * 1024 * 1024 * 1024)),
                database =
                    DatabaseConfig(
                        url = env("MANTEL_DB_URL") ?: "jdbc:postgresql://localhost:5432/mantel",
                        user = env("MANTEL_DB_USER") ?: "mantel",
                        password = env("MANTEL_DB_PASSWORD") ?: "mantel",
                    ),
                storage =
                    StorageConfig(
                        endpoint = env("MANTEL_S3_ENDPOINT") ?: "http://localhost:9100",
                        // A presigned URL is opened by a browser, not by this server. In compose the
                        // two differ: the app reaches storage at http://minio:9000 and the browser
                        // cannot. With R2 or S3 they are the same and this stays unset.
                        publicEndpoint = env("MANTEL_S3_PUBLIC_ENDPOINT"),
                        region = env("MANTEL_S3_REGION") ?: "auto",
                        bucket = env("MANTEL_S3_BUCKET") ?: "mantel",
                        accessKeyId = env("MANTEL_S3_ACCESS_KEY_ID") ?: "mantel",
                        secretAccessKey = env("MANTEL_S3_SECRET_ACCESS_KEY") ?: "mantel-development",
                        forcePathStyle = env("MANTEL_S3_FORCE_PATH_STYLE")?.toBoolean() ?: true,
                        // Above this a file uploads in parts, so a dropped connection costs one
                        // part rather than the whole file. Below it, one PUT and one round trip.
                        multipartThreshold =
                            Bytes(env("MANTEL_MULTIPART_THRESHOLD_BYTES")?.toLong() ?: (64L * 1024 * 1024)),
                        partSize = Bytes(env("MANTEL_MULTIPART_PART_BYTES")?.toLong() ?: (16L * 1024 * 1024)),
                    ),
                // No SMTP host: magic links go to the log, which is what local work reads.
                smtp =
                    env("MANTEL_SMTP_HOST")?.let { host ->
                        SmtpConfig(
                            host = host,
                            port = env("MANTEL_SMTP_PORT")?.toInt() ?: 587,
                            username = env("MANTEL_SMTP_USERNAME"),
                            password = env("MANTEL_SMTP_PASSWORD"),
                            from = env("MANTEL_SMTP_FROM") ?: "mantel@localhost",
                            startTls = env("MANTEL_SMTP_STARTTLS")?.toBoolean() ?: true,
                        )
                    },
                worker =
                    WorkerConfig(
                        // The worker authenticates to the API with this and holds no database
                        // credentials. Without it the worker endpoints are closed.
                        token = env("MANTEL_WORKER_TOKEN"),
                        claimTimeout = Duration.ofSeconds(env("MANTEL_CLAIM_TIMEOUT_SECONDS")?.toLong() ?: 600),
                        maxAttempts = env("MANTEL_MAX_ATTEMPTS")?.toInt() ?: 3,
                        batchSize = env("MANTEL_WORKER_BATCH")?.toInt() ?: 4,
                        pollInterval = Duration.ofSeconds(env("MANTEL_WORKER_POLL_SECONDS")?.toLong() ?: 5),
                    ),
                github =
                    env("MANTEL_GITHUB_CLIENT_ID")?.let { clientId ->
                        GitHubConfig(
                            clientId = clientId,
                            clientSecret =
                                env("MANTEL_GITHUB_CLIENT_SECRET")
                                    ?: error("MANTEL_GITHUB_CLIENT_ID is set without MANTEL_GITHUB_CLIENT_SECRET"),
                            authorizeUrl = env("MANTEL_GITHUB_AUTHORIZE_URL") ?: "https://github.com/login/oauth/authorize",
                            tokenUrl = env("MANTEL_GITHUB_TOKEN_URL") ?: "https://github.com/login/oauth/access_token",
                            apiBaseUrl = env("MANTEL_GITHUB_API_BASE_URL") ?: "https://api.github.com",
                        )
                    },
            )
    }
}

data class DatabaseConfig(val url: String, val user: String, val password: String)

data class StorageConfig(
    val endpoint: String,
    val publicEndpoint: String? = null,
    val region: String,
    val bucket: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val forcePathStyle: Boolean,
    val multipartThreshold: Bytes,
    val partSize: Bytes,
) {
    init {
        // S3 refuses any part but the last below 5 MiB, so a smaller part size cannot be uploaded.
        require(partSize.value >= 5L * 1024 * 1024) { "a part is at least 5 MiB" }
    }
}

data class SmtpConfig(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val from: String,
    val startTls: Boolean,
)

/**
 * A claim older than claimTimeout is presumed abandoned and returns to the queue, which is how a
 * crashed worker's item is recovered. It must be longer than the slowest job, or a running job is
 * claimed twice.
 */
data class WorkerConfig(
    val token: String?,
    val claimTimeout: Duration,
    val maxAttempts: Int,
    val batchSize: Int,
    val pollInterval: Duration,
)

data class GitHubConfig(
    val clientId: String,
    val clientSecret: String,
    val authorizeUrl: String,
    val tokenUrl: String,
    val apiBaseUrl: String,
)
