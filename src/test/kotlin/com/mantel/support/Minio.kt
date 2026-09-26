package com.mantel.support

import org.testcontainers.utility.DockerImageName

/**
 * The MinIO image the tests run against, and the one `docker-compose.yml` names.
 *
 * MinIO stopped publishing its own images in 2026: `quay.io/minio/minio` and `minio/minio` both
 * answer "unauthorized" to an anonymous pull, and a machine that pulled one before hides the
 * fact until CI tries a fresh one. Chainguard builds MinIO from source and publishes it for free.
 *
 * It is `latest` because that is the only tag Chainguard publishes without a subscription. The
 * tests use the S3 API, which MinIO keeps stable across releases, so a moving tag is the lesser
 * risk than an image that cannot be pulled at all.
 */
val MINIO_IMAGE: DockerImageName =
    DockerImageName.parse("cgr.dev/chainguard/minio:latest").asCompatibleSubstituteFor("minio/minio")
