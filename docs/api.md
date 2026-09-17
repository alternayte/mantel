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
