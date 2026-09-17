package com.mantel.features.album

import com.mantel.features.auth.requireAccountId
import com.mantel.kernel.Bytes
import com.mantel.kernel.Clock
import com.mantel.kernel.Ids
import com.mantel.kernel.db
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.insert
import java.time.OffsetDateTime
import java.time.ZoneOffset

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

suspend fun createAlbum(
    call: ApplicationCall,
    clock: Clock = Clock.system,
) {
    val accountId = requireAccountId(call)
    val request = call.receive<CreateAlbumRequest>()
    val title = AlbumTitle.of(request.title)
    val description = request.description?.trim()?.ifEmpty { null }

    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    val id = AlbumId(Ids.uuidV7(clock))
    db {
        Albums.insert {
            it[Albums.id] = id
            it[Albums.accountId] = accountId
            it[Albums.title] = title
            it[Albums.description] = description
            it[status] = AlbumStatus.DRAFT
            it[itemCount] = 0
            it[totalBytes] = Bytes.NONE
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    call.respond(
        HttpStatusCode.Created,
        AlbumSummary(
            id = id.toString(),
            title = title.value,
            description = description,
            status = AlbumStatus.DRAFT.wire,
            itemCount = 0,
            totalBytes = 0,
            createdAt = now.toInstant().toString(),
            updatedAt = now.toInstant().toString(),
        ),
    )
}
