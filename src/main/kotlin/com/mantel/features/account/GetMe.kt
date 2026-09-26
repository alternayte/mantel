package com.mantel.features.account

import com.mantel.features.agent.requireCaller
import com.mantel.kernel.Config
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.db
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.selectAll

@Serializable
data class Me(
    val email: String,
    val displayName: String? = null,
    val storageQuotaBytes: Long,
    val storageUsedBytes: Long,
    /**
     * The largest single file this instance accepts. A client that knows it can leave an oversized
     * file out of a batch rather than send it and have the whole batch refused for its sake.
     */
    val maxFileBytes: Long,
)

suspend fun getMe(
    call: ApplicationCall,
    config: Config,
) {
    val accountId = requireCaller(call).accountId
    val me =
        db {
            Accounts.selectAll().where { Accounts.id eq accountId }.singleOrNull()?.let {
                Me(
                    email = it[Accounts.email],
                    displayName = it[Accounts.displayName],
                    storageQuotaBytes = it[Accounts.storageQuotaBytes].value,
                    storageUsedBytes = it[Accounts.storageUsedBytes].value,
                    maxFileBytes = config.maxFileBytes.value,
                )
            }
        } ?: throw DomainException(ErrorCode.NOT_FOUND, "No such account")
    call.respond(me)
}
