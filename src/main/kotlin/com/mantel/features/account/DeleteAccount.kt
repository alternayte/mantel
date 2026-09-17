package com.mantel.features.account

import com.mantel.features.auth.requireAccountId
import com.mantel.features.auth.revokeSession
import com.mantel.kernel.db
import com.mantel.storage.ObjectStorage
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere

/**
 * Deletion is a product promise, not a flag. The rows go, and every object under the account's
 * storage prefix goes with them. Sessions and magic links cascade from the account row.
 */
suspend fun deleteAccount(
    call: ApplicationCall,
    storage: ObjectStorage,
) {
    val accountId = requireAccountId(call)

    // Bytes first: a failure here must not leave objects with no row pointing at them.
    withContext(Dispatchers.IO) { storage.deletePrefix(storagePrefixFor(accountId)) }
    db { Accounts.deleteWhere { Accounts.id eq accountId } }
    revokeSession(call)

    call.respond(HttpStatusCode.NoContent)
}
