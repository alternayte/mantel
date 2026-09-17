package com.mantel.kernel

import java.nio.file.Files
import java.nio.file.Path

/**
 * Configuration comes from the environment. In development it comes from a .env file, because the
 * Gradle daemon does not inherit the shell that launched it and a value that silently fails to
 * arrive is worse than no value at all.
 *
 * A real environment variable always wins, so a container is unaffected by a stray file.
 */
object Dotenv {
    private val values: Map<String, String> by lazy { read(Path.of(".env")) }

    fun read(file: Path): Map<String, String> {
        if (!Files.isRegularFile(file)) return emptyMap()
        return Files.readAllLines(file)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .associate { line ->
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
                key to value
            }
    }

    fun lookup(key: String): String? = System.getenv(key) ?: values[key]?.ifEmpty { null }
}
