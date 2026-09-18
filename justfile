set dotenv-load := true

# recipe: check
# Run every check in checks/. Add stack gates (build, vet, typecheck, tests) below the loop.
check:
    #!/usr/bin/env bash
    set -uo pipefail
    fail=0
    for c in checks/*.sh; do
        [ -e "$c" ] || continue
        if ! bash "$c"; then
            echo "FAIL $c" >&2
            fail=1
        fi
    done
    cd web && bun install && bun run build && bun test || fail=1
    cd ..
    bash checks/after-build/bundle.sh || fail=1
    ./gradlew --console=plain ktlintCheck build || fail=1
    exit "$fail"

# recipe: agents
# Sync global agent files and skills, then verify this repo's agent files.
agents:
    bash ~/dotfiles/agents/sync.sh
    bash checks/agents-md.sh
    bash checks/check-headers.sh

# recipe: check-slow
# The checks too slow for every `just check`. Runs the commands quoted in agent files, then builds the image and drives a real upload through it. Needs Docker.
check-slow:
    #!/usr/bin/env bash
    set -euo pipefail
    bash checks/agents-md.sh --run
    docker compose up -d --build --wait
    bash checks/slow/stack-smoke.sh

# recipe: review
# Review the branch diff against the default branch on two axes, each in a fresh read-only pi session.
# Axis 1, correctness: sees the diff and the repo. Runs on a model family that did not write the branch. REVIEW_MODEL overrides the model.
# Axis 2, spec compliance: sees docs/specs/<slug>.md and the diff, nothing else. <slug> is the branch name after its last /,
# or the one spec file the branch adds or changes. No spec: axis 2 says so in one line and no model runs.
review base="": check-slow
    #!/usr/bin/env bash
    set -euo pipefail
    base="{{base}}"
    if [ -z "$base" ]; then
        base="$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null || echo main)"
    fi
    mb="$(git merge-base "$base" HEAD)"
    tmp="$(mktemp -d -t review.XXXXXX)"
    trap 'rm -rf "$tmp"' EXIT
    git diff "$mb" > "$tmp/branch.diff"
    [ -s "$tmp/branch.diff" ] || { echo "no diff against $base"; exit 0; }

    family() {
        case "$(tr '[:upper:]' '[:lower:]' <<< "$1")" in
            *anthropic*|*claude*) echo anthropic ;;
            *openai*|*gpt*) echo openai ;;
            *deepseek*|ds/*) echo deepseek ;;
            *qwen*) echo qwen ;;
            *gemini*|*google*) echo google ;;
            *) echo "review: unknown model family: $1. Add it to family() in the review recipe." >&2; return 1 ;;
        esac
    }
    model="${REVIEW_MODEL:-or/openai/gpt-5.6-sol}"
    rf="$(family "$model")"
    # Builder families: commit authors and trailers on the branch, and the local pi default model when one is set.
    builders="$(git log "$mb"..HEAD --format='%an %ae %B' | grep -Eio 'claude|anthropic|gpt|openai|deepseek|qwen|gemini' | sort -u || true)"
    pidefault="$(jq -r '(.defaultProvider // "") + "/" + (.defaultModel // "")' "$HOME/.pi/agent/settings.json" 2>/dev/null || true)"
    for b in $builders "${pidefault:-}"; do
        [ -n "$b" ] && [ "$b" != "/" ] || continue
        bf="$(family "$b" 2>/dev/null || true)"
        [ "$bf" != "$rf" ] || { echo "review: $model is family $rf, which wrote this branch ($b). Set REVIEW_MODEL to another family." >&2; exit 1; }
    done

    branch="$(git rev-parse --abbrev-ref HEAD)"
    slug="${branch##*/}"
    spec=""
    if [ -f "docs/specs/$slug.md" ]; then
        spec="docs/specs/$slug.md"
    else
        touched="$(git diff --name-only --diff-filter=AM "$mb" -- 'docs/specs/*.md')"
        [ "$(grep -c . <<< "$touched" || true)" = 1 ] && { spec="$touched"; slug="$(basename "$spec" .md)"; }
    fi

    ro=(--no-session --no-extensions --no-skills --no-prompt-templates --tools read,grep,find,ls --model "$model")
    status=0

    echo "== Axis 1: correctness ($model)"
    pi "${ro[@]}" -p @"$tmp/branch.diff" "Review this diff for correctness bugs only. Read AGENTS.md and the touched files for context. You are read-only. A finding is a test: for each bug give the path tests/review/$slug/<name> in the repo's test framework, the full test file, the failing input, and the wrong result the test shows on HEAD. The test must fail on HEAD. A bug you cannot express as a test that fails on HEAD goes under a 'Nits' heading. No style notes. No praise. If there are no bugs, say so in one line." || status=1

    echo
    echo "== Axis 2: spec compliance"
    if [ -z "$spec" ]; then
        echo "No spec for branch $branch: docs/specs/$slug.md does not exist and the branch changes no single spec. Axis 2 stops."
    else
        mkdir "$tmp/axis2"
        cp "$spec" "$tmp/axis2/spec.md"
        git diff "$mb" -- . ':(exclude)docs/specs' ':(exclude)tests/review' > "$tmp/axis2/branch.diff"
        echo "spec: $spec"
        (cd "$tmp/axis2" && pi "${ro[@]}" --no-context-files -p @spec.md @branch.diff "You check spec compliance. You have exactly two inputs: spec.md and branch.diff. Read nothing else. For each requirement in spec.md, in spec order, output one line: the requirement, then met, not met, or built differently. For built differently, state what the spec asks for and what the code does. Do not judge which is better. Then a heading 'Unrequested changes': list each change in the diff outside the spec's scope, as file and one line, without judgement. Do not judge code quality or correctness.") || status=1
    fi
    exit "$status"

# recipe: dev
# Start postgres and minio, then the API on :8080 and Vite on :5173 proxying /api.
dev:
    #!/usr/bin/env bash
    set -euo pipefail
    # A clean clone has no .env, and the worker token has no default on purpose.
    [ -f .env ] || { cp .env.example .env; echo "wrote .env from .env.example"; }
    docker compose up -d --wait postgres minio
    export MANTEL_DEV_ASSETS_ORIGIN="http://localhost:5173"
    trap 'kill 0' EXIT
    ./gradlew --quiet --console=plain run &
    (cd web && bun install && bun run dev) &
    wait

# recipe: worker
# Run the same binary in worker mode against local compose.
worker:
    #!/usr/bin/env bash
    set -euo pipefail
    [ -f .env ] || { cp .env.example .env; echo "wrote .env from .env.example"; }
    ./gradlew --quiet --console=plain run --args="--worker"

# recipe: migrate
# Apply the migrations in src/main/resources/db/migration.
migrate:
    #!/usr/bin/env bash
    set -euo pipefail
    docker compose up -d --wait postgres
    ./gradlew --quiet --console=plain run --args="--migrate"

# recipe: seed
# Write a demo account and album for local work.
seed:
    @echo "seed: nothing to write. The account arrives in M1 and the album in M2 (BUILD.md section 4)." >&2
    @exit 1

# recipe: test
# Tests only. Needs Docker: the database tests use Testcontainers.
test:
    ./gradlew --console=plain test

# recipe: tokens
# Generate web/src/styles/tokens.css from design/tokens.json. The JSON is the source.
tokens:
    bun run scripts/tokens.ts

# recipe: fmt
# Format Kotlin.
fmt:
    ./gradlew --console=plain ktlintFormat

# recipe: build
# Build the production image. The Dockerfile builds the SPA and the jar, so a clean clone needs only Docker.
build:
    docker build -t mantel:dev .

# recipe: stack
# Build the image and run the whole product in containers: app, worker, postgres, minio.
stack:
    #!/usr/bin/env bash
    set -euo pipefail
    docker compose up -d --build --wait
    bash checks/slow/stack-smoke.sh

# recipe: model
# Set the pi default provider. The model is the first model of that provider in models.json.
model name:
    #!/usr/bin/env bash
    set -euo pipefail
    models="$HOME/.pi/agent/models.json"
    settings="$HOME/.pi/agent/settings.json"
    id="$(jq -er --arg p "{{name}}" '.providers[$p].models[0].id' "$models")" || {
        echo "model: no provider '{{name}}' in $models. Valid: $(jq -r '.providers | keys | join(", ")' "$models")" >&2
        exit 1
    }
    tmp="$(mktemp)"
    jq --arg p "{{name}}" --arg m "$id" '.defaultProvider = $p | .defaultModel = $m' "$settings" > "$tmp"
    mv "$tmp" "$settings"
    echo "pi default: {{name}}/$id"
