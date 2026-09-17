package com.mantel.kernel

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A token bucket per key, in memory. Correct for one instance, which is the deployment SDD.md 11
 * describes. A second replica needs this in Postgres, and that move is documented when it happens.
 */
class RateLimiter(
    private val capacity: Int,
    private val refillPeriod: Duration,
    private val clock: Clock = Clock.system,
) {
    private data class Bucket(val tokens: Double, val updatedAt: Instant)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** True when the call is allowed and a token was spent. */
    fun tryConsume(key: String): Boolean {
        val now = clock.now()
        var allowed = false
        buckets.compute(key) { _, existing ->
            val bucket = existing ?: Bucket(capacity.toDouble(), now)
            val elapsed = Duration.between(bucket.updatedAt, now).toMillis().coerceAtLeast(0)
            val refilled =
                (bucket.tokens + elapsed.toDouble() * capacity / refillPeriod.toMillis())
                    .coerceAtMost(capacity.toDouble())
            if (refilled >= 1.0) {
                allowed = true
                Bucket(refilled - 1.0, now)
            } else {
                Bucket(refilled, now)
            }
        }
        return allowed
    }
}
