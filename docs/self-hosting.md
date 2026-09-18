# Self-hosting

Self-hosting is a first-class path, not a courtesy (`SDD.md` §11). The product is one app container
and PostgreSQL; object storage is anything S3-compatible.

## The smallest real deployment

```yaml
services:
  app:
    image: ghcr.io/alternayte/mantel:latest   # or a version tag, which is what a deployment should pin
    environment:
      MANTEL_PUBLIC_BASE_URL: https://albums.example.com
      MANTEL_DB_URL: jdbc:postgresql://postgres:5432/mantel
      MANTEL_DB_USER: mantel
      MANTEL_DB_PASSWORD: ${DB_PASSWORD}
      MANTEL_COOKIE_SECRET: ${COOKIE_SECRET}
      MANTEL_WORKER_TOKEN: ${WORKER_TOKEN}
      MANTEL_S3_ENDPOINT: https://<account>.r2.cloudflarestorage.com
      MANTEL_S3_BUCKET: mantel
      MANTEL_S3_ACCESS_KEY_ID: ${R2_KEY_ID}
      MANTEL_S3_SECRET_ACCESS_KEY: ${R2_SECRET}
      MANTEL_SMTP_HOST: smtp.example.com
      MANTEL_SMTP_FROM: albums@example.com
    ports: ["8080:8080"]
    depends_on: [postgres]

  worker:
    image: ghcr.io/alternayte/mantel:latest   # or a version tag, which is what a deployment should pin
    command: ["--worker"]
    environment:
      MANTEL_PUBLIC_BASE_URL: http://app:8080
      MANTEL_WORKER_TOKEN: ${WORKER_TOKEN}
      MANTEL_S3_ENDPOINT: https://<account>.r2.cloudflarestorage.com
      MANTEL_S3_BUCKET: mantel
      MANTEL_S3_ACCESS_KEY_ID: ${R2_KEY_ID}
      MANTEL_S3_SECRET_ACCESS_KEY: ${R2_SECRET}
    depends_on: [app]

  postgres:
    image: postgres:17-alpine
    environment:
      POSTGRES_USER: mantel
      POSTGRES_PASSWORD: ${DB_PASSWORD}
      POSTGRES_DB: mantel
    volumes: [postgres-data:/var/lib/postgresql/data]

volumes:
  postgres-data:
```

Put a reverse proxy in front for TLS. `MANTEL_PUBLIC_BASE_URL` must be the address people actually
use: share links and the OG tags are built from it.

## Three things worth getting right

**`MANTEL_COOKIE_SECRET` must be set and must be stable.** Unset, a new one is generated each start,
and viewers of PIN'd albums have to unlock again after every restart.

**`MANTEL_WORKER_TOKEN` must be set,** or the worker cannot claim anything and no photograph is ever
rendered. The worker holds no database credentials; this token is how it talks to the API.

**The worker needs the same storage credentials as the app,** because it reads originals and writes
derivatives directly. It does not need, and should not be given, database access.

## Coolify

Coolify can run this from the repository: point it at a Docker Compose resource using
`docker-compose.yml`, and set the environment in Coolify rather than in the file. The variables that
must be set are `MANTEL_PUBLIC_BASE_URL`, `MANTEL_COOKIE_SECRET`, `MANTEL_WORKER_TOKEN` and the four
`MANTEL_S3_*` values; everything else has a working default
([configuration.md](configuration.md)).

Two things to change from the development compose file:

- **Drop the `minio` and `minio-bucket` services** and point `MANTEL_S3_*` at R2 or another bucket.
  MinIO in the compose file is there so presigned upload is exercised in development.
- **Set `MANTEL_S3_PUBLIC_ENDPOINT`** if the browser reaches storage at a different address than the
  server does. With R2 they are the same and it stays unset.

The worker is the same image with `--worker`, so it is a second service in the same resource. It
needs the storage credentials and the worker token, and it must not be given database credentials.

## Storage

Any S3-compatible bucket. R2 is the default because egress is free and media is almost all egress.

Two things the app asks of the bucket at startup, both best effort:

- **expire incomplete multipart uploads after a day.** An abandoned large upload bills for parts no
  listing shows. If your provider refuses the rule, set it by hand — see
  [operations/storage.md](operations/storage.md).
- **allow anonymous reads of `public/`.** Link previews are fetched by crawlers with no credentials,
  so cover thumbnails for links without a PIN live there. Refused, you lose previews, not albums.

Everything else in the bucket is private and served through signed URLs.

## The demo album

The README links to a permanent public album. Seed it once the instance is up:

```
# on the machine with the repository, pointing at the deployment
bun run scripts/seed-demo.ts --base https://albums.example.com --token mantel_...
```

Make the token at `/app/settings` with `albums:read`, `albums:write` and `share:write`. The script
creates the album, uploads the fourteen CC0 fixtures from `site/references/album`, waits for the
worker, sets the captions and the cover, and creates one link with no PIN and no expiry. It prints
the URL and the line to paste into the README.

It is idempotent. Run it again after a deployment and it reports what is already there rather than
making a second album; run it after restoring a backup to check the demo is still whole. `--replace`
re-uploads the photographs, and `--local` signs in through the compose stack's log instead of a
token.

## Backups

Two things to back up:

1. **PostgreSQL.** Ordinary `pg_dump`. It holds accounts, albums, items and share links.
2. **The bucket.** It holds the photographs, which are the part that cannot be regenerated.

A database restored without its bucket shows albums whose items never became ready. A bucket
restored without its database is a folder of files nobody can reach: the reconciliation job will
delete those objects a day later, so restore the database first.

## Upgrading

Images are published to `ghcr.io/alternayte/mantel` on every tag: `0.3.0`, `0.3` and `latest`. Pin a
version in a deployment; `latest` is for trying it.

Pull the image and restart. Migrations run at startup, and a migration the app cannot parse stops it
rather than being skipped. Roll back by deploying the previous image only if the newer one added no
migration; there is no down migration.

## What it costs to run

Around 250 MB of memory for the app and the same for a worker, plus the image, which is about
840 MB because it carries ffmpeg and libvips. Storage and egress depend on your photographs.
