package com.mantel.kernel

import java.security.SecureRandom

/**
 * Unguessable public identifiers. The share-link token is 12 characters of this alphabet,
 * which is about 71 bits (SDD.md 4.3).
 */
object Ids {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZabcdefghijkmnpqrstvwxyz"
    private val random = SecureRandom()

    fun token(length: Int = 12): String {
        val out = StringBuilder(length)
        repeat(length) { out.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return out.toString()
    }
}
