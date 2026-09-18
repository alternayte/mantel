---
title: Mantel
description: Photo and video albums shared as a link.
---

Albums of photographs, shared as a link. The recipient opens it and sees the photographs:
no account, no app install, no cookie banner, no tracking.

- **The viewer is the product.** Photographs at their own proportions on near-black, with no
  interface until you ask for one.
- **An album downloads as a folder that works offline, forever** — the photographs and one page
  that shows them, with nothing to fetch.
- **Open source and self-hostable.** One app container, a worker, PostgreSQL and any S3-compatible
  bucket. AGPL-3.0.
- **Operable by an agent end to end**, through the same API everything else uses.

```
git clone https://github.com/alternayte/mantel && cd mantel
docker compose up --build
```
