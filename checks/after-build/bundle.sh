#!/usr/bin/env bash
# check: bundle
# born: 2026-09-18
# failure: a recipient opening a photo link downloads the authoring application, or a dependency
#          lands in the viewer's chunk and nobody notices until a phone on 4G does
# rule: everything the viewer route loads is within the budget in SDD.md 7.3
# limit: this measures bytes, not speed. LCP is measured by hand in a browser, per the gauntlet.
# It lives in checks/after-build/ rather than checks/ because it reads the web build, and the
# checks/ loop runs before anything is built.
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"

budget_kb=90
dist="build/web-resources/web"
manifest="$dist/.vite/manifest.json"

[ -f "$manifest" ] || { echo "rule: bundle: no build. Run the web build first." >&2; exit 1; }

# Every chunk the viewer entry pulls in, including its shared chunks.
files="$(python3 - "$manifest" <<'PY'
import json, sys
manifest = json.load(open(sys.argv[1]))
seen, queue = set(), ["viewer.html"]
while queue:
    key = queue.pop()
    entry = manifest.get(key)
    if not entry or key in seen:
        continue
    seen.add(key)
    queue.extend(entry.get("imports", []))
    queue.extend(entry.get("dynamicImports", []))
print("\n".join(manifest[k]["file"] for k in seen if "file" in manifest[k] and manifest[k]["file"].endswith(".js")))
PY
)"

total=0
for file in $files; do
    size="$(gzip -c "$dist/$file" | wc -c | tr -d ' ')"
    total=$((total + size))
    printf "  %-40s %6s kB gz\n" "$file" "$(( (size + 512) / 1024 ))"
done

total_kb=$(( (total + 512) / 1024 ))
echo "  viewer route total: ${total_kb} kB gzipped (budget ${budget_kb} kB)"

if [ "$total_kb" -gt "$budget_kb" ]; then
    echo "rule: bundle: the viewer route is ${total_kb} kB gzipped, over the ${budget_kb} kB budget in SDD.md 7.3" >&2
    exit 1
fi

# The authoring application must not be reachable from the viewer entry.
if grep -q "app-" <<< "$files"; then
    echo "rule: bundle: the viewer entry pulls in a creator chunk" >&2
    exit 1
fi
