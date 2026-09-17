package com.mantel.features.account

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * The creator. Flyway builds this table; these columns only name what the code reads.
 * An account's object-storage keys all live under one prefix, which is what makes deletion a
 * single call rather than a walk over rows that may not exist yet.
 */
object Accounts : Table("account") {
    val id = uuid("id")
    val email = text("email")
    val githubId = long("github_id").nullable()
    val displayName = text("display_name").nullable()
    val storageQuotaBytes = long("storage_quota_bytes")
    val storageUsedBytes = long("storage_used_bytes")
    val createdAt = timestampWithTimeZone("created_at")
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

fun storagePrefixFor(accountId: java.util.UUID): String = "accounts/$accountId/"
