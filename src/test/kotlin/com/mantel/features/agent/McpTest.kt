package com.mantel.features.agent

import com.mantel.features.album.AlbumSummary
import com.mantel.support.browser
import com.mantel.support.createAlbum
import com.mantel.support.signedIn
import com.mantel.support.withApp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The MCP server is an adapter over the same API with the same scopes (SDD.md 9). These check that
 * it really is one — a tool that quietly skipped a scope check would be a second surface.
 */
class McpTest {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun HttpClient.rpc(
        body: String,
        token: String? = null,
    ): JsonObject =
        json.parseToJsonElement(
            post("/mcp") {
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(body)
            }.bodyAsText(),
        ).jsonObject

    private suspend fun HttpClient.mint(vararg scopes: String): String =
        post("/api/tokens") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"agent","scopes":[${scopes.joinToString(",") { "\"$it\"" }}]}""")
        }.body<TokenView>().token!!

    @Test
    fun `it introduces itself and lists the tools SDD 9 names`() =
        withApp {
            val agent = browser()
            val initialize =
                agent.rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
            assertEquals("mantel", initialize["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)

            val tools =
                agent.rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")["result"]!!
                    .jsonObject["tools"]!!
                    .jsonArray
                    .map { it.jsonObject["name"]!!.jsonPrimitive.content }

            listOf(
                "list_albums",
                "create_album",
                "request_upload",
                "complete_upload",
                "set_caption",
                "reorder_items",
                "publish_album",
                "create_share_link",
                "revoke_share_link",
            ).forEach { assertTrue(it in tools, "missing tool $it, has $tools") }
        }

    @Test
    fun `a tool call does the same work the REST route does`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val token = creator.mint("albums:read", "albums:write")
            val agent = browser()

            val created =
                agent.rpc(
                    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":
                       {"name":"create_album","arguments":{"title":"From an agent"}}}""",
                    token,
                )
            val album = created["result"]!!.jsonObject["structuredContent"]!!.jsonObject
            assertEquals("From an agent", album["title"]!!.jsonPrimitive.content)

            // The same album is there for the person, through the ordinary API.
            val albums = creator.get("/api/albums").body<List<AlbumSummary>>()
            assertEquals(listOf("From an agent"), albums.map { it.title })
        }

    @Test
    fun `a tool obeys the token's scopes`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val readOnly = creator.mint("albums:read")
            val agent = browser()

            val refused =
                agent.rpc(
                    """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":
                       {"name":"create_album","arguments":{"title":"Not allowed"}}}""",
                    readOnly,
                )
            val result = refused["result"]!!.jsonObject
            assertEquals(true, result["isError"]!!.jsonPrimitive.content.toBoolean())
            val text = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
            assertTrue(text.contains("forbidden"), text)
            assertTrue(text.contains("albums:write"), text)

            assertEquals(0, creator.get("/api/albums").body<List<AlbumSummary>>().size)
        }

    @Test
    fun `a tool cannot touch another account`() =
        withApp { harness ->
            val mine = signedIn(harness, "nate@example.com")
            val token = mine.mint("albums:read", "albums:write")

            val theirs = signedIn(harness, "someone@example.com")
            val theirAlbum = theirs.createAlbum("Theirs").body<AlbumSummary>()

            val agent = browser()
            val response =
                agent.rpc(
                    """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":
                       {"name":"get_album","arguments":{"albumId":"${theirAlbum.id}"}}}""",
                    token,
                )
            val result = response["result"]!!.jsonObject
            assertEquals(true, result["isError"]!!.jsonPrimitive.content.toBoolean())
            assertFalse(response.toString().contains("Theirs"), response.toString())
        }

    @Test
    fun `a call with no token is refused`() =
        withApp {
            val response =
                browser().rpc(
                    """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":
                       {"name":"list_albums","arguments":{}}}""",
                )
            val result = response["result"]!!.jsonObject
            assertEquals(true, result["isError"]!!.jsonPrimitive.content.toBoolean())
        }

    @Test
    fun `an unknown tool and malformed JSON both answer, rather than failing silently`() =
        withApp { harness ->
            val creator = signedIn(harness)
            val token = creator.mint("albums:read")
            val agent = browser()

            val unknown =
                agent.rpc(
                    """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":
                       {"name":"delete_everything","arguments":{}}}""",
                    token,
                )
            assertEquals(true, unknown["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content.toBoolean())

            val broken =
                agent.post("/mcp") {
                    contentType(ContentType.Application.Json)
                    setBody("not json at all")
                }
            assertEquals(HttpStatusCode.BadRequest, broken.status)
            assertTrue(broken.bodyAsText().contains("-32700"), broken.bodyAsText())
        }

    @Test
    fun `llms txt and the OpenAPI document are served and say the true thing`() =
        withApp {
            val agent = browser()

            val llms = agent.get("/llms.txt")
            assertEquals(HttpStatusCode.OK, llms.status)
            val text = llms.bodyAsText()
            assertTrue(text.contains("bytes never pass through this API"), text)
            assertTrue(text.contains("albums:read"))
            assertTrue(text.contains("/openapi.json"))
            // Every error code is listed, so an agent can handle the closed set.
            com.mantel.kernel.ErrorCode.entries.forEach { assertTrue(text.contains(it.wire), "missing ${it.wire}") }

            val openapi = agent.get("/openapi.json")
            assertEquals(HttpStatusCode.OK, openapi.status)
            val document = Json.parseToJsonElement(openapi.bodyAsText()).jsonObject
            assertEquals("3.1.0", document["openapi"]!!.jsonPrimitive.content)
            val paths = document["paths"]!!.jsonObject.keys
            assertTrue("/api/albums" in paths)
            assertTrue("/api/share/{token}" in paths)
            assertTrue("/api/albums/{id}/upload-intent" in paths)
        }
}
