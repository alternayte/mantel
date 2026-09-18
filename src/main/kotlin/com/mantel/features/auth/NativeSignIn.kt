package com.mantel.features.auth

import com.mantel.features.account.AccountId
import com.mantel.kernel.Clock
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.Ids
import com.mantel.kernel.db
import com.mantel.kernel.sha256Hex
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

/**
 * Sign-in for a client that is not a browser.
 *
 * The browser flows end by writing an HttpOnly cookie, which is the right answer for a browser and
 * unreadable to a native app: the magic link opens in the mail app's browser and the OAuth flow in
 * a custom tab, and neither shares a cookie jar with the app. So a native flow ends by minting a
 * one-time code and redirecting to the app's deep link. The app exchanges the code for the session
 * secret, proving with the verifier that it is the client that started the flow.
 *
 * This is the API's answer for every native client, not Android's (SDD.md 10). Nothing here knows
 * what platform is asking.
 */
object NativeSignIns : Table("native_sign_in") {
    /** The hash of whichever secret drives the browser half: the magic-link token, or the OAuth state. */
    val flowHash = text("flow_hash")
    val challenge = text("challenge")
    val codeHash = text("code_hash").nullable()

    // Declared, not referenced: an Exposed reference shares its column type with the referee, and
    // making the copy nullable makes account.id nullable too. The foreign key is in the migration.
    val accountId = uuid("account_id").transform({ AccountId(it) }, { it.value }).nullable()
    val expiresAt = timestampWithTimeZone("expires_at")
    val consumedAt = timestampWithTimeZone("consumed_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(flowHash)
}

/**
 * Where a completed native sign-in sends the browser. The scheme is the app's, not the instance's:
 * a self-hoster runs the server, not the app, so this is a constant rather than configuration.
 */
const val NATIVE_REDIRECT = "mantel://auth"

private val FLOW_LIFETIME: Duration = Duration.ofMinutes(15)
private val CODE_LIFETIME: Duration = Duration.ofMinutes(5)

/** base64url of a SHA-256 digest, unpadded: 43 characters of the PKCE alphabet. */
private val CHALLENGE = Regex("^[A-Za-z0-9_-]{43}$")

fun s256(verifier: String): String =
    Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

/** Rejects a malformed challenge at the edge, so a flow is never started that cannot be finished. */
fun validChallenge(challenge: String?): String? {
    if (challenge == null) return null
    if (!CHALLENGE.matches(challenge)) {
        throw DomainException(ErrorCode.VALIDATION_FAILED, "That is not a PKCE S256 challenge")
    }
    return challenge
}

/** Records that this browser flow belongs to a native client. Called as the flow is started. */
fun openNativeFlow(
    flowSecret: String,
    challenge: String,
    now: OffsetDateTime,
) {
    NativeSignIns.insert {
        it[flowHash] = sha256Hex(flowSecret)
        it[NativeSignIns.challenge] = challenge
        it[expiresAt] = now.plus(FLOW_LIFETIME)
        it[createdAt] = now
    }
}

/**
 * Ends the browser half. Returns the one-time code when the flow was started by a native client,
 * and null when it was an ordinary browser, which the caller then signs in with a cookie.
 *
 * A native flow deliberately leaves no session in the browser it ran in: the custom tab and the
 * mail app's browser are not the person's signed-in browser and should not become one.
 */
suspend fun closeNativeFlow(
    flowSecret: String,
    accountId: AccountId,
    clock: Clock = Clock.system,
): String? {
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    val code = Ids.token(32)
    return db {
        val row =
            NativeSignIns
                .selectAll()
                .where {
                    (NativeSignIns.flowHash eq sha256Hex(flowSecret)) and
                        NativeSignIns.codeHash.isNull() and
                        (NativeSignIns.expiresAt greater now)
                }
                .singleOrNull() ?: return@db null

        NativeSignIns.update({ NativeSignIns.flowHash eq row[NativeSignIns.flowHash] }) {
            it[codeHash] = sha256Hex(code)
            it[NativeSignIns.accountId] = accountId
            it[expiresAt] = now.plus(CODE_LIFETIME)
        }
        code
    }
}

@Serializable
data class NativeExchangeRequest(val code: String, val verifier: String)

@Serializable
data class NativeSession(val session: String, val expiresAt: String)

/**
 * The code for the session. One attempt: the row is consumed whether or not the verifier matches,
 * because a code that survives a wrong guess is a code worth guessing at.
 */
suspend fun exchangeNativeCode(
    call: ApplicationCall,
    clock: Clock = Clock.system,
) {
    val request = call.receive<NativeExchangeRequest>()
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    val row =
        db {
            val found =
                NativeSignIns
                    .selectAll()
                    .where {
                        (NativeSignIns.codeHash eq sha256Hex(request.code)) and
                            NativeSignIns.consumedAt.isNull() and
                            (NativeSignIns.expiresAt greater now)
                    }
                    .singleOrNull()
            if (found != null) {
                NativeSignIns.update({ NativeSignIns.flowHash eq found[NativeSignIns.flowHash] }) {
                    it[consumedAt] = now
                }
            }
            found
        } ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "That code has been used or has expired")

    if (row[NativeSignIns.challenge] != s256(request.verifier)) {
        throw DomainException(ErrorCode.VALIDATION_FAILED, "That verifier does not match the flow")
    }
    val accountId =
        row[NativeSignIns.accountId]
            ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "That sign-in did not complete")

    val session = createSession(accountId, clock)
    call.respond(NativeSession(session.secret, session.expiresAt.toInstant().toString()))
}
