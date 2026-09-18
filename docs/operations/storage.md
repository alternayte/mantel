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

A job walks storage per account and corrects `storage_used_bytes`, catching abandoned uploads and
drift. Orphaned objects with no `media_item` row are deleted after a grace period. That job arrives
at M10.
