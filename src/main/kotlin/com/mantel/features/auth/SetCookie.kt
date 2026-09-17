package com.mantel.features.auth

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.header

/**
 * The Set-Cookie header, written out exactly. Ktor's cookie builder appends its own `$x-enc` marker
 * to record how it encoded the value, which no browser needs and no proxy should have to parse.
 * The values here are tokens from a URL-safe alphabet, so nothing needs encoding.
 */
fun ApplicationCall.setCookie(
    name: String,
    value: String,
    maxAgeSeconds: Long,
    path: String,
    httpOnly: Boolean = true,
    sameSite: String = "Lax",
) {
    require(value.all { it.isLetterOrDigit() || it in "-_" }) { "cookie value must be URL safe" }
    val parts =
        buildList {
            add("$name=$value")
            add("Max-Age=$maxAgeSeconds")
            add("Path=$path")
            if (httpOnly) add("HttpOnly")
            if (request.origin.scheme == "https") add("Secure")
            add("SameSite=$sameSite")
        }
    response.header(HttpHeaders.SetCookie, parts.joinToString("; "))
}
