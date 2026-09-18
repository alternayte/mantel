# Agents

"AI-native" here means an agent can drive the product, not that the product contains a chatbot
(`SDD.md` §9). "Make an album from this folder and send Mum the link" is a real instruction someone
would give an agent, and this is the surface for it.

There is one API. Agents use the same endpoints the web client uses, so anything you learn here is
true for everyone.

## Start here

`GET /llms.txt` on any instance. It names the shape of the work, the closed error-code set and the
other documents. `GET /openapi.json` is the full description; its state enums are generated from the
same code the API runs, so it cannot disagree with the implementation.

## Tokens

A person creates a token at `/app/settings` and gives it scopes:

| Scope | Allows |
|---|---|
| `albums:read` | List albums, read one with its items |
| `albums:write` | Create and change albums, upload, caption, reorder, delete |
| `share:write` | List, create and revoke share links |

Present it as `Authorization: Bearer mantel_…`.

**No scope reads another account.** Another account's album answers `not_found`, exactly as it does
for a person, so a token cannot even confirm it exists. **`albums:read` does not list share links**,
because a link is what gives an album away. Minting a token, exporting an account and deleting one
need a signed-in person: a token that can mint a token cannot be scoped.

`TokenScopeTest` is the proof, and it runs on every commit.

## MCP

`POST /mcp`, JSON-RPC, same bearer token. Ten tools:

`list_albums`, `create_album`, `get_album`, `request_upload`, `complete_upload`, `set_caption`,
`reorder_items`, `publish_album`, `create_share_link`, `revoke_share_link`.

Every tool is one call of the same command a REST route calls, with the same scope check. There is
no agent-only path through this product, and therefore nothing to drift.

## The shape of the work

```
create_album            → an album id
request_upload          → a URL per file, after a quota check
PUT each file           → straight to storage. The bytes never pass through the API
complete_upload         → once for the whole batch
get_album               → poll until every item is ready or failed
publish_album           → the link to send
```

## What agents get wrong

- **Quota is checked before any URL exists.** A batch that does not fit is refused whole, with
  `quota_exceeded`. Declare the real sizes.
- **`complete_upload` is one call for the batch,** not one per file. Storage is asked whether each
  object really arrived; anything missing stays pending and can be re-sent.
- **A large file comes back as parts,** not one URL. Upload each part, then complete as usual;
  the API finalises with the provider, so you never handle ETags.
- **Rendering is asynchronous.** An item is `pending_upload`, `uploaded`, `processing`, then `ready`
  or `failed`. A published album shows the ready ones and marks the rest.
- **Publishing is creating a link.** There is no separate publish action, and revoking the last live
  link unpublishes the album.
- **Deleting deletes bytes.** There is no undo, for an item or for an account.
