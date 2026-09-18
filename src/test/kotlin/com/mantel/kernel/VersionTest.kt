package com.mantel.kernel

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionTest {
    @Test
    fun `the build's version reaches the app, expanded`() {
        // The template is filled by Gradle. It has shipped unexpanded once already.
        assertTrue(Regex("""^\d+\.\d+\.\d+.*$""").matches(Version.current), "version is '${Version.current}'")
    }
}
