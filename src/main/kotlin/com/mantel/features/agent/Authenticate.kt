package com.mantel.features.agent

import com.mantel.features.account.Accounts
import com.mantel.features.auth.currentAccountId
import com.mantel.kernel.Clock
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.db
import com.mantel.kernel.sha256Hex
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * A session cookie or a bearer token, resolved the same way for every creator route. Scope is then
 * checked per route, because the alternative — checking it in nine places and forgetting the tenth
 * — is how an agent token ends up reading something it should not.
 */
suspend fun callerOf(
    call: ApplicationCall,
    clock: Clock = Clock.system,
): Caller? {
    currentAccountId(call, clock)?.let { return Caller(it, scopes = null) }

    val presented =
        call.request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.removePrefix("Bearer ")
            ?.removePrefix("bearer ")
            ?.trim()
            ?: return null

    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    return db {
        val row =
            (ApiTokens innerJoin Accounts)
                .selectAll()
                .where {
                    (ApiTokens.tokenHash eq sha256Hex(presented)) and
                        ApiTokens.revokedAt.isNull() and
                        Accounts.deletedAt.isNull()
                }
                .singleOrNull() ?: return@db null

        ApiTokens.update({ ApiTokens.id eq row[ApiTokens.id] }) { it[lastUsedAt] = now }
        Caller(
            accountId = row[ApiTokens.accountId],
            scopes = row[ApiTokens.scopes].mapNotNull(Scope::fromWire).toSet(),
        )
    }
}

suspend fun requireCaller(
    call: ApplicationCall,
    clock: Clock = Clock.system,
): Caller = callerOf(call, clock) ?: throw DomainException(ErrorCode.UNAUTHENTICATED, "Sign in first")

/**
 * The scope check. A token without the scope is refused before the handler reads anything, and the
 * refusal says which scope was missing, because an agent has to be able to act on the answer.
 */
suspend fun requireScope(
    call: ApplicationCall,
    scope: Scope,
    clock: Clock = Clock.system,
): Caller {
    val caller = requireCaller(call, clock)
    if (!caller.allows(scope)) {
        throw DomainException(
            ErrorCode.FORBIDDEN,
            "This token does not have ${scope.wire}",
            mapOf("requiredScope" to scope.wire),
        )
    }
    return caller
}

/**
 * Some things are the person's, not an agent's: exporting everything, deleting the account, and
 * minting another token. A token that can mint a token has no scope at all.
 */
suspend fun requirePerson(
    call: ApplicationCall,
    clock: Clock = Clock.system,
): com.mantel.features.account.AccountId {
    val caller = requireCaller(call, clock)
    if (caller.isAgent) {
        throw DomainException(
            ErrorCode.FORBIDDEN,
            "This needs a signed-in person, not an API token",
        )
    }
    return caller.accountId
}
