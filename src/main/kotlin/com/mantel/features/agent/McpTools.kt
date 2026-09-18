package com.mantel.features.agent

import com.mantel.features.album.CreateAlbumRequest
import com.mantel.features.album.albumIdOf
import com.mantel.features.album.createAlbumFor
import com.mantel.features.album.listAlbumsFor
import com.mantel.features.album.readAlbumFor
import com.mantel.features.media.DeclaredFile
import com.mantel.features.media.completeUploadsFor
import com.mantel.features.media.setCaptionFor
import com.mantel.features.media.uploadIntentFor
import com.mantel.features.share.createShareLinkFor
import com.mantel.features.share.revokeShareLinkFor
import com.mantel.kernel.Clock
import com.mantel.kernel.Config
import com.mantel.kernel.DomainException
import com.mantel.kernel.ErrorCode
import com.mantel.storage.ObjectStorage
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val json = Json { encodeDefaults = true }

/**
 * The nine tools SDD.md 9 names. Each is one command — the same function the REST route calls, with
 * the same scope check — so there is no second implementation to drift.
 */
suspend fun callTool(
    call: ApplicationCall,
    name: String,
    arguments: JsonObject,
    config: Config,
    storage: ObjectStorage,
    clock: Clock,
): JsonElement {
    val caller = requireCaller(call, clock)

    return when (name) {
        "list_albums" -> json.encodeToJsonElement(listAlbumsFor(caller))

        "create_album" ->
            json.encodeToJsonElement(
                createAlbumFor(
                    caller,
                    CreateAlbumRequest(stringArg(arguments, "title"), optionalString(arguments, "description")),
                    clock,
                ),
            )

        "get_album" -> json.encodeToJsonElement(readAlbumFor(caller, albumIdOf(stringArg(arguments, "albumId")), storage))

        "request_upload" -> {
            val files =
                (arguments["files"] as? JsonArray)?.map { element ->
                    val file = element.jsonObject
                    DeclaredFile(
                        filename =
                            file["filename"]?.jsonPrimitive?.content
                                ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "filename is required"),
                        contentType =
                            file["contentType"]?.jsonPrimitive?.content
                                ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "contentType is required"),
                        sizeBytes =
                            file["sizeBytes"]?.jsonPrimitive?.content?.toLongOrNull()
                                ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "sizeBytes is required"),
                    )
                } ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "files is required")

            json.encodeToJsonElement(
                uploadIntentFor(caller, albumIdOf(stringArg(arguments, "albumId")), files, config, storage, clock),
            )
        }

        "complete_upload" ->
            json.encodeToJsonElement(
                completeUploadsFor(
                    caller,
                    albumIdOf(stringArg(arguments, "albumId")),
                    stringList(arguments, "itemIds"),
                    storage,
                ),
            )

        "set_caption" -> {
            setCaptionFor(
                caller,
                albumIdOf(stringArg(arguments, "albumId")),
                stringArg(arguments, "itemId"),
                optionalString(arguments, "caption"),
                clock,
            )
            buildJsonObject { put("ok", true) }
        }

        "reorder_items" -> {
            com.mantel.features.media.reorderItemsFor(
                caller,
                albumIdOf(stringArg(arguments, "albumId")),
                stringList(arguments, "itemIds"),
                clock,
            )
            buildJsonObject { put("ok", true) }
        }

        // Publishing is creating the first live link; there is no separate act (SDD.md 4.2).
        "publish_album", "create_share_link" ->
            json.encodeToJsonElement(
                createShareLinkFor(
                    caller,
                    albumIdOf(stringArg(arguments, "albumId")),
                    optionalString(arguments, "pin"),
                    optionalInt(arguments, "expiresInDays"),
                    config,
                    storage,
                    clock,
                ),
            )

        "revoke_share_link" -> {
            revokeShareLinkFor(caller, stringArg(arguments, "shareLinkId"), storage, clock)
            buildJsonObject { put("ok", true) }
        }

        else -> throw DomainException(ErrorCode.NOT_FOUND, "There is no tool called $name")
    }
}

private fun stringList(
    arguments: JsonObject,
    name: String,
): List<String> =
    (arguments[name] as? JsonArray)?.map { it.jsonPrimitive.content }
        ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "$name is required")

/** What an agent is told it can do, and what each thing needs. */
fun toolDefinitions(): JsonArray =
    buildJsonArray {
        add(
            tool(
                "list_albums",
                "Every album this token's account owns, newest change first.",
                required = emptyList(),
            ) {},
        )
        add(
            tool("create_album", "Create an empty album and return it.", required = listOf("title")) {
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "What the album is called. 1 to 200 characters.")
                }
                putJsonObject("description") {
                    put("type", "string")
                    put("description", "Optional line under the title.")
                }
            },
        )
        add(
            tool("get_album", "One album with its items, their state and their thumbnails.", required = listOf("albumId")) {
                putJsonObject("albumId") { put("type", "string") }
            },
        )
        add(
            tool(
                "request_upload",
                "Check quota and get a URL per file. The bytes are then PUT to those URLs directly; " +
                    "they never pass through this server. A large file comes back as parts instead.",
                required = listOf("albumId", "files"),
            ) {
                putJsonObject("albumId") { put("type", "string") }
                putJsonObject("files") {
                    put("type", "array")
                    put("description", "Each file's name, content type and exact size in bytes.")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("filename") { put("type", "string") }
                            putJsonObject("contentType") { put("type", "string") }
                            putJsonObject("sizeBytes") { put("type", "integer") }
                        }
                    }
                }
            },
        )
        add(
            tool(
                "complete_upload",
                "Tell the album the bytes have arrived, once for the whole batch. Storage is asked " +
                    "whether each object is really there; anything missing stays pending.",
                required = listOf("albumId", "itemIds"),
            ) {
                putJsonObject("albumId") { put("type", "string") }
                putJsonObject("itemIds") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
            },
        )
        add(
            tool("set_caption", "Set or clear one item's caption.", required = listOf("albumId", "itemId")) {
                putJsonObject("albumId") { put("type", "string") }
                putJsonObject("itemId") { put("type", "string") }
                putJsonObject("caption") {
                    put("type", "string")
                    put("description", "Leave it out to clear the caption.")
                }
            },
        )
        add(
            tool(
                "reorder_items",
                "Put the album in this order. Name every item exactly once.",
                required = listOf("albumId", "itemIds"),
            ) {
                putJsonObject("albumId") { put("type", "string") }
                putJsonObject("itemIds") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
            },
        )
        add(
            tool(
                "publish_album",
                "Create the album's first share link, which is what publishing is. Returns the URL.",
                required = listOf("albumId"),
            ) {
                putJsonObject("albumId") { put("type", "string") }
                putJsonObject("pin") {
                    put("type", "string")
                    put("description", "Optional second factor, 4 to 12 digits.")
                }
                putJsonObject("expiresInDays") {
                    put("type", "integer")
                    put("description", "7, 30 or 90. Leave it out for a link that does not expire.")
                }
            },
        )
        add(
            tool(
                "create_share_link",
                "Another link to the same album, with its own PIN and expiry.",
                required = listOf("albumId"),
            ) {
                putJsonObject("albumId") { put("type", "string") }
                putJsonObject("pin") { put("type", "string") }
                putJsonObject("expiresInDays") { put("type", "integer") }
            },
        )
        add(
            tool("revoke_share_link", "Revoke one link. Immediate, and it cannot be undone.", required = listOf("shareLinkId")) {
                putJsonObject("shareLinkId") { put("type", "string") }
            },
        )
    }

private fun tool(
    name: String,
    description: String,
    required: List<String>,
    properties: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
): JsonObject =
    buildJsonObject {
        put("name", name)
        put("description", description)
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") { properties() }
            putJsonArray("required") { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        }
    }
