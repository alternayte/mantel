package com.mantel.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IdsTest {
    @Test
    fun `a share token is twelve characters from an unambiguous alphabet`() {
        val tokens = List(10_000) { Ids.token() }
        assertEquals(setOf(12), tokens.map { it.length }.toSet())
        assertEquals(10_000, tokens.toSet().size, "tokens must not repeat")
        val alphabet = Regex("^[0-9A-HJKMNP-TV-Za-km-np-z]+$")
        assertEquals(emptyList<String>(), tokens.filterNot { alphabet.matches(it) })
    }
}
