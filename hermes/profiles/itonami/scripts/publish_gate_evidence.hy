#!/usr/bin/env hy
"Evidence gathering for the itonami publish-gate tick.

Runs from THIS profile's scripts dir (itonami) so the script-path guard accepts it
(the old job referenced ../itonami-publish-gate/scripts/... which the guard blocks).

Producer interface (per transport-efficiency/workspace/AGENTS.md): the bot's
deliverable is one findings/<YYYYMMDD>-<slug>.edn per tick, verified by
`nbb verify-findings.cljs findings` — NOT workspace/proposals.jsonl.
"
(import os)
(import glob)
(import datetime [datetime])
(setv PROFILE "~/.hermes/profiles")
(setv transport_ws f"{PROFILE}/transport-efficiency/workspace")
(setv findings_dir f"{transport_ws}/findings")
(setv legacy_ledger f"{transport_ws}/proposals.jsonl")
(print "=== TRANSPORT-EFFICIENCY FINDINGS (publish-gate corpus) ===")
(if ((. os.path isdir) findings_dir)
  (do
    (setv files (sorted (glob.glob f"{findings_dir}/*.edn")))
    (print f"MEASURE\tfinding_files\t{(len files)}")
    (for [f files]
      (try
       (with [fh (open f)]
         (setv text (fh.read)))
       (setv status "unmeasured")
       (for [token #(":measured" ":not-measured" ":coverage-gap")]
         (when (in token text) (setv status (token.strip ":")) (break)))
       (setv fid "?")
       (when (in ":finding/id" text)
         (setv fid
           (get ((. (get (text.split ":finding/id" 1) 1) split) "\"" 2)
             1)))
       (print f"MEASURE\tfinding\t{((. os.path basename) f)}\tstatus={status}\tid={fid}")
       (except [e Exception]
         (print
           f"MEASURE\tfinding_read_error\t{((. os.path basename) f)}\t{(cut (str e) 80)}")))))
  (do (print "MEASURE\tfinding_files\t0") (print f"MEASURE\tfindings_dir_status\tnot found: {findings_dir}")))
(print "=== LEGACY proposals.jsonl (interface the old gate script expected) ===")
(if ((. os.path exists) legacy_ledger)
  (do
    (with [f (open legacy_ledger)]
      (setv n (sum (gfor line f :if (line.strip) 1))))
    (print f"MEASURE\tlegacy_proposals\t{n}"))
  (do (print "MEASURE\tlegacy_proposals\t0") (print "MEASURE\tlegacy_ledger_status\tnot found (producer writes findings/*.edn, not this file)")))
(print f"MEASURE\tcollected_at\t{((. (datetime.now) isoformat))}")