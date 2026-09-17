package com.mantel.features.album

import com.mantel.features.auth.requireAccountId
import com.mantel.kernel.Clock
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.db
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.insert
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Serializable
data class CreateAlbumRequest(val title: String, val description: String? = null)

@Serializable
data class AlbumSummary(
    val id: String,
    val title: String,
    val description: String? = null,
    val status: String,
    val itemCount: Int,
    val totalBytes: Long,
    val coverItemId: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

private const val MAX_TITLE = 200

suspend fun createAlbum(
    call: ApplicationCall,
    clock: Clock = Clock.system,
) {
    val accountId = requireAccountId(call)
    val request = call.receive<CreateAlbumRequest>()
    val title = request.title.trim()
    if (title.isEmpty() || title.length > MAX_TITLE) {
        throw DomainException(ErrorCode.VALIDATION_FAILED, "A title is between 1 and $MAX_TITLE characters")
    }

    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    val id = UUID.randomUUID()
    db {
        Albums.insert {
            it[Albums.id] = id
            it[Albums.accountId] = accountId
            it[Albums.title] = title
            it[description] = request.description?.trim()?.ifEmpty { null }
            it[status] = AlbumStatus.DRAFT.wire
            it[itemCount] = 0
            it[totalBytes] = 0
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    call.respond(
        HttpStatusCode.Created,
        AlbumSummary(
            id = id.toString(),
            title = title,
            description = request.description?.trim()?.ifEmpty { null },
            status = AlbumStatus.DRAFT.wire,
            itemCount = 0,
            totalBytes = 0,
            createdAt = now.toInstant().toString(),
            updatedAt = now.toInstant().toString(),
        ),
    )
}
