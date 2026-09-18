# site

The marketing page and the documentation, as a static site. Zero containers, not in
`docker-compose.yml`, not part of the product (`SDD.md` §8).

```
cd site && bun install && bun run dev
```

`just site` builds and publishes it. It is live at **https://mantel.nate-andert.workers.dev** — a
free subdomain, so this needs no domain of its own yet. When there is one, point the apex at this
and the app keeps `/app` and `/a/{token}`.

Cloudflare Pages is now part of Workers, so `wrangler deploy` with an `assets` directory is the
deployment; `wrangler.jsonc` holds the two lines that describes.

The pages under `src/content/docs/` are thin wrappers that carry the same words as `docs/` in the
repository root, so there is one source for each document rather than two that drift.

`references/` is not part of the site. It is the gauntlet's reference set (`BUILD.md` §5) and its
fixture album, kept here because that is where `BUILD.md` says it lives.
