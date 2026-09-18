# Configuration

Everything is read from the environment. In development the app also reads a `.env` file, because
the Gradle daemon does not inherit the shell that launched it. A real environment variable always
wins.

A value is here only when a deployment will set it to something other than the default.

## The app

| Variable | Default | What it does |
|---|---|---|
| `MANTEL_PORT` | `8080` | Port the API listens on |
| `MANTEL_PUBLIC_BASE_URL` | `http://localhost:8080` | How the outside world reaches this instance. Share links are built from it |
| `MANTEL_COOKIE_SECRET` | random each start | Signs the PIN unlock cookie. Unset costs viewers of PIN'd albums one extra unlock after a restart |
| `MANTEL_DEFAULT_QUOTA_BYTES` | `10737418240` (10 GiB) | Storage a new account gets. One number, no tiers |

## PostgreSQL

| Variable | Default |
|---|---|
| `MANTEL_DB_URL` | `jdbc:postgresql://localhost:5432/mantel` |
| `MANTEL_DB_USER` | `mantel` |
| `MANTEL_DB_PASSWORD` | `mantel` |

Migrations run at startup. A migration whose name it cannot parse stops the app rather than being
skipped.

## Object storage

Anything S3-compatible. MinIO in development, Cloudflare R2 by default in deployment.

| Variable | Default | What it does |
|---|---|---|
| `MANTEL_S3_ENDPOINT` | `http://localhost:9100` | Where **this server** reaches storage |
| `MANTEL_S3_PUBLIC_ENDPOINT` | the endpoint | Where **a browser** reaches storage, when that differs. Presigned URLs are signed with it |
| `MANTEL_S3_BUCKET` | `mantel` | |
| `MANTEL_S3_REGION` | `auto` | `auto` suits R2 |
| `MANTEL_S3_ACCESS_KEY_ID` | | |
| `MANTEL_S3_SECRET_ACCESS_KEY` | | |
| `MANTEL_S3_FORCE_PATH_STYLE` | `true` | MinIO needs it; R2 does not mind |
| `MANTEL_MULTIPART_THRESHOLD_BYTES` | `67108864` (64 MiB) | Above this a file uploads in parts |
| `MANTEL_MULTIPART_PART_BYTES` | `16777216` (16 MiB) | Part size. S3 refuses parts under 5 MiB |

## The worker

| Variable | Default | What it does |
|---|---|---|
| `MANTEL_WORKER_TOKEN` | unset | Shared secret between the worker and the API. **Without it the worker endpoints are closed.** The worker holds no database credentials |
| `MANTEL_CLAIM_TIMEOUT_SECONDS` | `600` | A claim older than this returns to the queue. Must exceed your slowest transcode |
| `MANTEL_MAX_ATTEMPTS` | `3` | Attempts before an item is failed |
| `MANTEL_WORKER_BATCH` | `4` | Items claimed at once |
| `MANTEL_WORKER_POLL_SECONDS` | `5` | Pause when there is no work |
| `MANTEL_RECONCILE_INTERVAL_HOURS` | `6` | How often storage is walked for objects no row owns |

## Sign-in mail

With no SMTP host the magic link is written to the log, which is what local work reads and what a
misconfigured deployment should notice.

| Variable | Default |
|---|---|
| `MANTEL_SMTP_HOST` | unset |
| `MANTEL_SMTP_PORT` | `587` |
| `MANTEL_SMTP_USERNAME` | unset |
| `MANTEL_SMTP_PASSWORD` | unset |
| `MANTEL_SMTP_FROM` | `mantel@localhost` |
| `MANTEL_SMTP_STARTTLS` | `true` |

## GitHub sign-in

Optional. Without a client id the app tells the sign-in page not to offer it.

| Variable | Default |
|---|---|
| `MANTEL_GITHUB_CLIENT_ID` | unset |
| `MANTEL_GITHUB_CLIENT_SECRET` | required when the id is set |

## Development only

| Variable | What it does |
|---|---|
| `MANTEL_DEV_ASSETS_ORIGIN` | Set by `just dev` so the album page at :8080 loads modules from Vite on :5173 |
| `MANTEL_MINIO_PORT`, `MANTEL_MINIO_CONSOLE_PORT` | Host ports for MinIO in compose |
