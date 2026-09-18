package com.mantel.features.agent

import com.mantel.kernel.Clock
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.Ids
import com.mantel.kernel.db
import com.mantel.kernel.sha256Hex
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Serializable
data class CreateTokenRequest(val name: String, val scopes: List<String>)

@Serializable
data class TokenView(
    val id: String,
    val name: String,
    val scopes: List<String>,
    val createdAt: String,
    val lastUsedAt: String? = null,
    val revokedAt: String? = null,
    /** Present once, when the token is created. It is stored as a hash and cannot be shown again. */
    val token: String? = null,
)

suspend fun listTokens(
    call: ApplicationCall,
    clock: Clock = Clock.system,
) {
    val accountId = requirePerson(call, clock)
    val tokens =
        db {
            ApiTokens.selectAll()
                .where { ApiTokens.accountId eq accountId }
                .orderBy(ApiTokens.createdAt to SortOrder.DESC)
                .map {
                    TokenView(
                        id = it[ApiTokens.id].toString(),
                        name = it[ApiTokens.name],
                        scopes = it[ApiTokens.scopes],
                        createdAt = it[ApiTokens.createdAt].toInstant().toString(),
                        lastUsedAt = it[ApiTokens.lastUsedAt]?.toInstant()?.toString(),
                        revokedAt = it[ApiTokens.revokedAt]?.toInstant()?.toString(),
                    )
                }
        }
    call.respond(tokens)
}

/**
 * The token is shown once and stored as a hash, like every other secret here. Creating one needs a
 * signed-in person: a token that can mint tokens is a token that cannot be scoped.
 */
suspend fun createToken(
    call: ApplicationCall,
    clock: Clock = Clock.system,
) {
    val accountId = requirePerson(call, clock)
    val request = call.receive<CreateTokenRequest>()

    val name = request.name.trim()
    if (name.isEmpty() || name.length > 100) {
        throw DomainException(ErrorCode.VALIDATION_FAILED, "A token needs a name of 1 to 100 characters")
    }
    val scopes = request.scopes.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    if (scopes.isEmpty()) {
        throw DomainException(ErrorCode.VALIDATION_FAILED, "A token with no scopes can do nothing")
    }
    val unknown = scopes.filter { Scope.fromWire(it) == null }
    if (unknown.isNotEmpty()) {
        throw DomainException(
            ErrorCode.VALIDATION_FAILED,
            "Unknown scope: ${unknown.joinToString(", ")}",
            mapOf("known" to Scope.entries.joinToString(", ") { it.wire }),
        )
    }

    val secret = "mantel_${Ids.token(40)}"
    val id = ApiTokenId(Ids.uuidV7(clock))
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    db {
        ApiTokens.insert {
            it[ApiTokens.id] = id
            it[ApiTokens.accountId] = accountId
            it[ApiTokens.name] = name
            it[tokenHash] = sha256Hex(secret)
            it[ApiTokens.scopes] = scopes.toList().sorted()
            it[createdAt] = now
        }
    }

    call.respond(
        HttpStatusCode.Created,
        TokenView(
            id = id.toString(),
            name = name,
            scopes = scopes.toList().sorted(),
            createdAt = now.toInstant().toString(),
            token = secret,
        ),
    )
}

/** Revocation is immediate: the next request with it is unauthenticated. */
suspend fun revokeToken(
    call: ApplicationCall,
    clock: Clock = Clock.system,
) {
    val accountId = requirePerson(call, clock)
    val id =
        call.parameters["id"]?.let { runCatching { ApiTokenId(UUID.fromString(it)) }.getOrNull() }
            ?: throw DomainException(ErrorCode.NOT_FOUND, "No such token")
    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    val updated =
        db {
            ApiTokens.update({ (ApiTokens.id eq id) and (ApiTokens.accountId eq accountId) }) {
                it[revokedAt] = now
            }
        }
    if (updated == 0) throw DomainException(ErrorCode.NOT_FOUND, "No such token")
    call.respond(HttpStatusCode.NoContent)
}
