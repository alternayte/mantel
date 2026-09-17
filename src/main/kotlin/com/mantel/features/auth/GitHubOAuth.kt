package com.mantel.features.auth

import com.mantel.features.account.AccountId
import com.mantel.features.account.Accounts
import com.mantel.kernel.Bytes
import com.mantel.kernel.Clock
import com.mantel.kernel.Config
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.kernel.Ids
import com.mantel.kernel.db
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondRedirect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset

private const val STATE_COOKIE = "mantel_oauth_state"

@Serializable
private data class GitHubToken(
    @SerialName("access_token") val accessToken: String? = null,
)

@Serializable
private data class GitHubUser(val id: Long, val login: String, val name: String? = null, val email: String? = null)

@Serializable
private data class GitHubEmail(val email: String, val primary: Boolean, val verified: Boolean)

private fun githubOrFail(config: Config) =
    config.github ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "GitHub sign-in is not configured")

/**
 * The state value is minted here, held in a short-lived cookie scoped to this callback, and
 * compared on return. Without it the callback accepts a code anyone can plant.
 */
suspend fun startGitHubOAuth(
    call: ApplicationCall,
    config: Config,
) {
    val github = githubOrFail(config)
    val state = Ids.token(24)
    call.setCookie(STATE_COOKIE, state, maxAgeSeconds = 600, path = "/api/auth/github")
    val redirectUri = "${config.publicBaseUrl}/api/auth/github/callback"
    call.respondRedirect(
        "${github.authorizeUrl}?client_id=${github.clientId}&redirect_uri=$redirectUri&scope=user:email&state=$state",
    )
}

suspend fun completeGitHubOAuth(
    call: ApplicationCall,
    config: Config,
    client: HttpClient,
    clock: Clock = Clock.system,
) {
    val github = githubOrFail(config)
    val state = call.request.queryParameters["state"]
    val expectedState = call.request.cookies[STATE_COOKIE]
    if (state == null || expectedState == null || state != expectedState) {
        throw DomainException(ErrorCode.VALIDATION_FAILED, "Sign-in could not be verified. Start again.")
    }
    val code =
        call.request.queryParameters["code"]
            ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "GitHub returned no code")

    val token =
        client.post(github.tokenUrl) {
            header(HttpHeaders.Accept, "application/json")
            parameter("client_id", github.clientId)
            parameter("client_secret", github.clientSecret)
            parameter("code", code)
            parameter("redirect_uri", "${config.publicBaseUrl}/api/auth/github/callback")
        }.body<GitHubToken>().accessToken
            ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "GitHub refused the sign-in")

    val user: GitHubUser =
        client.get("${github.apiBaseUrl}/user") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }.body()

    // An unverified address must not be able to claim an existing account, so only a verified
    // primary address counts as an identity.
    val email =
        user.email
            ?: client.get("${github.apiBaseUrl}/user/emails") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Accept, "application/vnd.github+json")
            }.body<List<GitHubEmail>>()
                .firstOrNull { it.primary && it.verified }
                ?.email
            ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "GitHub has no verified email for this account")

    val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
    val accountId =
        db {
            val byGitHub =
                Accounts.selectAll()
                    .where { (Accounts.githubId eq user.id) and Accounts.deletedAt.isNull() }
                    .singleOrNull()
            val byEmail =
                byGitHub
                    ?: Accounts.selectAll()
                        .where { (Accounts.email.lowerCase() eq email.lowercase()) and Accounts.deletedAt.isNull() }
                        .singleOrNull()
            when (byEmail) {
                null -> {
                    val id = AccountId(Ids.uuidV7(clock))
                    Accounts.insert {
                        it[Accounts.id] = id
                        it[Accounts.email] = email
                        it[githubId] = user.id
                        it[displayName] = user.name ?: user.login
                        it[storageQuotaBytes] = config.defaultQuota
                        it[storageUsedBytes] = Bytes.NONE
                        it[createdAt] = now
                    }
                    id
                }
                else -> {
                    val id = byEmail[Accounts.id]
                    Accounts.update({ Accounts.id eq id }) {
                        it[githubId] = user.id
                        if (byEmail[displayName] == null) it[displayName] = user.name ?: user.login
                    }
                    id
                }
            }
        }

    issueSession(call, accountId, clock)
    call.setCookie(STATE_COOKIE, "", maxAgeSeconds = 0, path = "/api/auth/github")
    call.respondRedirect("/app")
}
