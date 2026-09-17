package com.mantel.kernel

/**
 * Every configuration value the product reads, resolved once at startup.
 * A value belongs here only when a deployment will set it to something other than the default.
 */
data class Config(
    val port: Int,
    val publicBaseUrl: String,
    val defaultQuotaBytes: Long,
    val database: DatabaseConfig,
    val storage: StorageConfig,
    val smtp: SmtpConfig?,
    val github: GitHubConfig?,
) {
    companion object {
        fun fromEnvironment(env: (String) -> String? = System::getenv): Config =
            Config(
                port = env("MANTEL_PORT")?.toInt() ?: 8080,
                publicBaseUrl = env("MANTEL_PUBLIC_BASE_URL") ?: "http://localhost:8080",
                // One number, no tiers. SDD.md 14.1 leaves pricing open; this is not it.
                defaultQuotaBytes = env("MANTEL_DEFAULT_QUOTA_BYTES")?.toLong() ?: (10L * 1024 * 1024 * 1024),
                database =
                    DatabaseConfig(
                        url = env("MANTEL_DB_URL") ?: "jdbc:postgresql://localhost:5432/mantel",
                        user = env("MANTEL_DB_USER") ?: "mantel",
                        password = env("MANTEL_DB_PASSWORD") ?: "mantel",
                    ),
                storage =
                    StorageConfig(
                        endpoint = env("MANTEL_S3_ENDPOINT") ?: "http://localhost:9100",
                        region = env("MANTEL_S3_REGION") ?: "auto",
                        bucket = env("MANTEL_S3_BUCKET") ?: "mantel",
                        accessKeyId = env("MANTEL_S3_ACCESS_KEY_ID") ?: "mantel",
                        secretAccessKey = env("MANTEL_S3_SECRET_ACCESS_KEY") ?: "mantel-development",
                        forcePathStyle = env("MANTEL_S3_FORCE_PATH_STYLE")?.toBoolean() ?: true,
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
    val region: String,
    val bucket: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val forcePathStyle: Boolean,
)

data class SmtpConfig(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val from: String,
    val startTls: Boolean,
)

data class GitHubConfig(
    val clientId: String,
    val clientSecret: String,
    val authorizeUrl: String,
    val tokenUrl: String,
    val apiBaseUrl: String,
)
