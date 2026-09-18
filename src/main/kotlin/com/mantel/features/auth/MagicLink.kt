package com.mantel.features.auth

import com.mantel.features.account.AccountId
import com.mantel.features.account.Accounts
import com.mantel.kernel.Bytes
import com.mantel.kernel.Clock
import com.mantel.kernel.Config
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.Ids
import com.mantel.kernel.Mail
import com.mantel.kernel.Mailer
import com.mantel.kernel.RateLimiter
import com.mantel.kernel.db
import com.mantel.kernel.sha256Hex
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

object MagicLinks : Table("magic_link") {
    val tokenHash = text("token_hash")
    val accountId = reference("account_id", Accounts.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION)
    val expiresAt = timestampWithTimeZone("expires_at")
    val consumedAt = timestampWithTimeZone("consumed_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(tokenHash)
}

/**
 * A native client sends a PKCE challenge with the request. The link it receives is the same link;
 * what changes is where consuming it sends the browser (NativeSignIn.kt).
 */
@Serializable
data class MagicLinkRequest(val email: String, val challenge: String? = null)

private val LINK_LIFETIME: Duration = Duration.ofMinutes(15)
private val EMAIL = Regex("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$")

/**
 * Requesting a link is also how an account is created: there is no separate sign-up, and the
 * response is the same either way, so the endpoint does not report whether an email is known.
 */
suspend fun requestMagicLink(
    call: ApplicationCall,
    config: Config,
    mailer: Mailer,
    limiter: RateLimiter,
    clock: Clock = Clock.system,
) {
    val request = call.receive<MagicLinkRequest>()
    val email = request.email.trim()
    val challenge = validChallenge(request.challenge)
    if (!EMAIL.matches(email)) {
        throw DomainException(ErrorCode.VALIDATION_FAILED, "That is not an email address")
    }
    if (!limiter.tryConsume(email.lowercase())) {
        throw DomainException(ErrorCode.RATE_LIMITED, "Too many links requested for this address. Wait an hour.")
    }

    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    val secret = Ids.token(32)
    val accountId =
        db {
            val existing =
                Accounts
                    .selectAll()
                    .where { (Accounts.email.lowerCase() eq email.lowercase()) and Accounts.deletedAt.isNull() }
                    .singleOrNull()
                    ?.get(Accounts.id)
            val id = existing ?: AccountId(Ids.uuidV7(clock))
            if (existing == null) {
                Accounts.insert {
                    it[Accounts.id] = id
                    it[Accounts.email] = email
                    it[storageQuotaBytes] = config.defaultQuota
                    it[storageUsedBytes] = Bytes.NONE
                    it[createdAt] = now
                }
            }
            MagicLinks.insert {
                it[tokenHash] = sha256Hex(secret)
                it[MagicLinks.accountId] = id
                it[expiresAt] = now.plus(LINK_LIFETIME)
                it[createdAt] = now
            }
            if (challenge != null) openNativeFlow(secret, challenge, now)
            id
        }

    val link = "${config.publicBaseUrl}/api/auth/magic-link/callback?token=$secret"
    mailer.send(
        Mail(
            to = email,
            subject = "Your Mantel sign-in link",
            body =
                """
                Open this link to sign in. It works once and expires in 15 minutes.

                $link

                If you did not ask for it, ignore this mail. Nothing happens until the link is opened.
                """.trimIndent(),
        ),
    )
    check(accountId != null)
    call.respond(HttpStatusCode.Accepted, mapOf("status" to "sent"))
}

/** Consuming a link signs the browser in and sends it to the creator app. */
suspend fun consumeMagicLink(
    call: ApplicationCall,
    clock: Clock = Clock.system,
) {
    val secret =
        call.request.queryParameters["token"]
            ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "No token")
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    val accountId =
        db {
            val row =
                MagicLinks
                    .selectAll()
                    .where {
                        (MagicLinks.tokenHash eq sha256Hex(secret)) and
                            MagicLinks.consumedAt.isNull() and
                            (MagicLinks.expiresAt greater now)
                    }
                    .singleOrNull()
            if (row != null) {
                MagicLinks.update({ MagicLinks.tokenHash eq row[MagicLinks.tokenHash] }) {
                    it[consumedAt] = now
                }
            }
            row?.get(MagicLinks.accountId)
        } ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "That link has been used or has expired")

    val code = closeNativeFlow(secret, accountId, clock)
    if (code != null) {
        call.respondRedirect("$NATIVE_REDIRECT?code=$code")
        return
    }
    issueSession(call, accountId, clock)
    call.respondRedirect("/app")
}
