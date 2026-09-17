package com.mantel.kernel

import java.time.Instant

/** Time as a value, so expiry and claim-timeout logic is testable without sleeping. */
fun interface Clock {
    fun now(): Instant

    companion object {
        val system = Clock { Instant.now() }
    }
}
