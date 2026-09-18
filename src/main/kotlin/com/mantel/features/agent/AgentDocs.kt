package com.mantel.features.agent

import com.mantel.kernel.Config
import com.mantel.kernel.ErrorCode

/**
 * The entry point an agent reads first. Short on purpose: it says what the product is, how to get a
 * token, the shape of the work, and where the full document is (SDD.md 9).
 */
fun llmsTxt(config: Config): String =
    """
    # Mantel

    > Photo and video albums shared as a link. No viewer account, no tracking. Open source, AGPL-3.0.

    One REST API serves the web client, any other client and you. Nothing here is an agent-only
    surface, so anything you learn about the API is true for everyone.

    ## Getting in

    A person signs in and creates a token at ${config.publicBaseUrl}/app/settings. Present it as
    `Authorization: Bearer mantel_…`. Scopes are `albums:read`, `albums:write` and `share:write`.
    No scope reads another account, and `albums:read` does not hand out share links.

    ## The shape of the work

    1. `POST /api/albums` — create an album.
    2. `POST /api/albums/{id}/upload-intent` — declare filenames, content types and exact sizes.
       You get a URL per file. Large files come back as parts.
    3. `PUT` each file to the URL you were given. **The bytes never pass through this API.**
    4. `POST /api/albums/{id}/uploads/complete` — once for the whole batch.
    5. Photographs are rendered in the background. `GET /api/albums/{id}` shows each item's state.
    6. `POST /api/albums/{id}/share-links` — this is what publishing is. It returns the URL to send.

    ## Errors

    Every failure is `{"error":{"code":"…","message":"…","details":{}}}`. The code is contract; the
    message is for humans. The closed set:

    ${ErrorCode.entries.joinToString("\n    ") { "- `${it.wire}` (${it.status})" }}

    ## Documents

    - OpenAPI: ${config.publicBaseUrl}/openapi.json
    - MCP endpoint: ${config.publicBaseUrl}/mcp (JSON-RPC over POST, same bearer token)
    - Written guide: https://github.com/alternayte/mantel/blob/main/docs/api.md

    ## Things worth knowing before you try

    - Quota is checked before any upload URL exists. A batch that does not fit is refused whole.
    - A revoked or expired share link is `not_found`, never a message saying it was revoked.
    - EXIF is stripped from everything served. Originals keep theirs, and are an explicit choice.
    - Deleting an album deletes bytes. There is no undo.
    """.trimIndent()

// The state names come from the enums rather than being typed again here: a document that can
// disagree with the code is worse than no document.
private fun albumStatuses() = com.mantel.features.album.AlbumStatus.entries.joinToString(", ") { "\"${it.wire}\"" }

private fun itemStates() = com.mantel.features.media.ItemState.entries.joinToString(", ") { "\"${it.wire}\"" }

private fun mediaKinds() = com.mantel.features.media.MediaKind.entries.joinToString(", ") { "\"${it.wire}\"" }

/**
 * The OpenAPI document, written by hand next to the routes it describes rather than generated from
 * annotations that drift. The enums it names come from the code, and a route missing from it is
 * caught by a convention test.
 */
fun openApiDocument(config: Config): String =
    """
    {
      "openapi": "3.1.0",
      "info": {
        "title": "Mantel",
        "version": "${com.mantel.kernel.Version.current}",
        "description": "Photo and video albums shared as a link. The same API the web client uses.",
        "license": { "name": "AGPL-3.0-or-later" }
      },
      "servers": [{ "url": "${config.publicBaseUrl}" }],
      "security": [{ "bearerToken": [] }],
      "components": {
        "securitySchemes": {
          "bearerToken": {
            "type": "http",
            "scheme": "bearer",
            "description": "An API token created at /app/settings. Scopes: albums:read, albums:write, share:write."
          },
          "sessionCookie": { "type": "apiKey", "in": "cookie", "name": "mantel_session" }
        },
        "schemas": {
          "Error": {
            "type": "object",
            "properties": {
              "error": {
                "type": "object",
                "required": ["code", "message"],
                "properties": {
                  "code": {
                    "type": "string",
                    "enum": [${ErrorCode.entries.joinToString(", ") { "\"${it.wire}\"" }}]
                  },
                  "message": { "type": "string" },
                  "details": { "type": "object", "additionalProperties": { "type": "string" } }
                }
              }
            }
          },
          "Album": {
            "type": "object",
            "properties": {
              "id": { "type": "string" },
              "title": { "type": "string" },
              "description": { "type": ["string", "null"] },
              "status": { "type": "string", "enum": [${albumStatuses()}] },
              "itemCount": { "type": "integer" },
              "totalBytes": { "type": "integer" },
              "coverItemId": { "type": ["string", "null"] },
              "createdAt": { "type": "string", "format": "date-time" },
              "updatedAt": { "type": "string", "format": "date-time" }
            }
          },
          "Item": {
            "type": "object",
            "properties": {
              "id": { "type": "string" },
              "position": { "type": "integer" },
              "kind": { "type": "string", "enum": [${mediaKinds()}] },
              "status": { "type": "string", "enum": [${itemStates()}] },
              "byteSize": { "type": "integer" },
              "caption": { "type": ["string", "null"] },
              "width": { "type": ["integer", "null"] },
              "height": { "type": ["integer", "null"] },
              "durationMs": { "type": ["integer", "null"] },
              "filename": { "type": ["string", "null"] },
              "lastError": { "type": ["string", "null"] },
              "thumbUrl": { "type": ["string", "null"] }
            }
          },
          "ShareLink": {
            "type": "object",
            "properties": {
              "id": { "type": "string" },
              "url": { "type": "string" },
              "token": { "type": "string" },
              "hasPin": { "type": "boolean" },
              "expiresAt": { "type": ["string", "null"] },
              "revokedAt": { "type": ["string", "null"] },
              "createdAt": { "type": "string" },
              "live": { "type": "boolean" }
            }
          }
        }
      },
      "paths": {
        "/api/me": {
          "get": {
            "summary": "The signed-in account",
            "responses": { "200": { "description": "The account and its storage use" } }
          }
        },
        "/api/albums": {
          "get": {
            "summary": "Every album this account owns",
            "description": "Needs albums:read.",
            "responses": {
              "200": {
                "description": "Albums, newest change first",
                "content": {
                  "application/json": {
                    "schema": { "type": "array", "items": { "${'$'}ref": "#/components/schemas/Album" } }
                  }
                }
              }
            }
          },
          "post": {
            "summary": "Create an album",
            "description": "Needs albums:write.",
            "requestBody": {
              "required": true,
              "content": {
                "application/json": {
                  "schema": {
                    "type": "object",
                    "required": ["title"],
                    "properties": {
                      "title": { "type": "string", "maxLength": 200 },
                      "description": { "type": "string" }
                    }
                  }
                }
              }
            },
            "responses": {
              "201": {
                "description": "The album",
                "content": { "application/json": { "schema": { "${'$'}ref": "#/components/schemas/Album" } } }
              },
              "422": {
                "description": "The title is empty or too long",
                "content": { "application/json": { "schema": { "${'$'}ref": "#/components/schemas/Error" } } }
              }
            }
          }
        },
        "/api/albums/{id}": {
          "get": {
            "summary": "One album with its items",
            "description": "Needs albums:read.",
            "parameters": [{ "name": "id", "in": "path", "required": true, "schema": { "type": "string" } }],
            "responses": { "200": { "description": "The album and its items" }, "404": { "description": "Not yours, or not there" } }
          },
          "patch": {
            "summary": "Retitle, describe, or set the cover",
            "description": "Needs albums:write.",
            "parameters": [{ "name": "id", "in": "path", "required": true, "schema": { "type": "string" } }],
            "responses": { "200": { "description": "The album" } }
          },
          "delete": {
            "summary": "Archive the album",
            "description": "Needs albums:write.",
            "parameters": [{ "name": "id", "in": "path", "required": true, "schema": { "type": "string" } }],
            "responses": { "204": { "description": "Archived" } }
          }
        },
        "/api/library": {
          "get": {
            "summary": "Every media item the account owns, newest first",
            "description": "Needs albums:read. Pages by item id: pass the previous page's `next` as `after`.",
            "parameters": [
              { "name": "after", "in": "query", "required": false, "schema": { "type": "string" } },
              { "name": "limit", "in": "query", "required": false, "schema": { "type": "integer" } }
            ],
            "responses": { "200": { "description": "A page of the library" } }
          }
        },
        "/api/library/{itemId}": {
          "delete": {
            "summary": "Delete media from the library",
            "description": "Needs albums:write. Removes the bytes, frees quota, and takes the item out of every album.",
            "parameters": [{ "name": "itemId", "in": "path", "required": true, "schema": { "type": "string" } }],
            "responses": { "204": { "description": "Deleted" } }
          }
        },
        "/api/library/{itemId}/upload-progress": {
          "get": {
            "summary": "What storage already holds for an interrupted upload",
            "description": "Needs albums:write. Fresh URLs for the parts that did not arrive.",
            "parameters": [{ "name": "itemId", "in": "path", "required": true, "schema": { "type": "string" } }],
            "responses": { "200": { "description": "Received and remaining parts" } }
          }
        },
        "/api/library/upload-intent": {
          "post": {
            "summary": "Check quota and get an upload URL per file, with no album",
            "description": "Needs albums:write. The library keeps any file; an album takes only what can be rendered.",
            "responses": { "200": { "description": "One presigned upload per file" } }
          }
        },
        "/api/library/uploads/complete": {
          "post": {
            "summary": "Tell the API the bytes arrived",
            "description": "Needs albums:write. One call for the whole batch.",
            "responses": { "200": { "description": "What arrived and what did not" } }
          }
        },
        "/api/albums/{id}/items": {
          "post": {
            "summary": "Put media that is already in the library into this album",
            "description": "Needs albums:write. Costs no quota and no upload: an album is a selection.",
            "parameters": [{ "name": "id", "in": "path", "required": true, "schema": { "type": "string" } }],
            "responses": { "204": { "description": "Added" } }
          }
        },
        "/api/albums/{id}/upload-intent": {
          "post": {
            "summary": "Check quota and get an upload URL per file",
            "description": "Needs albums:write. The bytes never pass through this API.",
            "parameters": [{ "name": "id", "in": "path", "required": true, "schema": { "type": "string" } }],
            "requestBody": {
              "required": true,
              "content": {
                "application/json": {
                  "schema": {
                    "type": "object",
                    "required": ["files"],
                    "properties": {
                      "files": {
                        "type": "array",
                        "items": {
                          "type": "object",
                          "required": ["filename", "contentType", "sizeBytes"],
                          "properties": {
                            "filename": { "type": "string" },
                            "contentType": { "type": "string" },
                            "sizeBytes": { "type": "integer" }
                          }
                        }
                      }
                    }
                  }
                }
              }
            },
            "responses": {
              "200": { "description": "A URL per file, or parts for a large one" },
              "413": {
                "description": "The batch does not fit in the remaining quota",
                "content": { "application/json": { "schema": { "${'$'}ref": "#/components/schemas/Error" } } }
              }
            }
          }
        },
        "/api/albums/{id}/uploads/complete": {
          "post": {
            "summary": "Say the batch has arrived",
            "description": "Needs albums:write. Storage is asked whether each object is really there.",
            "parameters": [{ "name": "id", "in": "path", "required": true, "schema": { "type": "string" } }],
            "responses": { "200": { "description": "Which items arrived and which are missing" } }
          }
        },
        "/api/albums/{id}/items/reorder": {
          "patch": {
            "summary": "Put the album in this order",
            "description": "Needs albums:write. Name every item exactly once.",
            "parameters": [{ "name": "id", "in": "path", "required": true, "schema": { "type": "string" } }],
            "responses": { "204": { "description": "Reordered" } }
          }
        },
        "/api/albums/{id}/items/{itemId}": {
          "patch": {
            "summary": "Set or clear a caption",
            "description": "Needs albums:write.",
            "parameters": [
              { "name": "id", "in": "path", "required": true, "schema": { "type": "string" } },
              { "name": "itemId", "in": "path", "required": true, "schema": { "type": "string" } }
            ],
            "responses": { "204": { "description": "Set" } }
          },
          "delete": {
            "summary": "Remove an item, its files and its bytes",
            "description": "Needs albums:write. There is no undo.",
            "parameters": [
              { "name": "id", "in": "path", "required": true, "schema": { "type": "string" } },
              { "name": "itemId", "in": "path", "required": true, "schema": { "type": "string" } }
            ],
            "responses": { "204": { "description": "Gone" } }
          }
        },
        "/api/albums/{id}/share-links": {
          "get": {
            "summary": "Every link for this album",
            "description": "Needs share:write: a link is what gives an album away.",
            "parameters": [{ "name": "id", "in": "path", "required": true, "schema": { "type": "string" } }],
            "responses": { "200": { "description": "Links, newest first" } }
          },
          "post": {
            "summary": "Create a share link, which is what publishing is",
            "description": "Needs share:write.",
            "parameters": [{ "name": "id", "in": "path", "required": true, "schema": { "type": "string" } }],
            "requestBody": {
              "content": {
                "application/json": {
                  "schema": {
                    "type": "object",
                    "properties": {
                      "pin": { "type": "string", "description": "4 to 12 digits" },
                      "expiresInDays": { "type": "integer", "enum": [7, 30, 90] }
                    }
                  }
                }
              }
            },
            "responses": {
              "201": {
                "description": "The link",
                "content": { "application/json": { "schema": { "${'$'}ref": "#/components/schemas/ShareLink" } } }
              }
            }
          }
        },
        "/api/share-links/{id}": {
          "delete": {
            "summary": "Revoke a link, immediately",
            "description": "Needs share:write.",
            "parameters": [{ "name": "id", "in": "path", "required": true, "schema": { "type": "string" } }],
            "responses": { "204": { "description": "Revoked" } }
          }
        },
        "/api/share/{token}": {
          "get": {
            "summary": "The public manifest for a share link",
            "security": [],
            "parameters": [{ "name": "token", "in": "path", "required": true, "schema": { "type": "string" } }],
            "responses": {
              "200": { "description": "The album as a viewer sees it" },
              "401": { "description": "pin_required" },
              "404": { "description": "Unknown, revoked or expired. The three are indistinguishable." }
            }
          }
        },
        "/api/share/{token}/download": {
          "get": {
            "summary": "The offline bundle",
            "security": [],
            "parameters": [
              { "name": "token", "in": "path", "required": true, "schema": { "type": "string" } },
              { "name": "originals", "in": "query", "schema": { "type": "boolean" } }
            ],
            "responses": {
              "302": { "description": "A URL to the packed album" },
              "202": { "description": "It is being packed. Ask again." }
            }
          }
        },
        "/api/tokens": {
          "get": {
            "summary": "This account's API tokens",
            "description": "A signed-in person only; a token cannot list tokens.",
            "responses": { "200": { "description": "Tokens, newest first" } }
          },
          "post": {
            "summary": "Create a scoped token",
            "description": "A signed-in person only. The token is shown once.",
            "responses": { "201": { "description": "The token, with its secret" } }
          }
        },
        "/api/tokens/{id}": {
          "delete": {
            "summary": "Revoke a token",
            "description": "A signed-in person only. Immediate.",
            "parameters": [{ "name": "id", "in": "path", "required": true, "schema": { "type": "string" } }],
            "responses": { "204": { "description": "Revoked" } }
          }
        }
      }
    }
    """.trimIndent()
