#!/usr/bin/env bash
#
# Propagate the shared JVM parser engine (nyora-shared) to every Nyora variant.
#
# The variants consume the parser stack in three different ways, so "update the
# sources everywhere" means three different things — this script does the
# mechanical part (desktop) and tells you exactly what's needed for the rest:
#
#   • desktop (mac / linux / windows)  — submodule `nyora-shared`
#         → bump the submodule ref to the target commit (this script).
#         Local uncommitted WIP inside the submodule is preserved (stash/pop).
#
#   • android (nyora-android)          — native `kotatsu-parsers-redo` dep + its
#         own OkHttp/DI wiring. Shared Kotlin can't be dropped in verbatim, so
#         parser fixes live in-repo (e.g. core/network/LibApiHeadersInterceptor.kt).
#         → commit there directly; this script only reminds you.
#
#   • helper (nyora VM) / mihon / ios / nyora-web — hosted-helper REST clients.
#         They call api.hasanraza.tech, so they update automatically once the
#         helper jar is rebuilt from nyora-shared and redeployed.
#         → nyora-shared/scripts/restart-helper.sh (or the deploy flow).
#
# Usage:
#   scripts/propagate-to-variants.sh [nyora-shared-ref]   # default: origin/main
#
set -euo pipefail

REF="${1:-origin/main}"

# This repo is …/Nyora/nyora-shared; its siblings are the variant repos.
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$(cd "$SELF/.." && pwd)"

DESKTOP=(nyora-mac nyora-linux nyora-windows)

echo "▶ Propagating nyora-shared@$REF to desktop variants"
for repo in "${DESKTOP[@]}"; do
    parent="$ROOT/$repo"
    sub="$parent/nyora-shared"
    if [ ! -e "$sub/.git" ]; then
        echo "  ✗ $repo: no nyora-shared submodule — skipping"
        continue
    fi
    echo "── $repo"
    git -C "$sub" fetch --quiet origin

    # Preserve any uncommitted WIP living in the submodule working tree.
    stashed=0
    if [ -n "$(git -C "$sub" status --porcelain)" ]; then
        git -C "$sub" stash push --quiet --include-untracked -m "propagate-shared-wip"
        stashed=1
    fi

    git -C "$sub" checkout --quiet --detach "$REF"

    if [ "$stashed" -eq 1 ]; then
        if git -C "$sub" stash pop --quiet; then
            echo "  • preserved local submodule WIP"
        else
            echo "  ⚠ $repo: WIP re-apply conflicted — resolve in $sub, then re-run"
            continue
        fi
    fi

    target="$(git -C "$sub" rev-parse --short HEAD)"
    git -C "$parent" add nyora-shared
    if git -C "$parent" diff --cached --quiet -- nyora-shared; then
        echo "  = already at $target"
    else
        git -C "$parent" commit --quiet -m "chore: bump nyora-shared -> $target"
        echo "  ✓ bumped submodule ref to $target (commit the parent, then push to ship)"
    fi
done

echo
echo "▶ Not auto-propagated (by design):"
echo "  • android — commit the in-repo parser fix (core/network/*Interceptor.kt)"
echo "  • mihon / ios / nyora-web — hosted-helper clients; rebuild+redeploy the helper jar"
echo
echo "Done. Push each bumped desktop repo to release via CI."
