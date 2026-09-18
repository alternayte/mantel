#!/usr/bin/env bash
# check: tokens
# born: 2026-09-18
# failure: design/tokens.json changed, one generated theme was regenerated and the other was not, so the two clients drifted apart while both looked correct
# rule: `just tokens` produces no change to the files it generates
# limit: it proves the generated files match the JSON, not that a token is used anywhere.
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"

generated=(
    web/src/styles/tokens.css
    android/src/androidMain/kotlin/com/mantel/app/design/Tokens.kt
    android/src/androidMain/res/values/tokens.xml
)

bun run scripts/tokens.ts > /dev/null

if ! git diff --quiet -- "${generated[@]}"; then
    echo "rule: tokens: design/tokens.json and its generated files disagree. Run just tokens and commit:" >&2
    git --no-pager diff --stat -- "${generated[@]}" >&2
    exit 1
fi
