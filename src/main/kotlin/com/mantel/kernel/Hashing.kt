package com.mantel.kernel

import java.security.MessageDigest

/**
 * Secrets that travel (a magic-link token, a session cookie) are stored as a hash, so a database
 * leak cannot be replayed as a sign-in. These are high-entropy random tokens, not passwords: a fast
 * digest is the right tool. A PIN is not, and uses Argon2id at M5.
 */
fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
