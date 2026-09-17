package com.mantel.convention

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

/**
 * The structure of SDD.md section 13, asserted. These fail the build on architectural drift, which
 * is the only thing that reliably stops vertical slices decaying into layers.
 */
class ConventionTest {
    private val repoRoot: Path = Path.of("").toAbsolutePath()
    private val serverRoot: Path = repoRoot.resolve("src/main/kotlin/com/mantel")
    private val webRoot: Path = repoRoot.resolve("web/src")

    private fun sourcesUnder(
        dir: Path,
        extensions: Set<String>,
    ): List<Path> =
        if (!Files.isDirectory(dir)) {
            emptyList()
        } else {
            Files.walk(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.extension in extensions }.toList()
            }
        }

    private fun kotlinSources(dir: Path) = sourcesUnder(dir, setOf("kt"))

    private fun webSources(dir: Path) = sourcesUnder(dir, setOf("ts", "tsx"))

    @Test
    fun `every feature package of the design exists`() {
        val expected = listOf("account", "auth", "album", "media", "share", "viewer", "agent")
        val missing = expected.filterNot { Files.isDirectory(serverRoot.resolve("features/$it")) }
        assertTrue(missing.isEmpty()) { "missing feature packages: $missing" }
    }

    @Test
    fun `the kernel, storage, http and worker packages exist`() {
        val missing =
            listOf("kernel", "storage", "http", "worker")
                .filterNot { Files.isDirectory(serverRoot.resolve(it)) }
        assertTrue(missing.isEmpty()) { "missing packages: $missing" }
    }

    @Test
    fun `no landfill package`() {
        val banned = setOf("common", "utils", "util", "helpers", "shared", "misc")
        val offenders =
            listOf(serverRoot, webRoot)
                .filter { Files.isDirectory(it) }
                .flatMap { root ->
                    Files.walk(root).use { stream ->
                        stream.filter { Files.isDirectory(it) && it.name in banned }
                            .map { it.relativeTo(repoRoot).toString() }
                            .toList()
                    }
                }
        assertTrue(offenders.isEmpty()) {
            "a package named for what it is not: $offenders. Name it for the feature it serves."
        }
    }

    @Test
    fun `the kernel does not know about features`() {
        val offenders =
            kotlinSources(serverRoot.resolve("kernel"))
                .filter { it.readText().contains("import com.mantel.features") }
                .map { it.relativeTo(repoRoot).toString() }
        assertTrue(offenders.isEmpty()) { "kernel imports a feature: $offenders" }
    }

    @Test
    fun `the worker holds no database access`() {
        val databaseTypes = listOf("org.jetbrains.exposed", "org.flywaydb", "java.sql", "javax.sql", "com.zaxxer")
        val offenders =
            kotlinSources(serverRoot.resolve("worker"))
                .filter { file -> databaseTypes.any { file.readText().contains("import $it") } }
                .map { it.relativeTo(repoRoot).toString() }
        assertTrue(offenders.isEmpty()) {
            "the worker has no database credentials by design (SDD.md 6.3): $offenders"
        }
    }

    @Test
    fun `no banned construct in a type name`() {
        val banned = Regex("(class|interface|object)\\s+\\w*(Repository|Mediator|Mapper|ServiceImpl)\\b")
        val offenders =
            kotlinSources(serverRoot)
                .filter { banned.containsMatchIn(it.readText()) }
                .map { it.relativeTo(repoRoot).toString() }
        assertTrue(offenders.isEmpty()) { "banned construct (SDD.md 13): $offenders" }
    }

    @Test
    fun `web route files route and nothing else`() {
        val dataAccess = Regex("\\bfetch\\(|axios|useMutation\\(")
        val offenders =
            webSources(webRoot.resolve("routes"))
                .filter { dataAccess.containsMatchIn(it.readText()) }
                .map { it.relativeTo(repoRoot).toString() }
        assertTrue(offenders.isEmpty()) {
            "a route file reaches for data: $offenders. A route resolves params, calls a loader and renders a feature component."
        }
    }
}
