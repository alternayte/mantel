package com.mantel.app.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The REST API of SDD.md 6, and nothing else. The app is a pure consumer of it: if it wants
 * something the API does not do, that belongs in the API for every client (SDD.md 10).
 */
@Serializable
data class SignInMethods(val magicLink: Boolean, val github: Boolean)

@Serializable
data class NativeSession(val session: String, val expiresAt: String)

@Serializable
data class Me(
    val email: String,
    val displayName: String? = null,
    val storageQuotaBytes: Long,
    val storageUsedBytes: Long,
)

@Serializable
private data class ErrorBody(val error: ErrorDetail)

@Serializable
private data class ErrorDetail(val code: String, val message: String)

/**
 * The documented error shape (SDD.md 6.4), as an exception. `code` is contract and drives
 * behaviour; `message` is what the person reads.
 */
class ApiException(val code: String, override val message: String) : RuntimeException(message)

private val lenientJson = Json { ignoreUnknownKeys = true }

class MantelApi(
    baseUrl: String,
    private val session: String? = null,
) {
    private val base = baseUrl.trimEnd('/')

    private val client =
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(lenientJson) }
            expectSuccess = false
        }

    /** Where the custom tab starts a GitHub sign-in. The browser, not the app, follows this. */
    fun githubSignInUrl(challenge: String): String = "$base/api/auth/github?challenge=$challenge"

    suspend fun signInMethods(): SignInMethods = client.get("$base/api/auth/methods").require()

    suspend fun requestMagicLink(
        email: String,
        challenge: String,
    ) {
        client.post("$base/api/auth/magic-link") {
            contentType(ContentType.Application.Json)
            setBody(MagicLinkRequest(email, challenge))
        }.require<Unit>()
    }

    suspend fun exchange(
        code: String,
        verifier: String,
    ): NativeSession =
        client.post("$base/api/auth/native/exchange") {
            contentType(ContentType.Application.Json)
            setBody(ExchangeRequest(code, verifier))
        }.require()

    suspend fun me(): Me = client.get("$base/api/me") { authorize() }.require()

    suspend fun logout() {
        client.post("$base/api/auth/logout") { authorize() }.require<Unit>()
    }

    fun close() = client.close()

    private fun io.ktor.client.request.HttpRequestBuilder.authorize() {
        session?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    /**
     * A failed call carries the server's code, so the caller can act on `unauthenticated` without
     * reading English. A response that is not the documented shape at all is a broken instance or
     * the wrong address, and says so.
     */
    private suspend inline fun <reified T> HttpResponse.require(): T {
        if (status.value in 200..299) {
            return if (T::class == Unit::class) Unit as T else body()
        }
        val text = bodyAsText()
        val detail =
            runCatching { lenientJson.decodeFromString<ErrorBody>(text).error }
                .getOrNull()
                ?: throw ApiException("unreachable", "That address did not answer as a Mantel server")
        throw ApiException(detail.code, detail.message)
    }

    @Serializable
    private data class MagicLinkRequest(val email: String, val challenge: String)

    @Serializable
    private data class ExchangeRequest(val code: String, val verifier: String)
}
