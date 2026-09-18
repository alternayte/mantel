package com.mantel.features.account

import com.mantel.kernel.Bytes
import com.mantel.kernel.Quota
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import java.util.UUID

/** A creator's identity. Distinct from an album id and an item id, and the compiler knows it. */
@JvmInline
value class AccountId(val value: UUID) {
    override fun toString() = value.toString()
}

/**
 * The creator. Flyway builds this table; these columns only name what the code reads.
 * An account's object-storage keys all live under one prefix, which is what makes deletion a
 * single call rather than a walk over rows that may not exist yet.
 */
object Accounts : Table("account") {
    val id = uuid("id").transform({ AccountId(it) }, { it.value })
    val email = text("email")
    val githubId = long("github_id").nullable()
    val displayName = text("display_name").nullable()
    val storageQuotaBytes = long("storage_quota_bytes").transform({ Bytes(it) }, { it.value })
    val storageUsedBytes = long("storage_used_bytes").transform({ Bytes(it) }, { it.value })
    val createdAt = timestampWithTimeZone("created_at")
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

fun ResultRow.quota() = Quota(limit = this[Accounts.storageQuotaBytes], used = this[Accounts.storageUsedBytes])

/**
 * Media lives under its own item id and nothing else. An earlier layout put the account and album
 * ids in the key, which then travelled inside every presigned URL in a public manifest — exactly
 * what SDD.md 6.2 forbids, and a way for a viewer to tell that two albums share an owner.
 */
fun mediaPrefixFor(itemId: com.mantel.features.media.ItemId): String = "media/$itemId/"
