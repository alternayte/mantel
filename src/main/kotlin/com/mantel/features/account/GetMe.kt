package com.mantel.features.account

import com.mantel.features.auth.requireAccountId
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
)

suspend fun getMe(call: ApplicationCall) {
    val accountId = requireAccountId(call)
    val me =
        db {
            Accounts.selectAll().where { Accounts.id eq accountId }.singleOrNull()?.let {
                Me(
                    email = it[Accounts.email],
                    displayName = it[Accounts.displayName],
                    storageQuotaBytes = it[Accounts.storageQuotaBytes].value,
                    storageUsedBytes = it[Accounts.storageUsedBytes].value,
                )
            }
        } ?: throw DomainException(ErrorCode.NOT_FOUND, "No such account")
    call.respond(me)
}
