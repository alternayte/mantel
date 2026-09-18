# site

The marketing page and the documentation, as a static site. Zero containers, not in
`docker-compose.yml`, not part of the product (`SDD.md` §8).

```
cd site && bun install && bun run dev
```

`bun run build` writes `dist/`, which is what gets deployed — Cloudflare Pages by default. The apex
domain serves this; the app serves `/app` and `/a/{token}`.

The pages under `src/content/docs/` are thin wrappers that carry the same words as `docs/` in the
repository root, so there is one source for each document rather than two that drift.

`references/` is not part of the site. It is the gauntlet's reference set (`BUILD.md` §5) and its
fixture album, kept here because that is where `BUILD.md` says it lives.
