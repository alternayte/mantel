# Getting started

Two commands, if Docker is installed.

```
git clone https://github.com/alternayte/mantel && cd mantel
docker compose up --build
```

That gives you the app on :8080, a worker, PostgreSQL and MinIO standing in for object storage.
Open http://localhost:8080/app.

## Signing in

There is no password. Enter an email address and the app writes a sign-in link; with no SMTP host
configured it goes to the log rather than to an inbox:

```
docker compose logs app | grep magic-link
```

Open the link. That is the whole sign-in.

## Making an album

1. Name it and press Create.
2. Drop photographs on the page, or press Add photos. They upload straight to storage; the bytes
   never pass through the app.
3. Watch them render. Each item carries its own state, so a slow one reads differently from a
   failed one.
4. Press **Publish and get a link**. That link is the product.

Open the link in another browser, or a private window, to see what a recipient sees: photographs on
near-black with nothing else on the screen, and no cookie of any kind.

## Working on it

`just dev` runs the app from source with a reload, and the web client on :5173 proxying `/api`:

```
just dev        # postgres, minio, the API on :8080, Vite on :5173
just worker     # the same binary in worker mode
just check      # the gate: checks, web build, ktlint, tests
just stack      # build the image and drive an upload through the containers
```

`just check` needs Docker running: the database tests use Testcontainers, and the photo tests shell
out to libvips. `brew install vips` on macOS, `libvips-tools` with `libheif-plugin-aomenc` on
Debian or Ubuntu.

## Where to go next

- [configuration.md](configuration.md) — every value the app reads
- [self-hosting.md](self-hosting.md) — running it for real
- [api.md](api.md) — the API, which is the same one the web client uses
- [agents.md](agents.md) — tokens, `llms.txt`, MCP
