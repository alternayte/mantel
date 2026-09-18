package com.mantel.features.share

import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import de.mkammerer.argon2.Argon2Factory

/**
 * The token travels in the URL and leaks with it. The PIN does not, which is the entire reason the
 * product has one (SDD.md 4.3). It is four to twelve digits, so it is guessable by anyone who can
 * try often enough: Argon2id makes each guess expensive and the rate limit makes trying often hard.
 */
@JvmInline
value class Pin private constructor(val value: String) {
    companion object {
        private val DIGITS = Regex("^[0-9]{4,12}$")

        fun of(raw: String?): Pin? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            if (!DIGITS.matches(trimmed)) {
                throw DomainException(ErrorCode.VALIDATION_FAILED, "A PIN is 4 to 12 digits")
            }
            return Pin(trimmed)
        }
    }
}

object PinHash {
    // OWASP's Argon2id parameters for a second factor: 19 MiB, two passes, one lane.
    private const val MEMORY_KIB = 19 * 1024
    private const val ITERATIONS = 2
    private const val PARALLELISM = 1

    private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)

    fun hash(pin: Pin): String = argon2.hash(ITERATIONS, MEMORY_KIB, PARALLELISM, pin.value.toCharArray())

    fun verify(
        hash: String,
        attempt: String,
    ): Boolean = argon2.verify(hash, attempt.toCharArray())
}
