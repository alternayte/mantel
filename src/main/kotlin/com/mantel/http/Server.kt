package com.mantel.http

import com.mantel.kernel.Config
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
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
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class ErrorBody(val error: ErrorDetail)

@Serializable
data class ErrorDetail(val code: String, val message: String, val details: Map<String, String> = emptyMap())

private val log = LoggerFactory.getLogger("com.mantel.http")

fun startServer(config: Config) {
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module() }.start(wait = true)
}

fun Application.module() {
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
    }
}
