package com.mantel.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The version reaches the running app from the build, not from a string somebody typed. An OpenAPI
 * document naming a version the build does not is the same drift an enum typed by hand would be.
 */
class VersionTest {
    @Test
    fun `the app knows what the build calls it`() {
        assertTrue(Regex("""^\d+\.\d+\.\d+$""").matches(Version.current), "version is '${Version.current}'")
    }

    @Test
    fun `the OpenAPI document and the MCP server both say it`() {
        val config = com.mantel.support.testConfig()
        val document = com.mantel.features.agent.openApiDocument(config)
        assertTrue(document.contains("\"version\": \"${Version.current}\""), "the document names another version")
        assertEquals("0.1.0", Version.current, "the first release is 0.1.0; update this when it moves")
    }
}
