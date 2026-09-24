#!/bin/bash
# itonami-maint measurement source: 7-axis maturity of cloud-itonami-app.
# Outputs measured facts ONLY (no interpretation). Hermes cron monitor gates on it.
set -euo pipefail
APP=~/github/com-junkawasaki/orgs/cloud-itonami/cloud-itonami-app

cd "$APP"

echo "== git =="
echo "dirty_files: $(git status --porcelain | wc -l | tr -d ' ')"
echo "head: $(git log --oneline -1)"
echo "branch: $(git branch --show-current)"
echo "adr_count: $(ls 90-docs/adr/ | wc -l | tr -d ' ')"

echo "== tests =="
# bounded: clj-kondo style syntax presence + test files count only (full suite too heavy per tick)
echo "test_files: $(find test -name '*_test.clj' | wc -l | tr -d ' ')"
echo "src_files: $(find src -name '*.clj' -o -name '*.cljc' -o -name '*.kotoba' | wc -l | tr -d ' ')"

echo "== ledger =="
SIM=90-docs/sim-loop
if [ -f "$SIM/status/maturity.md" ]; then
  grep -E '^\| ' "$SIM/status/maturity.md" | head -10 || true
  grep -A3 'OPEN' "$SIM/status/maturity.md" | head -8 || true
else
  echo "maturity.md: absent"
fi
echo "evidence_count: $(ls "$SIM/evidence" 2>/dev/null | wc -l | tr -d ' ')"

echo "== durability probes =="
curl -s -o /dev/null -w 'server_health:%{http_code}\n' --max-time 5 http://127.0.0.1:1338/health || echo 'server_health:000'
JOURNAL="$HOME/.cloud-itonami/data/state.journal.edn"
if [ -f "$JOURNAL" ]; then
  SIZE=$(stat -f%z "$JOURNAL")
  echo "journal_bytes:$SIZE bound:4194304 pct:$((SIZE * 100 / 4194304))"
fi
