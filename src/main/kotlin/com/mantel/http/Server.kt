package com.mantel.http

import com.mantel.features.account.deleteAccount
import com.mantel.features.account.exportAccount
import com.mantel.features.account.getMe
import com.mantel.features.album.archiveAlbum
import com.mantel.features.album.createAlbum
import com.mantel.features.album.getAlbum
import com.mantel.features.album.getAlbumProgress
import com.mantel.features.album.listAlbums
import com.mantel.features.album.updateAlbum
import com.mantel.features.auth.completeGitHubOAuth
import com.mantel.features.auth.consumeMagicLink
import com.mantel.features.auth.requestMagicLink
import com.mantel.features.auth.revokeSession
import com.mantel.features.auth.startGitHubOAuth
import com.mantel.features.media.completeUploads
import com.mantel.features.media.createUploadIntent
import com.mantel.features.media.deleteItem
import com.mantel.features.media.reorderItems
import com.mantel.features.media.setCaption
import com.mantel.kernel.Config
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.Mailer
import com.mantel.kernel.RateLimiter
import com.mantel.storage.ObjectStorage
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Duration

@Serializable
data class ErrorBody(val error: ErrorDetail)

@Serializable
data class ErrorDetail(val code: String, val message: String, val details: Map<String, String> = emptyMap())

private val log = LoggerFactory.getLogger("com.mantel.http")

/** What a request handler needs, assembled once at startup and passed in. */
class Services(
    val config: Config,
    val storage: ObjectStorage,
    val mailer: Mailer,
    val httpClient: HttpClient,
    // Five links an hour for one address: enough for a mistyped inbox, not enough to use as a mailer.
    val magicLinkLimiter: RateLimiter = RateLimiter(capacity = 5, refillPeriod = Duration.ofHours(1)),
)

fun startServer(services: Services) {
    embeddedServer(Netty, port = services.config.port, host = "0.0.0.0") { module(services) }.start(wait = true)
}

fun Application.module(services: Services) {
    install(ContentNegotiation) { json() }
    install(CallLogging)
    install(DefaultHeaders) {
        // Albums are unlisted by design. SDD.md 4.3.
        header("X-Robots-Tag", "noindex, nofollow")
    }
    install(StatusPages) {
        exception<DomainException> { call, cause ->
            call.respond(
                HttpStatusCode.fromValue(cause.code.status),
                ErrorBody(ErrorDetail(cause.code.wire, cause.message, cause.details)),
            )
        }
        exception<Throwable> { call, cause ->
            log.error("unhandled", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorBody(ErrorDetail(ErrorCode.INTERNAL.wire, "Unexpected error")),
            )
        }
    }
    routing {
        get("/api/health") { call.respond(mapOf("status" to "ok")) }

        post("/api/auth/magic-link") {
            requestMagicLink(call, services.config, services.mailer, services.magicLinkLimiter)
        }
        get("/api/auth/magic-link/callback") { consumeMagicLink(call) }
        get("/api/auth/github") { startGitHubOAuth(call, services.config) }
        get("/api/auth/github/callback") { completeGitHubOAuth(call, services.config, services.httpClient) }
        post("/api/auth/logout") {
            revokeSession(call)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/api/me") { getMe(call) }

        get("/api/albums") { listAlbums(call) }
        post("/api/albums") { createAlbum(call) }
        get("/api/albums/{id}") { getAlbum(call) }
        patch("/api/albums/{id}") { updateAlbum(call) }
        delete("/api/albums/{id}") { archiveAlbum(call) }
        get("/api/albums/{id}/status") { getAlbumProgress(call) }

        post("/api/albums/{id}/upload-intent") { createUploadIntent(call, services.storage) }
        post("/api/albums/{id}/uploads/complete") { completeUploads(call, services.storage) }
        patch("/api/albums/{id}/items/reorder") { reorderItems(call) }
        patch("/api/albums/{id}/items/{itemId}") { setCaption(call) }
        delete("/api/albums/{id}/items/{itemId}") { deleteItem(call, services.storage) }

        get("/api/account/export") { exportAccount(call) }
        delete("/api/account") { deleteAccount(call, services.storage) }
    }
}
