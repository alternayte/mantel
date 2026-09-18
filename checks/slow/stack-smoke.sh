#!/usr/bin/env bash
# check: stack-smoke
# born: 2026-09-18
# failure: the fat jar dropped Flyway's service files, so the packaged app applied no migrations and
#          failed on a missing column. Every unit test passed, because tests run from the classpath.
# rule: the built image, run by docker compose, signs a creator in, takes an upload and renders it
# limit: this is one photo on one machine. It proves packaging and wiring, not behaviour under load.
set -euo pipefail

base="${MANTEL_BASE_URL:-http://localhost:8080}"
jar="$(mktemp)"
photo="$(mktemp -t photo.XXXXXX).jpg"
trap 'rm -f "$jar" "$photo"' EXIT

# A real JPEG, made by the same libvips the product uses.
docker compose exec -T app sh -c 'vips black /tmp/smoke.png 1200 800 && vips copy /tmp/smoke.png /tmp/smoke.jpg' > /dev/null
docker compose exec -T app cat /tmp/smoke.jpg > "$photo"
size="$(wc -c < "$photo" | tr -d ' ')"
[ "$size" -gt 0 ] || { echo "stack-smoke: could not make a test photo" >&2; exit 1; }

curl -fsS -X POST -H 'content-type: application/json' \
  -d '{"email":"smoke@example.com"}' "$base/api/auth/magic-link" > /dev/null

link="$(docker compose logs app | grep -o "$base/api/auth/magic-link/callback?token=[A-Za-z0-9]*" | tail -1)"
[ -n "$link" ] || { echo "stack-smoke: no magic link in the app log" >&2; exit 1; }
curl -fsS -o /dev/null -c "$jar" "$link"

album="$(curl -fsS -b "$jar" -X POST -H 'content-type: application/json' \
  -d '{"title":"Smoke"}' "$base/api/albums" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"

intent="$(curl -fsS -b "$jar" -X POST -H 'content-type: application/json' \
  -d "{\"files\":[{\"filename\":\"smoke.jpg\",\"contentType\":\"image/jpeg\",\"sizeBytes\":$size}]}" \
  "$base/api/albums/$album/upload-intent")"
url="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["items"][0]["uploadUrl"])' <<< "$intent")"
item="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["items"][0]["itemId"])' <<< "$intent")"

# The bytes go straight to storage, at the address a browser would use.
curl -fsS -o /dev/null -X PUT -H 'content-type: image/jpeg' --data-binary "@$photo" "$url"
curl -fsS -b "$jar" -X POST -H 'content-type: application/json' \
  -d "{\"itemIds\":[\"$item\"]}" "$base/api/albums/$album/uploads/complete" > /dev/null

for _ in $(seq 1 60); do
  status="$(curl -fsS -b "$jar" "$base/api/albums/$album/status" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["items"][0]["status"])')"
  [ "$status" = "ready" ] && { echo "stack-smoke: rendered"; exit 0; }
  [ "$status" = "failed" ] && break
  sleep 3
done

echo "stack-smoke: the item never became ready (last status: ${status:-unknown})" >&2
curl -fsS -b "$jar" "$base/api/albums/$album/status" >&2 || true
exit 1
