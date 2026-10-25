#!/usr/bin/env python3
"""Evidence gathering for the itonami publish-gate tick.

Runs from THIS profile's scripts dir (itonami) so the script-path guard accepts it
(the old job referenced ../itonami-publish-gate/scripts/... which the guard blocks).

Producer interface (per transport-efficiency/workspace/AGENTS.md): the bot's
deliverable is one findings/<YYYYMMDD>-<slug>.edn per tick, verified by
`nbb verify-findings.cljs findings` — NOT workspace/proposals.jsonl.
"""
import os
import glob
from datetime import datetime

PROFILE = "~/.hermes/profiles"
transport_ws = f"{PROFILE}/transport-efficiency/workspace"
findings_dir = f"{transport_ws}/findings"
legacy_ledger = f"{transport_ws}/proposals.jsonl"

print("=== TRANSPORT-EFFICIENCY FINDINGS (publish-gate corpus) ===")
if os.path.isdir(findings_dir):
    files = sorted(glob.glob(f"{findings_dir}/*.edn"))
    print(f"MEASURE\tfinding_files\t{len(files)}")
    for f in files:
        try:
            with open(f) as fh:
                text = fh.read()
            status = "unmeasured"
            for token in (":measured", ":not-measured", ":coverage-gap"):
                if token in text:
                    status = token.strip(":")
                    break
            fid = "?"
            if ":finding/id" in text:
                fid = text.split(":finding/id", 1)[1].split('"', 2)[1]
            print(f"MEASURE\tfinding\t{os.path.basename(f)}\tstatus={status}\tid={fid}")
        except Exception as e:
            print(f"MEASURE\tfinding_read_error\t{os.path.basename(f)}\t{str(e)[:80]}")
else:
    print("MEASURE\tfinding_files\t0")
    print(f"MEASURE\tfindings_dir_status\tnot found: {findings_dir}")

print("=== LEGACY proposals.jsonl (interface the old gate script expected) ===")
if os.path.exists(legacy_ledger):
    with open(legacy_ledger) as f:
        n = sum(1 for line in f if line.strip())
    print(f"MEASURE\tlegacy_proposals\t{n}")
else:
    print("MEASURE\tlegacy_proposals\t0")
    print("MEASURE\tlegacy_ledger_status\tnot found (producer writes findings/*.edn, not this file)")

print(f"MEASURE\tcollected_at\t{datetime.now().isoformat()}")
