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
# kbb stdlib siblings (measured 2026-10-18: absent siblings print
# "NOT on the classpath: stdlib sibling not checked out" warnings at runtime)
for lib in process bytes json http pprint error coll fs io spec test; do
  if [ -d "$GH/kotoba-lang/$lib" ]; then
    sync_repo "$GH/kotoba-lang/$lib" "$OUT/kotoba-lang/$lib"
  fi
done

echo "context assembled: $OUT"
du -sh "$OUT"
