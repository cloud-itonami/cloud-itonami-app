# source-scout run records (append-only)

## 2026-09-20 run: REFUSED — pre-run evidence collector ENOENT on stale .cljs name

- Pre-run printed REFUSED: collector invoked scripts/wiki_growth_evidence.cljs
  (old name; main renamed to .cljk 2026-09-15) → ENOENT → "no evidence was
  gathered this run". Fifth REFUSED flavor; obey, propose/land nothing, no
  catch-up run (SKILL.md REFUSED rule; SKILL.md itself is over the 100KB
  cap so this record is the authoritative note until it is slimmed).
- Post-mortem (read-only): detach to origin/main 0a04e70e succeeded; config
  tail healthy (last entries = company-crawl 2026-09-20 pair
  uk-companies-house-register-root + ke-brs-company-registrar, inside the
  :sources vector, closed properly); scripts/ carries the .cljk names. Tree
  healthy; refusal is harness-level (stale filename in the pre-run script).

## 2026-09-15 run: organoid + protein-folding (PR #994)

- Landed nsf-awards-organoid (deferred pool's LAST member, re-probed green
  200/182880B/25 records/fields 25/25) + nsf-awards-protein-folding (fresh
  keyword; 200/116366B, same envelope). Gate 2/2 REJECTED 0 exit 0 on pristine
  cc99872d; evidence 2925→2927 exit 0; PR #994 @ 19641c91,
  bot/source-scout-20260915-1040, ls-remote==HEAD, gh pr view OPEN + body
  needles. Deferred pool after: EMPTY; fusion%20energy probed green same
  envelope and left deferred — new pick for next run.
- Run banner script died SIGTERM (exit -15) BEFORE the measurement; the
  re-parent was re-run fresh this run and verified clean, so the run proceeded
  (unlike a REFUSED measurement, which orders stop).
- MAIN RENAMED .cljs → .cljk: scripts/verify_source_proposal.cljs no longer
  exists; it is scripts/verify_source_proposal.cljk, and
  wiki_growth_evidence is .cljk too (registry.cljk likewise). The old
  .cljs filename gives ENOENT (wasted gate call). All nbb entry points need
  the kotoba-lang/text classpath still:
  `--classpath src:~/.gitlibs/libs/io.github.kotoba-lang/text/<deps.edn sha>/src`.
- Splice arithmetic: this main's tail is
  `...:llm? true}\n\n]\n\n}\n` — 2 added entries must keep a close-delta of
  exactly +2; the naive append that drops the consumed last-entry close gave
  +1 on `}` (assert caught pre-write, nothing written). Re-emit the consumed
  close line, then append both entries before `\n]\n\n}\n`.
- Evidence line format changed: '## configured sources' section is now lines
  13..2938 of wiki_growth_evidence output (2925 sources on cc99872d); count
  with a python slice, and the `SCANNED\t420` trailer is not the count.
- Evidence count arithmetic: the naive branch-push script still worked, but
  `ls-remote=$(git ls-remote ...)` in a bash script WITHOUT quotes-in-command
  ran as a command (`ls-remote=` not found) — verify ls-remote==HEAD with a
  plain `git ls-remote origin refs/heads/<branch>` line instead.
- stdout rot persists: every terminal call returns exit 0 with EMPTY stdout in
  cron sessions; all scripts must self-log to a /tmp file that is read back
  with read_file (and /tmp script names must not shadow stdlib).