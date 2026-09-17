package com.mantel.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class IdsTest {
    @Test
    fun `a share token is twelve characters from an unambiguous alphabet`() {
        val tokens = List(10_000) { Ids.token() }
        assertEquals(setOf(12), tokens.map { it.length }.toSet())
        assertEquals(10_000, tokens.toSet().size, "tokens must not repeat")
        val alphabet = Regex("^[0-9A-HJKMNP-TV-Za-km-np-z]+$")
        assertEquals(emptyList<String>(), tokens.filterNot { alphabet.matches(it) })
    }

    @Test
    fun `a row id is a valid version 7 UUID`() {
        repeat(1_000) {
            val id = Ids.uuidV7()
            assertEquals(7, id.version(), "version")
            assertEquals(2, id.variant(), "variant")
        }
    }

    @Test
    fun `row ids sort in the order they were created`() {
        var millis = 1_700_000_000_000L
        val clock = Clock { Instant.ofEpochMilli(millis) }
        val ids =
            List(500) {
                millis += 1
                Ids.uuidV7(clock)
            }
        assertEquals(ids, ids.sortedBy { it.toString() }, "string order must follow creation order")
        assertEquals(ids, ids.sortedWith(compareBy { it.mostSignificantBits }), "index order too")
    }

    @Test
    fun `ids made in the same millisecond are still distinct`() {
        val clock = Clock { Instant.ofEpochMilli(1_700_000_000_000L) }
        val ids = List(10_000) { Ids.uuidV7(clock) }
        assertEquals(10_000, ids.toSet().size)
    }

    @Test
    fun `the timestamp is the creation time`() {
        val millis = 1_700_000_123_456L
        val id = Ids.uuidV7(Clock { Instant.ofEpochMilli(millis) })
        assertTrue((id.mostSignificantBits ushr 16) == millis, "timestamp bits")
    }
}
