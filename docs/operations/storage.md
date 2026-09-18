# Storage

Mantel uses one S3-compatible bucket. MinIO in development, Cloudflare R2 by default in deployment.

## Incomplete multipart uploads

A large file uploads in parts. A client that abandons one leaves parts that no object listing shows
and no database row points at, and the provider bills for them.

Two things remove them:

1. **Deleting the item aborts its upload.** The API does this, so the normal path needs nothing.
2. **A lifecycle rule** catches the rest: an upload nobody finished and nobody deleted. The app asks
   the provider for this rule at startup:

   > abort incomplete multipart uploads 1 day after they start

If the provider refuses, the app logs a warning naming the error code and carries on. Set the rule
by hand in that case:

```
mc ilm rule add --expire-delete-marker --noncurrent-expire-days 1 local/mantel
aws s3api put-bucket-lifecycle-configuration --bucket mantel --lifecycle-configuration file://lifecycle.json
```

Known refusal: MinIO releases from early 2024 require a `Content-Md5` header that current AWS SDKs
no longer send, and answer `Missing required header for this request: Content-Md5`. Current MinIO
releases, R2 and S3 accept the rule.

## Reconciliation

The worker runs this at startup and every `MANTEL_RECONCILE_INTERVAL_HOURS` (6 by default). It does
two things, and neither can see the other's world, which is why it exists at all:

**Orphaned objects.** The worker lists the bucket and asks the API what it is looking at. An object
that no `media_item`, bundle or live share link points at, and that is **older than 24 hours**, is
deleted. Nothing younger is ever touched: an object written a minute ago may belong to a row that is
a second from being committed.

**Storage used.** `storage_used_bytes` is recomputed from the rows that own it. Drift comes from a
crash between reserving quota and writing the row; without this a creator pays for it for ever.

Both are idempotent and safe to run at any time. The worker logs only when it changes something:

```
reconciliation: deleting 3 orphaned objects under media/
reconciliation: corrected storage used for 1 accounts
```

If you restore a database backup **without** its bucket, restore the database first: a bucket whose
rows are missing looks exactly like a bucket full of orphans, and 24 hours later it is one.
