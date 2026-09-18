package com.mantel.app.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The proof that the client exchanging a code is the client that started the sign-in.
 *
 * The verifier never leaves the device until the exchange; only its digest travels through the
 * browser. A code intercepted on its way back is therefore worth nothing on its own.
 */
object Pkce {
    private val random = SecureRandom()

    private fun base64Url(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun verifier(): String = base64Url(ByteArray(32).also(random::nextBytes))

    fun challenge(verifier: String): String = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
}
