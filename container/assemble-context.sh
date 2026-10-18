#!/usr/bin/env bash
# Assemble the container build context for the itonami agent image (JUN-77).
# Stages app + cli + kbb engine repos at their sibling layout so the scripts'
# relative references (../../kotoba-lang/*) keep resolving.
# Usage: container/assemble-context.sh [context-dir]
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GH=${GH:-$HOME/github}
OUT=${1:-$HERE/context}

mkdir -p "$OUT/cloud-itonami" "$OUT/kotoba-lang"
sync_repo() { # src dst
  rsync -a --delete \
    --exclude .git --exclude node_modules --exclude target --exclude data \
    --exclude '.nbb' --exclude '.cpcache' "$1/" "$2/"
}

sync_repo "$GH/cloud-itonami/cloud-itonami-app"   "$OUT/cloud-itonami/cloud-itonami-app"
sync_repo "$GH/cloud-itonami/cloud-itonami-cli"   "$OUT/cloud-itonami/cloud-itonami-cli"
sync_repo "$GH/kotoba-lang/kotoba"                 "$OUT/kotoba-lang/kotoba"
sync_repo "$GH/kotoba-lang/org-babashka-nbb"       "$OUT/kotoba-lang/org-babashka-nbb"

echo "context assembled: $OUT"
du -sh "$OUT"
