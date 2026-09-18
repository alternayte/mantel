package com.mantel.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The address a self-hoster types. It is the one field between a fresh install and everything else,
 * and it is typed on a phone keyboard, so what it accepts is worth pinning.
 */
class ServerAddressTest {
    @Test
    fun `a bare host gets https`() {
        assertEquals("https://albums.example.com", normalise("albums.example.com"))
    }

    @Test
    fun `a trailing slash and stray spaces go`() {
        assertEquals("https://albums.example.com", normalise("  https://albums.example.com/  "))
    }

    @Test
    fun `an explicit scheme is kept, including http for a local instance`() {
        assertEquals("http://localhost:8080", normalise("http://localhost:8080"))
    }

    @Test
    fun `a word is not an address`() {
        assertNull(normalise("albums"))
        assertNull(normalise(""))
    }
}
