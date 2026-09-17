package com.mantel.kernel

/**
 * Every configuration value the product reads, resolved once at startup.
 * A value belongs here only when a deployment will set it to something other than the default.
 */
data class Config(
    val port: Int,
    val publicBaseUrl: String,
    val database: DatabaseConfig,
    val storage: StorageConfig,
) {
    companion object {
        fun fromEnvironment(env: (String) -> String? = System::getenv): Config =
            Config(
                port = env("MANTEL_PORT")?.toInt() ?: 8080,
                publicBaseUrl = env("MANTEL_PUBLIC_BASE_URL") ?: "http://localhost:8080",
                database =
                    DatabaseConfig(
                        url = env("MANTEL_DB_URL") ?: "jdbc:postgresql://localhost:5432/mantel",
                        user = env("MANTEL_DB_USER") ?: "mantel",
                        password = env("MANTEL_DB_PASSWORD") ?: "mantel",
                    ),
                storage =
                    StorageConfig(
                        endpoint = env("MANTEL_S3_ENDPOINT") ?: "http://localhost:9000",
                        region = env("MANTEL_S3_REGION") ?: "auto",
                        bucket = env("MANTEL_S3_BUCKET") ?: "mantel",
                        accessKeyId = env("MANTEL_S3_ACCESS_KEY_ID") ?: "mantel",
                        secretAccessKey = env("MANTEL_S3_SECRET_ACCESS_KEY") ?: "mantel-development",
                        forcePathStyle = env("MANTEL_S3_FORCE_PATH_STYLE")?.toBoolean() ?: true,
                    ),
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
