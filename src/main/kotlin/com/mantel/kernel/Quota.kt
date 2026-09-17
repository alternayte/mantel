package com.mantel.kernel

/**
 * An account's storage allowance, as arithmetic that cannot go wrong quietly. Reserving happens
 * before bytes move and releasing happens when an item is removed, so the two must agree; doing it
 * with bare Longs in two handlers is how they stop agreeing.
 */
@JvmInline
value class Bytes(val value: Long) : Comparable<Bytes> {
    init {
        require(value >= 0) { "bytes cannot be negative: $value" }
    }

    operator fun plus(other: Bytes) = Bytes(value + other.value)

    /** Never below zero: releasing more than was reserved is drift, not a negative account. */
    operator fun minus(other: Bytes) = Bytes((value - other.value).coerceAtLeast(0))

    override fun compareTo(other: Bytes) = value.compareTo(other.value)

    companion object {
        val NONE = Bytes(0)

        fun of(value: Long): Bytes {
            require(value > 0) { "a file declares more than zero bytes" }
            return Bytes(value)
        }
    }
}

data class Quota(val limit: Bytes, val used: Bytes) {
    val remaining: Bytes get() = limit - used

    fun fits(request: Bytes): Boolean = request <= remaining

    fun reserve(request: Bytes): Quota {
        require(fits(request)) { "quota checked before reserving" }
        return copy(used = used + request)
    }

    fun release(amount: Bytes): Quota = copy(used = used - amount)
}
