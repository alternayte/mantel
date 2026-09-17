package com.mantel.support

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder

/** A signed-in browser, which is the starting point of every creator test. */
suspend fun ApplicationTestBuilder.signedIn(
    harness: Harness,
    email: String = "nate@example.com",
): HttpClient {
    val browser = browser()
    browser.post("/api/auth/magic-link") {
        contentType(ContentType.Application.Json)
        setBody("""{"email":"$email"}""")
    }
    browser.get(harness.mailer.lastLink())
    return browser
}

suspend fun HttpClient.createAlbum(title: String = "Holiday"): HttpResponse =
    post("/api/albums") {
        contentType(ContentType.Application.Json)
        setBody("""{"title":"$title"}""")
    }

suspend fun HttpClient.uploadIntent(
    albumId: String,
    body: String,
): HttpResponse =
    post("/api/albums/$albumId/upload-intent") {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
