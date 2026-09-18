package com.mantel.kernel

import java.util.Properties

/** What this build calls itself. Gradle writes it; the API document and the MCP server read it. */
object Version {
    val current: String by lazy {
        Version::class.java.getResourceAsStream("/version.properties")?.use { stream ->
            Properties().apply { load(stream) }.getProperty("version")
        } ?: "unknown"
    }
}
