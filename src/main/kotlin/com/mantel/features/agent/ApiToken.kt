package com.mantel.features.agent

import com.mantel.features.account.AccountId
import com.mantel.features.account.Accounts
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import java.util.UUID

@JvmInline
value class ApiTokenId(val value: UUID) {
    override fun toString() = value.toString()
}

/**
 * What a token may do. There is deliberately no scope that reads another account's media, and
 * `albums:read` does not return share tokens: a link is the thing that gives an album away, so
 * handing one out is a write (SDD.md 9).
 */
enum class Scope {
    ALBUMS_READ,
    ALBUMS_WRITE,
    SHARE_WRITE,
    ;

    val wire: String
        get() =
            when (this) {
                ALBUMS_READ -> "albums:read"
                ALBUMS_WRITE -> "albums:write"
                SHARE_WRITE -> "share:write"
            }

    companion object {
        fun fromWire(value: String): Scope? = entries.firstOrNull { it.wire == value }
    }
}

object ApiTokens : Table("api_token") {
    val id = uuid("id").transform({ ApiTokenId(it) }, { it.value })
    val accountId =
        reference("account_id", Accounts.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION)
    val name = text("name")
    val tokenHash = text("token_hash")
    val scopes = array<String>("scopes")
    val createdAt = timestampWithTimeZone("created_at")
    val lastUsedAt = timestampWithTimeZone("last_used_at").nullable()
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Who is asking, and what they are allowed to ask for. A person signed in with a cookie has no
 * scope limit; a token has exactly the scopes it was created with.
 */
data class Caller(val accountId: AccountId, val scopes: Set<Scope>?) {
    fun allows(scope: Scope): Boolean = scopes == null || scope in scopes

    val isAgent: Boolean get() = scopes != null
}
