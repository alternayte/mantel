# check: icons
# born: 2026-09-27
# failure: an icon SVG or the icon stroke token changed and Icons.kt was not regenerated, so the app drew icons the design no longer describes
# rule: `just icons` produces no change to the file it generates
# limit: it proves Icons.kt matches design/icons/lucide/ and design/tokens.json, not that an icon is used anywhere.
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"

generated=android/src/androidMain/kotlin/com/mantel/app/design/Icons.kt

bun run design/icons/icons.ts > /dev/null

if ! git diff --quiet -- "$generated"; then
    echo "rule: icons: design/icons/lucide/ and $generated disagree. Run just icons and commit:" >&2
    git --no-pager diff --stat -- "$generated" >&2
    exit 1
fi
