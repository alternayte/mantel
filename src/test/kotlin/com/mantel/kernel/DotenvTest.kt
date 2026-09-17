package com.mantel.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Files

class DotenvTest {
    @Test
    fun `reads pairs and ignores comments, blanks and quotes`() {
        val file = Files.createTempFile("dotenv", ".env")
        Files.writeString(
            file,
            """
            # a comment
            MANTEL_PORT=8080

            MANTEL_SMTP_FROM="mantel@example.com"
            MANTEL_DB_PASSWORD='secret=with=equals'
            NOT_A_PAIR
            """.trimIndent(),
        )

        val values = Dotenv.read(file)

        assertEquals("8080", values["MANTEL_PORT"])
        assertEquals("mantel@example.com", values["MANTEL_SMTP_FROM"])
        assertEquals("secret=with=equals", values["MANTEL_DB_PASSWORD"])
        assertNull(values["NOT_A_PAIR"])
    }

    @Test
    fun `a missing file is not an error`() {
        assertEquals(emptyMap<String, String>(), Dotenv.read(java.nio.file.Path.of("no-such.env")))
    }

    @Test
    fun `configuration reads what the file provides`() {
        val file = Files.createTempFile("dotenv-config", ".env")
        Files.writeString(file, "MANTEL_WORKER_TOKEN=from-file\nMANTEL_PORT=9999\n")
        val values = Dotenv.read(file)

        val config = Config.fromEnvironment { values[it] }

        assertEquals(9999, config.port)
        assertEquals("from-file", config.worker.token)
    }
}
