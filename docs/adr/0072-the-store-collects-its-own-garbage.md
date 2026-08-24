# ADR-0072: The store collects its own garbage

**Status:** accepted — 2026-08-24

## Context

On 2026-08-23 this machine's disk filled. The application did not cause it —
`~/.cloud-itonami` held under 1 GB on a 460 GB volume — but it could not
survive it either. The store rewrites the whole of `state.edn` through a
sibling tmp file and an atomic rename on every transaction, so the moment
free space fell under the file's own size, every turn died at its first
write. Measured from the SLO surface the next morning: thirteen resident
turns failed as `fs/io write failed` (the error message mostly lost),
three as `No space left on device`, and a 23:40 batch of sixteen resident
jobs launched into the full disk and stuck in `:running`, where the
stale-running gate found them nine hours later. The 24-hour completion rate
was 48.4% against a 90% target.

Two prior observations frame the fix:

- `host/store-max-bytes` (2026-08-20) already named the gap when the store
  outgrew its first write bound: *"670 runs are retained with full goals and
  never pruned … Retention is the actual gap; this only stops the ceiling
  from being the wrong one."*
- Measured 2026-08-24, `state.edn` was 40 MB. 9.2 MB was `[:bots :goal-jobs]`
  — 1,391 entries of which 1,372 were terminal (849 failed, 523 succeeded)
  and none had ever been dropped. 17.3 MB was `[:mail :messages]` — 2,385
  messages carrying full bodies that `mail-sync/upsert-messages!` had
  **already archived to disk** (every store record carries the
  `:archive-path` it wrote first). The hot store was two-thirds duplicate
  and tombstone.

There was also no admission control against the disk: `fire-due-workforce!`
checks provider capacity (`max-active`) but launched its batch regardless of
whether the machine could record a single turn of it.

## Decision

A new namespace, `cloud.itonami.app.gc`, owns three things. Decisions are
pure and portable (`.cljc`); every effect takes them as values.

**1. Retention.** A scheduled sweep (default every 6 h, first pass one
minute after start) applies three rules inside one store transaction:

- Terminal goal-jobs (`agent.run/terminal-statuses`) are **compacted** when
  they finished more than `:goal-job-compact-days` ago (default 2): the
  plan, the events, the goal text and the run's heavy fields go; the
  skeleton every remaining reader consults stays — `:job/bot`,
  `:job/resident-workforce?` (`bot_slo/resident-turn?` classifies a week of
  turns through it), the owner's `:user-id` (`resident-outcomes` filters by
  it), the status, error-type and timestamps (`run-outcome` and the restart
  sweeps), and `:agent.run/result` only when it is a keyword
  (`run-outcome` compares it to `:safe-no-op`; a string result is a
  transcript, and the transcript's home is the turn history). Measured
  2026-08-24: a terminal job averaged 6.6 KB, the skeleton ~0.3 KB.
- Terminal goal-jobs are **dropped** when they finished more than
  `:goal-job-retention-days` ago (default 14 — deliberately past the SLO's
  seven-day window, whose `resident-turn?` still consults the skeleton), or
  when they fall beyond the newest `:goal-jobs-keep-per-bot` (default 50)
  for their Bot. Active runs are never candidates, whatever their age. An
  undatable record is never an age candidate (the cap still bounds it).
- Mail bodies are evicted from the hot store — `:body` removed,
  `:body-evicted? true` added, snippet, metadata and `:archive-path` kept —
  when the message is older than `:mail-body-retention-days` (default 30)
  **and** its archive file demonstrably exists on disk at sweep time. Age
  is the MESSAGE's age (`:received-at`, falling back to `:synced-at`): the
  first live dry-run judged by sync age and evicted zero of 2,385 bodies,
  because the whole mailbox had been synced recently. Eviction is a cache
  decision, and a cache may only forget what something else still holds:
  the bytes remain in `mail-archive/` and at the provider.

The sweep also deletes `config.edn.bak-*` siblings beyond the newest
`:config-backups-keep` (default 5).

**2. The disk floor.** `pressure*` classifies a measurement of the store
filesystem into `:ok`, `:soft`, `:hard` — or `:unknown` when the probe
could not answer (`File/getUsableSpace` returns 0 for "unable to
determine"). The hard floor is the configured `:hard-floor-bytes` (default
512 MiB) or **four times the store file, whichever is larger**, because the
atomic rewrite needs the file's own size free just for its tmp sibling.
`fire-due-workforce!` consults this before admitting a resident batch and
skips with `{:reason :disk-pressure :usable-bytes … :hard-floor-bytes …}` —
a skip the SLO surface can show, where a sixteen-way `fs/io` crash was not.
`:unknown` does **not** refuse admission: a broken probe must not stop the
company for ever, but it is written into every receipt it touches, because
"could not measure" must never print the same thing as "measured and fine"
(ADR-2608136000's sixth question, inherited from the workspace).

`host/write-atomic!` gains the same preflight: when the measured usable
space is known and below the write's own size plus a 32 MiB margin, it
refuses with a typed `:fs/disk-pressure` carrying the measurement, instead
of half-writing a tmp file and surfacing as `fs/io write failed` with the
message discarded — which is how twelve of yesterday's sixteen failures
lost their cause.

**3. Receipts.** Every sweep appends what it measured and what it did to
`[:gc :receipts]` (newest `:receipts-keep`, default 50): pressure level,
usable bytes, store bytes, counts and byte estimates per rule. A sweep with
nothing to do reports zeros; a sweep that could not measure reports
`:unknown`. The two are distinguishable by construction.

All policy lives under `[:gc …]` in the configuration map, key for key over
`gc/defaults`; `{:gc {:enabled? false}}` turns the sweeper off. The sweeper
follows `bots/start-tick!`'s executor discipline: fixed delay, daemon
thread, Throwable caught so the collector cannot silently stop collecting.

## Consequences

- The two measured growth planes are bounded. Dry-run against the live
  2026-08-24 store (read-only, the real `plan` with the real archive
  predicate): 996 terminal goal-jobs compacted (~6.1 MB), 862 archived
  bodies evicted (~5.1 MB) — a 31.3% reduction of the serialized store
  (36.7M → 25.2M chars) on the first sweep, and with it the write
  amplification of every future transaction. Drops begin when terminal
  records age past 14 days; the workforce was 9 days old at measurement,
  so the first dry-run's drop count of zero is the window, not a defect.
- `public-goal-job` (the single-run API view) renders a compacted job
  without its objective and plan steps. That is the accepted cost of
  compaction: the run's identity, status and outcome remain; the prose
  remains in the Bot's conversation transcript.
- A full disk now degrades legibly: resident batches skip with a measured
  reason instead of dying sixteen at a time in `:running`, and the writes
  that must refuse do so with `:fs/disk-pressure` and numbers rather than
  `write failed`.
- What this deliberately does not do: it does not touch
  `[:bots :turn-history]`, `:contexts`, `:conversations` or `:traces`
  (bounded by bot count today, a candidate for the same treatment when
  measured to matter); it does not manage `app/target` (391 MB, the
  installer's plane) or `awai-worktrees` (303 MB, the worktree lifecycle's
  plane); and it cannot free the 380 GB of this machine that belongs to
  other software. The floor exists precisely because the app's own
  hygiene cannot guarantee the volume it lives on.
- Dropped goal-jobs are gone from the store. The retention window (14 days)
  is the audit window; anything that must outlive it must land somewhere
  durable before it expires. That is the existing receipts/ledger
  responsibility, not this collector's.

## Verification

`cloud.itonami.app.gc-test` exercises every policy in both directions —
keeps and drops, compacts and preserves, evicts and refuses — and pins the
pressure literals, including that `:unknown` ≠ `:ok` and `:unknown` ≠
`:hard`. The compaction test asserts field-for-field that the skeleton
keeps what each named reader consults and loses the heavy fields, and that
the message-age-not-sync-age rule evicts the case the first dry-run
measured as a miss. The suite registers in `test-runner` (the runner
refuses unlisted namespaces). Full suite at landing: 1,826 tests, 10,852
assertions, 0 failures.
