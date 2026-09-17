package com.mantel.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Quota arithmetic, with no database and no HTTP (SDD.md 12). */
class QuotaTest {
    private val tenBytes = Quota(limit = Bytes(10), used = Bytes.NONE)

    @Test
    fun `remaining is what the limit has left`() {
        assertEquals(Bytes(10), tenBytes.remaining)
        assertEquals(Bytes(4), tenBytes.reserve(Bytes(6)).remaining)
    }

    @Test
    fun `a batch exactly the size of the remainder fits`() {
        assertTrue(tenBytes.fits(Bytes(10)))
        assertFalse(tenBytes.fits(Bytes(11)))
        assertEquals(Bytes.NONE, tenBytes.reserve(Bytes(10)).remaining)
    }

    @Test
    fun `reserving twice cannot spend the same space`() {
        val afterFirst = tenBytes.reserve(Bytes(8))
        assertFalse(afterFirst.fits(Bytes(8)))
        assertThrows(IllegalArgumentException::class.java) { afterFirst.reserve(Bytes(8)) }
    }

    @Test
    fun `releasing returns the space`() {
        val after = tenBytes.reserve(Bytes(7)).release(Bytes(7))
        assertEquals(Bytes.NONE, after.used)
        assertEquals(Bytes(10), after.remaining)
    }

    @Test
    fun `releasing more than was reserved lands on zero, not below it`() {
        val after = tenBytes.reserve(Bytes(3)).release(Bytes(9))
        assertEquals(Bytes.NONE, after.used)
    }

    @Test
    fun `an account already over its limit has nothing remaining`() {
        val over = Quota(limit = Bytes(10), used = Bytes(25))
        assertEquals(Bytes.NONE, over.remaining)
        assertFalse(over.fits(Bytes(1)))
    }

    @Test
    fun `bytes cannot be negative, and a declared file cannot be empty`() {
        assertThrows(IllegalArgumentException::class.java) { Bytes(-1) }
        assertThrows(IllegalArgumentException::class.java) { Bytes.of(0) }
        assertEquals(Bytes(5), Bytes.of(5))
    }
}
