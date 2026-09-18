#!/usr/bin/env bash
# check: openapi
# born: 2026-09-18
# failure: a route was added and the OpenAPI document did not hear about it, so an agent reading the
#          document builds against an API that has moved
# rule: every public /api path the server registers appears in the OpenAPI document
# limit: it compares paths, not shapes. A changed response body still needs a human.
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"

# The routing table as the route-inventory test prints it, kept in one place.
routes="$(grep -oE '"(GET|POST|PATCH|DELETE) /api/[^"]*"' \
    src/test/kotlin/com/mantel/features/media/UploadIntentTest.kt |
    tr -d '"' | sort -u)"

document="$(grep -oE '"/api/[^"]*": \{' src/main/kotlin/com/mantel/features/agent/AgentDocs.kt |
    sed 's/": {//; s/"//' | sort -u)"

missing=""
while read -r method path; do
    [ -n "$path" ] || continue
    # Worker endpoints are not a public interface: they exist between our own two processes.
    case "$path" in
        /api/worker/*) continue ;;
        # These are viewer routes an agent does not drive, and they are in docs/api.md.
        /api/auth/*|/api/health|/api/share/*/unlock|/api/albums/*/status|/api/albums/*/upload-progress) continue ;;
        /api/albums/*/items/*/retry) continue ;;
        /api/account*) continue ;;
    esac
    grep -qxF "$path" <<< "$document" || missing="$missing$method $path\n"
done <<< "$routes"

if [ -n "$missing" ]; then
    printf "rule: openapi: these routes are not in the OpenAPI document:\n%b" "$missing" >&2
    exit 1
fi
