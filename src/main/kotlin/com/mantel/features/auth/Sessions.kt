package com.mantel.features.auth

import com.mantel.features.account.Accounts
import com.mantel.kernel.Clock
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.Ids
import com.mantel.kernel.db
import com.mantel.kernel.sha256Hex
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
import java.util.UUID

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
    accountId: UUID,
    clock: Clock = Clock.system,
): String {
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
    call.setCookie(SESSION_COOKIE, secret, maxAgeSeconds = SESSION_LIFETIME.seconds, path = "/")
    return secret
}

suspend fun revokeSession(call: ApplicationCall) {
    val secret = call.request.cookies[SESSION_COOKIE]
    if (secret != null) {
        db { Sessions.deleteWhere { Sessions.id eq sha256Hex(secret) } }
    }
    call.setCookie(SESSION_COOKIE, "", maxAgeSeconds = 0, path = "/")
}

/** The signed-in account, or null. */
suspend fun currentAccountId(
    call: ApplicationCall,
    clock: Clock = Clock.system,
): UUID? {
    val secret = call.request.cookies[SESSION_COOKIE] ?: return null
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

suspend fun requireAccountId(call: ApplicationCall): UUID =
    currentAccountId(call) ?: throw DomainException(ErrorCode.UNAUTHENTICATED, "Sign in first")
