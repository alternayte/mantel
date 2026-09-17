package com.mantel.kernel

/**
 * The closed error-code set. `code` is contract and may not change; `message` is for humans.
 * SDD.md 6.4. Every new code is documented in docs/api.md in the same commit that adds it.
 */
enum class ErrorCode(val status: Int) {
    NOT_FOUND(404),
    UNAUTHENTICATED(401),
    FORBIDDEN(403),
    VALIDATION_FAILED(422),
    CONFLICT(409),
    RATE_LIMITED(429),
    INTERNAL(500),
    ;

    val wire: String get() = name.lowercase()
}

class DomainException(
    val code: ErrorCode,
    override val message: String,
    val details: Map<String, String> = emptyMap(),
) : RuntimeException(message)
