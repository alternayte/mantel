# API

The REST API is the product surface. The web client, the Android client (v1.1) and agents all use
this one API. `code` in an error is contract; `message` is for humans and may change.

The OpenAPI document arrives at M9. Until then this file is the reference.

## Errors

```json
{ "error": { "code": "unauthenticated", "message": "Sign in first", "details": {} } }
```

The code set is closed. Adding a code is an API change.

| Code | HTTP | Means |
|---|---|---|
| `not_found` | 404 | No such thing, or you may not know whether there is |
| `unauthenticated` | 401 | No session or API token |
| `forbidden` | 403 | Signed in, not allowed |
| `validation_failed` | 422 | The request is wrong, including a spent or expired sign-in link |
| `conflict` | 409 | The state does not allow this |
| `quota_exceeded` | 413 | The batch needs more space than the account has left |
| `rate_limited` | 429 | Too many attempts; wait |
| `internal` | 500 | A fault on our side |

## Authentication

A creator session is an HttpOnly, SameSite=Lax cookie named `mantel_session`, set only by a
sign-in. It is `Secure` when the request arrives over HTTPS. Viewer routes set no cookie unless a
share link has a PIN and the viewer unlocks it (M5).

### `POST /api/auth/magic-link`

```json
{ "email": "nate@example.com" }
```

`202` with `{"status":"sent"}` whether or not the address has an account, so the endpoint does not
report who is registered. Requesting a link for an unknown address creates the account; there is no
separate sign-up. The link works once and expires in 15 minutes. Five requests per address per
hour, then `rate_limited`.

### `GET /api/auth/magic-link/callback?token=…`

Consumes the link, sets the session cookie and redirects to `/app`. A spent, unknown or expired
token is `validation_failed`.

### `GET /api/auth/github`

Redirects to GitHub with a `state` value held in a short-lived cookie scoped to the callback.
`validation_failed` when GitHub sign-in is not configured.

### `GET /api/auth/github/callback?code=…&state=…`

Verifies `state`, exchanges the code, and signs in. GitHub identifies an account by its numeric id,
or by a verified primary email address; an unverified address cannot claim an existing account.
Sets the session cookie and redirects to `/app`.

### `POST /api/auth/logout`

`204`. Deletes the session row and clears the cookie.

## Account

### `GET /api/me`

```json
{
  "email": "nate@example.com",
  "displayName": "Nate",
  "storageQuotaBytes": 10737418240,
  "storageUsedBytes": 0
}
```

### `GET /api/account/export`

Everything held about the account, as one JSON document, served as an attachment. Albums and media
items join it in the milestones that create them.

### `DELETE /api/account`

`204`. Deletes the account row, which cascades to sessions and sign-in links, and deletes every
object under the account's storage prefix. Objects go first, so a failure never leaves storage with
no row pointing at it. The email address is free to sign up again afterwards.

## Albums

An album is `draft` while it is assembled, `ready` when every item has finished processing,
`published` while a live share link exists (M5), and `archived` once deleted. Publishing is not a
separate action: the first live share link publishes the album.

An album belonging to another account is `not_found`, never `forbidden`. A creator learns nothing
about an album that is not theirs, including whether it exists.

### `GET /api/albums`

The account's albums, newest change first. Archived albums are not listed.

### `POST /api/albums`

```json
{ "title": "Cornwall", "description": "three days" }
```

`201` with the album. A title is 1 to 200 characters.

### `GET /api/albums/{id}`

The album and its items in position order.

### `PATCH /api/albums/{id}`

`title`, `description` and `coverItemId`, each optional. The cover must be an item in this album.

### `DELETE /api/albums/{id}`

`204`. Archives the album. The bytes and the quota they hold go when the purge job runs.

### `GET /api/albums/{id}/status`

Per-item processing progress: totals for ready, failed and still coming, with the items themselves.
The creator polls this while assembling, and the viewer's manifest reads the same rows.

## Upload

Bytes go from the client to object storage and never through this server.

```
1. POST /api/albums/{id}/upload-intent   declare the batch
2. PUT to each presigned URL             the client uploads directly
3. POST /api/albums/{id}/uploads/complete   one call for the whole batch
```

### `POST /api/albums/{id}/upload-intent`

```json
{ "files": [ { "filename": "beach.jpg", "contentType": "image/jpeg", "sizeBytes": 2048 } ] }
```

Quota is checked and the declared bytes are reserved before any URL is issued. A batch that does
not fit is refused whole, with `quota_exceeded`; the part that would fit is not accepted. At most
200 files in one batch.

Accepted types: `image/jpeg`, `image/png`, `image/webp`, `image/heic`, `image/heif`, `video/mp4`,
`video/quicktime`. Anything else is `validation_failed`.

```json
{
  "items": [
    {
      "itemId": "…",
      "filename": "beach.jpg",
      "uploadUrl": "https://storage.example/…",
      "contentType": "image/jpeg",
      "sizeBytes": 2048
    }
  ],
  "expiresInSeconds": 3600
}
```

Each URL is signed for exactly that length and content type. Uploading anything else is refused by
the storage provider, so the declared size is enforced rather than trusted.

**A file above `MANTEL_MULTIPART_THRESHOLD_BYTES` (64 MiB by default) uploads in parts instead.**
That item carries `uploadId` and `parts` in place of `uploadUrl`:

```json
{
  "itemId": "…",
  "filename": "clip.mp4",
  "contentType": "video/mp4",
  "sizeBytes": 104857600,
  "uploadId": "…",
  "parts": [
    { "partNumber": 1, "uploadUrl": "https://storage.example/…", "sizeBytes": 16777216 }
  ]
}
```

Each part is a separate PUT and can be retried alone. The client keeps no ETags: completion reads
what storage holds.

### `GET /api/albums/{id}/items/{itemId}/upload-progress`

For an interrupted part upload: what storage already has, and fresh URLs for what it does not.

```json
{
  "itemId": "…",
  "uploadId": "…",
  "sizeBytes": 104857600,
  "received": [ { "partNumber": 1, "etag": "…", "sizeBytes": 16777216 } ],
  "remaining": [ { "partNumber": 2, "uploadUrl": "…", "sizeBytes": 16777216 } ]
}
```

Storage is the source of truth, so a client that lost its page, its connection or its laptop sends
only what is missing. `conflict` if the upload was a single PUT or has already finished.

### `POST /api/albums/{id}/uploads/complete`

```json
{ "itemIds": ["…", "…"] }
```

One call for the whole batch. Storage is asked whether each object actually arrived; those that did
become `uploaded` and enter the processing queue, and the rest come back under `missing` and stay
`pending_upload`.

A part upload is finalised here: the API lists the parts storage holds and, when they add up to the
declared size, completes the upload with the provider. An incomplete set stays `pending_upload` and
remains resumable.

```json
{ "uploaded": ["…"], "missing": ["…"] }
```

### `PATCH /api/albums/{id}/items/reorder`

```json
{ "itemIds": ["…", "…", "…"] }
```

Names every item in the album exactly once, in the new order. `204`.

### `PATCH /api/albums/{id}/items/{itemId}`

```json
{ "caption": "low tide" }
```

`204`. An empty caption clears it. At most 500 characters.

### `DELETE /api/albums/{id}/items/{itemId}`

`204`. Deletes the item's objects, returns its bytes to the account, and closes the gap in
positions.

### `POST /api/albums/{id}/items/{itemId}/retry`

`204`. Puts a failed item back on the queue with its attempts reset. Only a failed item can be
retried; anything else is `conflict`.

## Worker endpoints

The worker holds no database credentials. It asks for work and reports outcomes over HTTP, with
`Authorization: Bearer $MANTEL_WORKER_TOKEN`. Without a configured token these are closed.

### `POST /api/worker/claim`

```json
{ "limit": 4 }
```

Claims up to that many items with `SKIP LOCKED`, so two workers never take the same row. A claim
older than `MANTEL_CLAIM_TIMEOUT_SECONDS` is presumed abandoned and is claimed again, which is what
recovers an item from a worker that died holding it. Each claim counts an attempt.

```json
[ { "itemId": "…", "kind": "photo", "attempt": 1, "originalKey": "…",
    "thumbKey": "…", "displayWebpKey": "…", "displayAvifKey": "…" } ]
```

The API names the derivative keys, so the key layout stays owned by one place.

### `POST /api/worker/items/{itemId}/derivatives`

```json
{ "thumbKey": "…", "displayWebpKey": "…", "displayAvifKey": "…", "width": 2400, "height": 1600 }
```

Marks the item ready and settles the album. An album whose items have all finished becomes `ready`.

### `POST /api/worker/items/{itemId}/failure`

```json
{ "error": "vips thumbnail failed: …" }
```

Puts the item back on the queue with a widening gap between attempts (1 minute, then 4, then 16).
After `MANTEL_MAX_ATTEMPTS` the item is `failed` with `lastError` set, and the creator sees it as a
failure to retry or remove. A failed item is never claimed again and never disappears.

## Item states

`pending_upload → uploaded → processing → ready | failed`

A failed item can be retried (M3). The transition is a pure function; illegal transitions are a
programming error, not a request error.
