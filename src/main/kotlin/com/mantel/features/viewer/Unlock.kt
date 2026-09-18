package com.mantel.features.viewer

import com.mantel.features.auth.setCookie
import com.mantel.features.share.PinHash
import com.mantel.features.share.ShareLinks
import com.mantel.features.share.ShareToken
import com.mantel.kernel.Clock
import com.mantel.kernel.Config
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.RateLimiter
import com.mantel.kernel.db
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.selectAll
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable
data class UnlockRequest(val pin: String)

private val UNLOCK_LIFETIME: Duration = Duration.ofHours(12)

fun unlockCookieName(token: ShareToken): String = "mantel_unlock_$token"

/**
 * The unlock cookie is a signed statement that this browser answered the PIN for this token. It
 * holds no identifier, it is scoped to that token's paths alone, and it is the only cookie a viewer
 * ever receives (SDD.md 4.3). Nothing about the viewer is stored anywhere.
 */
private fun sign(
    secret: String,
    token: ShareToken,
    expiresAtEpoch: Long,
): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    val digest = mac.doFinal("$token|$expiresAtEpoch".toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

fun ApplicationCall.hasUnlocked(
    token: ShareToken,
    secret: String,
    clock: Clock,
): Boolean {
    val cookie = request.cookies[unlockCookieName(token)] ?: return false
    val expiresAt = cookie.substringBefore('-').toLongOrNull() ?: return false
    val signature = cookie.substringAfter('-', "")
    if (Instant.ofEpochSecond(expiresAt).isBefore(clock.now())) return false
    val expected = sign(secret, token, expiresAt)
    // Constant time: a timing oracle on a signature is a way to forge one.
    return java.security.MessageDigest.isEqual(expected.toByteArray(), signature.toByteArray())
}

/**
 * The PIN is the only defence that survives a leaked URL, so attempts are rate limited per token and
 * per address. A wrong PIN says only that it is wrong.
 */
suspend fun unlock(
    call: ApplicationCall,
    config: Config,
    limiter: RateLimiter,
    clock: Clock = Clock.system,
) {
    val token = tokenFrom(call)
    val attempt = call.receive<UnlockRequest>().pin.trim()

    if (!limiter.tryConsume("$token|${call.request.origin.remoteAddress}")) {
        throw DomainException(ErrorCode.RATE_LIMITED, "Too many attempts. Wait a few minutes.")
    }

    val pinHash =
        db {
            ShareLinks.selectAll().where { ShareLinks.token eq token }.singleOrNull()?.get(ShareLinks.pinHash)
        } ?: throw DomainException(ErrorCode.NOT_FOUND, "No such album")

    if (!PinHash.verify(pinHash, attempt)) {
        throw DomainException(ErrorCode.VALIDATION_FAILED, "That PIN is not right")
    }

    val expiresAt = clock.now().plus(UNLOCK_LIFETIME).epochSecond
    call.setCookie(
        name = unlockCookieName(token),
        value = "$expiresAt-${sign(config.cookieSecret, token, expiresAt)}",
        maxAgeSeconds = UNLOCK_LIFETIME.seconds,
        path = "/api/share/$token",
    )
    call.respond(HttpStatusCode.NoContent)
}
