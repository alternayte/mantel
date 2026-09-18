package com.mantel.features.album

import com.mantel.features.agent.Caller
import com.mantel.features.agent.Scope
import com.mantel.features.agent.requireScope
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
    val caller = requireScope(call, Scope.ALBUMS_WRITE)
    val request = call.receive<CreateAlbumRequest>()
    call.respond(HttpStatusCode.Created, createAlbumFor(caller, request, clock))
}

/** The command. The route above and the MCP tool both call this and nothing else. */
suspend fun createAlbumFor(
    caller: Caller,
    request: CreateAlbumRequest,
    clock: Clock = Clock.system,
): AlbumSummary {
    val accountId = caller.demand(Scope.ALBUMS_WRITE).accountId
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

    return AlbumSummary(
        id = id.toString(),
        title = title.value,
        description = description,
        status = AlbumStatus.DRAFT.wire,
        itemCount = 0,
        totalBytes = 0,
        createdAt = now.toInstant().toString(),
        updatedAt = now.toInstant().toString(),
    )
}
