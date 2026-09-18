---
title: Self-hosting
description: One app container, a worker, PostgreSQL and a bucket.
---

Self-hosting is a first-class path, not a courtesy (`SDD.md` §11). The product is one app container
and PostgreSQL; object storage is anything S3-compatible.

## The smallest real deployment

```yaml
services:
  app:
    image: ghcr.io/alternayte/mantel:latest
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
    image: ghcr.io/alternayte/mantel:latest
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

## Storage

Any S3-compatible bucket. R2 is the default because egress is free and media is almost all egress.

Two things the app asks of the bucket at startup, both best effort:

- **expire incomplete multipart uploads after a day.** An abandoned large upload bills for parts no
  listing shows. If your provider refuses the rule, set it by hand — see
  [operations/storage.md](operations/storage/).
- **allow anonymous reads of `public/`.** Link previews are fetched by crawlers with no credentials,
  so cover thumbnails for links without a PIN live there. Refused, you lose previews, not albums.

Everything else in the bucket is private and served through signed URLs.

## Backups

Two things to back up:

1. **PostgreSQL.** Ordinary `pg_dump`. It holds accounts, albums, items and share links.
2. **The bucket.** It holds the photographs, which are the part that cannot be regenerated.

A database restored without its bucket shows albums whose items never became ready. A bucket
restored without its database is a folder of files nobody can reach: the reconciliation job will
delete those objects a day later, so restore the database first.

## Upgrading

Pull the image and restart. Migrations run at startup, and a migration the app cannot parse stops it
rather than being skipped. Roll back by deploying the previous image only if the newer one added no
migration; there is no down migration.

## What it costs to run

Around 250 MB of memory for the app and the same for a worker, plus the image, which is about
840 MB because it carries ffmpeg and libvips. Storage and egress depend on your photographs.
