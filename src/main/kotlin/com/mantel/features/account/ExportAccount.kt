package com.mantel.features.account

import com.mantel.features.auth.requireAccountId
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.db
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.selectAll

/**
 * Everything the product holds about an account, as one JSON document. Albums and media items join
 * this export in the milestones that create them; the endpoint is the promise, and it is kept from
 * the first milestone that has anything to export.
 */
@Serializable
data class AccountExport(
    val email: String,
    val displayName: String? = null,
    val createdAt: String,
    val storageQuotaBytes: Long,
    val storageUsedBytes: Long,
)

suspend fun exportAccount(call: ApplicationCall) {
    val accountId = requireAccountId(call)
    val export =
        db {
            Accounts.selectAll().where { Accounts.id eq accountId }.singleOrNull()?.let {
                AccountExport(
                    email = it[Accounts.email],
                    displayName = it[Accounts.displayName],
                    createdAt = it[Accounts.createdAt].toInstant().toString(),
                    storageQuotaBytes = it[Accounts.storageQuotaBytes].value,
                    storageUsedBytes = it[Accounts.storageUsedBytes].value,
                )
            }
        } ?: throw DomainException(ErrorCode.NOT_FOUND, "No such account")

    call.response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "mantel-export.json")
            .toString(),
    )
    call.respond(export)
}
