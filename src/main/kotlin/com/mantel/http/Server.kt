package com.mantel.http

import com.mantel.features.account.deleteAccount
import com.mantel.features.account.exportAccount
import com.mantel.features.account.getMe
import com.mantel.features.agent.createToken
import com.mantel.features.agent.listTokens
import com.mantel.features.agent.llmsTxt
import com.mantel.features.agent.openApiDocument
import com.mantel.features.agent.revokeToken
import com.mantel.features.agent.serveMcp
import com.mantel.features.album.archiveAlbum
import com.mantel.features.album.createAlbum
import com.mantel.features.album.getAlbum
import com.mantel.features.album.getAlbumProgress
import com.mantel.features.album.listAlbums
import com.mantel.features.album.updateAlbum
import com.mantel.features.auth.completeGitHubOAuth
import com.mantel.features.auth.consumeMagicLink
import com.mantel.features.auth.exchangeNativeCode
import com.mantel.features.auth.getSignInMethods
import com.mantel.features.auth.requestMagicLink
import com.mantel.features.auth.revokeSession
import com.mantel.features.auth.startGitHubOAuth
import com.mantel.features.media.claimWork
import com.mantel.features.media.classifyObjects
import com.mantel.features.media.completeUploads
import com.mantel.features.media.createUploadIntent
import com.mantel.features.media.deleteItem
import com.mantel.features.media.getUploadProgress
import com.mantel.features.media.reorderItems
import com.mantel.features.media.repairQuota
import com.mantel.features.media.reportDerivatives
import com.mantel.features.media.reportFailure
import com.mantel.features.media.reportHeartbeat
import com.mantel.features.media.retryItem
import com.mantel.features.media.setCaption
import com.mantel.features.share.claimBundles
import com.mantel.features.share.createShareLink
import com.mantel.features.share.heartbeatBundle
import com.mantel.features.share.listShareLinks
import com.mantel.features.share.reportBundleBuilt
import com.mantel.features.share.reportBundleFailure
import com.mantel.features.share.requestBundle
import com.mantel.features.share.revokeShareLink
import com.mantel.features.viewer.getManifest
import com.mantel.features.viewer.serveOgShell
import com.mantel.features.viewer.unlock
import com.mantel.kernel.Clock
import com.mantel.kernel.Config
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.Mailer
import com.mantel.kernel.RateLimiter
import com.mantel.storage.ObjectStorage
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
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
    val assets: WebAssets = WebAssets.load(config.devAssetsOrigin),
    val clock: Clock = Clock.system,
    val storage: ObjectStorage,
    val mailer: Mailer,
    val httpClient: HttpClient,
    // Five links an hour for one address: enough for a mistyped inbox, not enough to use as a mailer.
    val magicLinkLimiter: RateLimiter = RateLimiter(capacity = 5, refillPeriod = Duration.ofHours(1)),
    // Ten PIN attempts per link per address per hour. A four-digit PIN has ten thousand
    // possibilities, so this is the difference between guessable and not.
    val pinLimiter: RateLimiter = RateLimiter(capacity = 10, refillPeriod = Duration.ofHours(1)),
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

        get("/api/auth/methods") { getSignInMethods(call, services.config) }

        // A native client cannot read the session cookie the browser flows write, so it exchanges a
        // one-time code for the session instead (SDD.md 6.1).
        post("/api/auth/native/exchange") { exchangeNativeCode(call) }

        // The agent surface: the same API, the same scopes, in the same binary (SDD.md 9).
        post("/mcp") { serveMcp(call, services.config, services.storage, services.clock) }
        get("/llms.txt") { call.respondText(ContentType.Text.Plain, HttpStatusCode.OK) { llmsTxt(services.config) } }
        get("/openapi.json") {
            call.respondText(ContentType.Application.Json, HttpStatusCode.OK) { openApiDocument(services.config) }
        }
        get("/api/me") { getMe(call) }

        get("/api/albums") { listAlbums(call) }
        post("/api/albums") { createAlbum(call) }
        get("/api/albums/{id}") { getAlbum(call, services.storage) }
        patch("/api/albums/{id}") { updateAlbum(call) }
        delete("/api/albums/{id}") { archiveAlbum(call) }
        get("/api/albums/{id}/status") { getAlbumProgress(call, services.storage) }

        post("/api/albums/{id}/upload-intent") {
            createUploadIntent(call, services.config, services.storage, services.clock)
        }
        get("/api/albums/{id}/items/{itemId}/upload-progress") {
            getUploadProgress(call, services.config, services.storage)
        }
        post("/api/albums/{id}/uploads/complete") { completeUploads(call, services.storage) }
        patch("/api/albums/{id}/items/reorder") { reorderItems(call) }
        patch("/api/albums/{id}/items/{itemId}") { setCaption(call) }
        delete("/api/albums/{id}/items/{itemId}") { deleteItem(call, services.storage) }
        post("/api/albums/{id}/items/{itemId}/retry") { retryItem(call, services.clock) }

        post("/api/albums/{id}/share-links") {
            createShareLink(call, services.config, services.storage, services.clock)
        }
        get("/api/albums/{id}/share-links") { listShareLinks(call, services.config, services.clock) }
        delete("/api/share-links/{id}") { revokeShareLink(call, services.storage, services.clock) }

        // Public: a token and nothing else. No session, and no cookie unless a PIN is unlocked.
        get("/api/share/{token}") { getManifest(call, services.config, services.storage, services.clock) }
        post("/api/share/{token}/unlock") {
            unlock(call, services.config, services.pinLimiter, services.clock)
        }
        get("/api/share/{token}/download") {
            requestBundle(call, services.config, services.storage, services.clock)
        }
        get("/a/{token}") { serveOgShell(call, services.config, services.assets, services.clock) }
        // The preview image a PIN'd album shows in place of its cover. It ships in the jar: it is
        // not media, and it must load for a crawler with no credentials.
        // The built SPA. Hashed filenames, so they are immutable and may be cached for a year.
        staticResources("/assets", "web/assets") {
            cacheControl { listOf(io.ktor.http.CacheControl.MaxAge(maxAgeSeconds = 31_536_000)) }
        }

        // The creator application. Every path under /app is the same page; it reads the address bar.
        get("/app") { call.respondText(ContentType.Text.Html, HttpStatusCode.OK) { services.assets.appShell() } }
        get("/app/{rest...}") { call.respondText(ContentType.Text.Html, HttpStatusCode.OK) { services.assets.appShell() } }

        get("/og-placeholder.png") {
            val bytes = Services::class.java.getResourceAsStream("/static/og-placeholder.png")!!.readBytes()
            call.respondBytes(bytes, ContentType.Image.PNG)
        }

        // The worker has no database credentials, so it asks for work and reports outcomes here.
        post("/api/worker/claim") { claimWork(call, services.config, services.clock) }
        post("/api/worker/items/{itemId}/derivatives") {
            reportDerivatives(call, services.config, services.clock)
        }
        post("/api/worker/items/{itemId}/failure") { reportFailure(call, services.config, services.clock) }
        post("/api/worker/items/{itemId}/heartbeat") { reportHeartbeat(call, services.config, services.clock) }

        post("/api/worker/reconcile/classify") { classifyObjects(call, services.config) }
        post("/api/worker/reconcile/quota") { repairQuota(call, services.config) }

        post("/api/worker/bundles/claim") { claimBundles(call, services.config, services.clock) }
        post("/api/worker/bundles/{bundleId}/built") { reportBundleBuilt(call, services.config, services.clock) }
        post("/api/worker/bundles/{bundleId}/failure") { reportBundleFailure(call, services.config, services.clock) }
        post("/api/worker/bundles/{bundleId}/heartbeat") { heartbeatBundle(call, services.config, services.clock) }

        get("/api/tokens") { listTokens(call, services.clock) }
        post("/api/tokens") { createToken(call, services.clock) }
        delete("/api/tokens/{id}") { revokeToken(call, services.clock) }

        get("/api/account/export") { exportAccount(call) }
        delete("/api/account") { deleteAccount(call, services.storage) }
    }
}
