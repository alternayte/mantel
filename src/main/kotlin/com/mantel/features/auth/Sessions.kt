package com.mantel.features.auth

import com.mantel.features.account.AccountId
import com.mantel.features.account.Accounts
import com.mantel.kernel.Clock
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.Ids
import com.mantel.kernel.db
import com.mantel.kernel.sha256Hex
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.selectAll
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

object Sessions : Table("session") {
    val id = text("id")
    val accountId = reference("account_id", Accounts.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION)
    val expiresAt = timestampWithTimeZone("expires_at")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

const val SESSION_COOKIE = "mantel_session"
private val SESSION_LIFETIME: Duration = Duration.ofDays(30)

/**
 * The session id travels in an HttpOnly cookie and is stored as a hash, so neither a script in the
 * page nor a copy of the database yields a usable session. SDD.md 4.3 describes the PIN cookie as
 * the only cookie on a viewer path; this one is scoped to the creator surfaces.
 */
suspend fun issueSession(
    call: ApplicationCall,
    accountId: AccountId,
    clock: Clock = Clock.system,
): String {
    val session = createSession(accountId, clock)
    call.setCookie(SESSION_COOKIE, session.secret, maxAgeSeconds = SESSION_LIFETIME.seconds, path = "/")
    return session.secret
}

data class NewSession(val secret: String, val expiresAt: OffsetDateTime)

/**
 * A session without a call to write a cookie on. A native client holds the secret itself and sends
 * it as a bearer token, so the row exists in exactly the same shape and only its carrier differs.
 */
suspend fun createSession(
    accountId: AccountId,
    clock: Clock = Clock.system,
): NewSession {
    val secret = Ids.token(32)
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    val expiresAt = now.plus(SESSION_LIFETIME)
    db {
        Sessions.insert {
            it[id] = sha256Hex(secret)
            it[Sessions.accountId] = accountId
            it[Sessions.expiresAt] = expiresAt
            it[createdAt] = now
        }
    }
    return NewSession(secret, expiresAt)
}

suspend fun revokeSession(call: ApplicationCall) {
    val secret = call.presentedSession()
    if (secret != null) {
        db { Sessions.deleteWhere { Sessions.id eq sha256Hex(secret) } }
    }
    call.setCookie(SESSION_COOKIE, "", maxAgeSeconds = 0, path = "/")
}

/**
 * The session secret the caller presented: a browser's cookie, or a native client's bearer token.
 * An API token is also a bearer value; it does not match a session row and falls through to the
 * token path in callerOf.
 */
fun ApplicationCall.presentedSession(): String? =
    request.cookies[SESSION_COOKIE]
        ?: request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.drop("Bearer ".length)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

/** The signed-in account, or null. */
suspend fun currentAccountId(
    call: ApplicationCall,
    clock: Clock = Clock.system,
): AccountId? {
    val secret = call.presentedSession() ?: return null
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    return db {
        (Sessions innerJoin Accounts)
            .selectAll()
            .where {
                (Sessions.id eq sha256Hex(secret)) and
                    (Sessions.expiresAt greater now) and
                    Accounts.deletedAt.isNull()
            }
            .singleOrNull()
            ?.get(Sessions.accountId)
    }
}

suspend fun requireAccountId(call: ApplicationCall): AccountId =
    currentAccountId(call) ?: throw DomainException(ErrorCode.UNAUTHENTICATED, "Sign in first")
