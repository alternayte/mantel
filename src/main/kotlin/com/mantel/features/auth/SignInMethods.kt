package com.mantel.features.auth

import com.mantel.kernel.Config
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

@Serializable
data class SignInMethods(val magicLink: Boolean, val github: Boolean)

/**
 * What this instance can actually sign someone in with. A self-hoster who has not registered a
 * GitHub app should not be offered a button that answers with an error; the client asks first.
 */
suspend fun getSignInMethods(
    call: ApplicationCall,
    config: Config,
) {
    call.respond(SignInMethods(magicLink = true, github = config.github != null))
}
