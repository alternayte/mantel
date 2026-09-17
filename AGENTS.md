# Mantel

## What this is

Mantel creates photo and video albums that are shared as a link. A recipient opens the link and sees the photos; there is no account, no app install and no tracking.

## Run

`just dev` starts postgres and minio, the API on :8080 and Vite on :5173 proxying /api.
`just worker` runs the same binary in worker mode. `just migrate` applies the migrations.
`just build` puts the SPA in the jar and builds the image. Copy `.env.example` to .env first.

## Test

`just check` is the gate: every script in `checks/`, the web build, ktlint and the Gradle build.
`just test` runs the tests alone. Docker must be running; the database tests use Testcontainers.
The photo tests shell out to `vips`, and AVIF needs libheif with an AV1 encoder. Install libvips: brew install vips, or apt libvips-tools with libheif-plugin-aomenc.

`just check-slow` runs the read-only commands quoted in agent files.

The pre-commit hook at `.githooks/pre-commit` runs `checks/vocabulary.sh` and every script in `checks/staged/` against the staged diff.

## Layout

`src/main/kotlin/com/mantel/kernel/`, `src/main/kotlin/com/mantel/storage/`,
`src/main/kotlin/com/mantel/http/`, `src/main/kotlin/com/mantel/worker/` and
`src/main/kotlin/com/mantel/features/`. One feature file holds its command or query, its validation,
its handler and its route. Flyway owns the schema in `src/main/resources/db/migration/`; Exposed
tables only read it and are registered in `src/main/kotlin/com/mantel/SchemaRegistry.kt`.
`src/test/kotlin/com/mantel/convention/` fails the build on architectural drift.

## Stack rules

- Server is Kotlin on Ktor, one JVM. The worker is the same binary in worker mode, not a separate service.
- Web is one React SPA built with Vite. Bun is a build tool only; no JavaScript runtime ships.
- PostgreSQL is state and the job queue. No broker and no job library.
- Object storage is S3-compatible: MinIO in development, Cloudflare R2 as the default deployment.
- Deployment is one app container plus PostgreSQL.
- The REST API is the only interface; every client consumes it and no client gets a branch of its own.
- The API owns every write to PostgreSQL. The worker has no database credentials.
- No event sourcing. Media lifecycle state is a pure transition function persisted to a column.
- Row ids are UUIDv7 from `Ids.uuidV7`, never `randomUUID`; they sort by creation time.
- An id, a title, a caption and a byte count are value classes, not String, UUID or Long. A rule lives in the type, once.
- A state is its enum end to end, including in Exposed columns. No status string literals outside the enum that defines them.

## Domain words

- account: a creator; owns albums and a storage quota.
- album: an ordered collection of media items.
- media item: one photo or video together with its derivatives and processing state.
- derivative: a server-generated copy of an upload.
- share link: a revocable, optionally PIN-protected, optionally expiring URL for one album.
- token: the unguessable part of a share link URL; it leaks with the URL.
- PIN: an optional second factor on a share link, checked before the manifest is served.
- manifest: the JSON document a viewer fetches to render an album.
- viewer: an anonymous recipient of a share link; not an entity, nothing stored about one.
- upload intent: the request that checks quota and returns presigned PUTs before bytes move.
- publish: the transition that creates an album's first live share link.
- worker: the same binary in worker mode; renders derivatives and reports completion to the API.
- session: a signed-in creator's browser, held by an HttpOnly cookie carrying a random secret and no identifier.
