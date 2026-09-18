package com.mantel.features.agent

import com.mantel.kernel.Clock
import com.mantel.kernel.Config
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.storage.ObjectStorage
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val PROTOCOL_VERSION = "2025-06-18"

/**
 * The MCP server, in the same binary at /mcp, so a self-hoster gets it for free (SDD.md 9).
 *
 * It is an adapter and nothing more: every tool is one call of the same API a browser uses, with
 * the same scope check, so there is no second surface to drift out of sync. A token is presented
 * exactly as it is on the REST API.
 */
suspend fun serveMcp(
    call: ApplicationCall,
    config: Config,
    storage: ObjectStorage,
    clock: Clock,
) {
    val request =
        runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrElse {
            return call.respondText(
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            ) { error(JsonNull, -32700, "That is not JSON").toString() }
        }

    val id = request["id"] ?: JsonNull
    val method = request["method"]?.jsonPrimitive?.content

    val response =
        when (method) {
            "initialize" ->
                result(id) {
                    put("protocolVersion", PROTOCOL_VERSION)
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") {
                        put("name", "mantel")
                        put("version", "1")
                    }
                    put(
                        "instructions",
                        "Albums of photographs, shared as a link. Create an album, ask for an upload, " +
                            "send the bytes to the URLs you are given, say the upload is complete, then " +
                            "create a share link. Bytes never go through this server.",
                    )
                }

            "notifications/initialized" -> null

            "tools/list" -> result(id) { put("tools", toolDefinitions()) }

            "tools/call" -> {
                val params = request["params"]?.jsonObject ?: JsonObject(emptyMap())
                val name = params["name"]?.jsonPrimitive?.content.orEmpty()
                val arguments = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())
                runCatching { callTool(call, name, arguments, config, storage, clock) }
                    .fold(
                        onSuccess = { payload ->
                            result(id) {
                                putJsonArray("content") {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", payload.toString())
                                        },
                                    )
                                }
                                put("structuredContent", payload)
                            }
                        },
                        onFailure = { failure ->
                            // A tool failure is a result with isError, not a protocol error: the
                            // agent has to be able to read it and decide what to do.
                            val domain = failure as? DomainException
                            result(id) {
                                put("isError", true)
                                putJsonArray("content") {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put(
                                                "text",
                                                "${domain?.code?.wire ?: "internal"}: ${failure.message}",
                                            )
                                        },
                                    )
                                }
                            }
                        },
                    )
            }

            else -> error(id, -32601, "Unknown method: $method")
        }

    if (response == null) {
        call.respond(HttpStatusCode.Accepted)
    } else {
        call.respondText(ContentType.Application.Json, HttpStatusCode.OK) { response.toString() }
    }
}

private fun result(
    id: JsonElement,
    build: JsonObjectBuilderScope,
): JsonObject =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("result") { build() }
    }

private fun error(
    id: JsonElement,
    code: Int,
    message: String,
): JsonObject =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

private typealias JsonObjectBuilderScope = kotlinx.serialization.json.JsonObjectBuilder.() -> Unit

private fun JsonElement.asString(): String = jsonPrimitive.content

internal fun stringArg(
    arguments: JsonObject,
    name: String,
): String =
    arguments[name]?.takeIf { it !is JsonNull }?.asString()
        ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "$name is required")

internal fun optionalString(
    arguments: JsonObject,
    name: String,
): String? = arguments[name]?.takeIf { it !is JsonNull }?.asString()

internal fun optionalInt(
    arguments: JsonObject,
    name: String,
): Int? = (arguments[name] as? JsonPrimitive)?.content?.toIntOrNull()
