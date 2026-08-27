# ADR-0072: Bot runs use a measured model and durable execution slices

Status: accepted, 2026-08-23

## Observed failure

The resident UI showed three different outcomes under one impression of
"unstable Bots": provider timeouts, tool-budget exhaustion, and generic
execution failures. The host ledger separates them.

Live 24-hour baseline at 2026-08-23T13:08Z:

- 155 turns; 89 completed; completion rate 57.4%.
- 46 `provider/timeout` outcomes (29.7%).
- 11 `tool-budget-exhausted` outcomes (7.1%).
- p50 247 s and p90 1,749 s across resident and interactive work.
- stability score 55/100; quality baseline 75/100 but only 3 of the required
  20 scored tasks, so quality remains insufficiently sampled.

This is a compound failure, not one service's outage. At the same time the
Murakumo gateway `/ready` returned 200 but reported one configured inference
slot, `busy: 1`, `available: 0`. A real `murakumo-main` completion returned
HTTP 502 after 45.41 s. The explicit
`qwen3.8-27b-fastmtp-aggressive` model returned the required answer in 20.87 s.
Five subsequent real requests all returned HTTP 200 and obeyed the JSON-only
instruction: cold 22.60 s; warm 2.54--3.09 s.

The tool failure was local policy: resident jobs received four tool calls and
were then recorded as failed even when every call had made progress.

## Decision

1. New and provisioned resident Bots use
   `qwen3.8-27b-fastmtp-aggressive` by default. `murakumo-main` remains an
   explicit choice; changing inference does not change a Bot's grants.
2. A resident execution gets eight tool calls per scheduling slice. Reaching
   the slice boundary checkpoints the durable Goal and automatically requeues
   it with its accumulated messages, receipts, turn count, and tool count.
   It is no longer a terminal `tool-budget-exhausted` outcome.
3. The prompt no longer tells the model to stop after two repository reads. It
   asks for the reads needed to verify exactly one bounded step.
4. Every resumed slice receives an explicit convergence instruction: reuse the
   evidence and receipts already in the transcript, do not repeat discovery,
   and complete or name the exact blocker; make another tool call only for one
   specific missing fact. This is based on a live run which otherwise reached
   24 read calls across three slices without concluding.
5. This does not create unlimited authority. Connector admission, write
   approval, one active run per Bot, one active resident inference slot, context
   compaction, and cancellation remain unchanged. A hard safety fence remains
   after 24 continuation slices; crossing it reports a long-running execution
   stop rather than silently looping forever.

## Current decision (2026-08-26)

Item 1 above is reversed for the same reason it was written: live
measurement. On 2026-08-26 `POST /v1/chat/completions` with
`qwen3.8-27b-fastmtp-aggressive` returned HTTP 502 and a non-JSON body
starting `modal-http`. The same hour, `murakumo-main` returned HTTP 200
(`alias-for: qwen3.8-27b`, serving). New and provisioned resident Bots
therefore use the alias `murakumo-main`. FastMTP remains a named model
and an explicit operator override; it is also a `model-fallbacks` *source*
that retries the alias. Items 2–5 are unchanged. The 2026-08-23
measurement is not rewritten.

## Landing (2026-08-27 closing)

- `cloud-itonami-app` default branch: merge `0d0cd11` of
  `30b5ee3`. Defaults, last-resort hardcode, and FastMTP
  `model-fallbacks` source all name `murakumo-main`.
- Tests this session: `config-test` + `bots-test` with
  `CLOUD_ITONAMI_DATA_DIR` unset — 157 tests, 687 assertions, 0
  failures. Full `clojure -M:test` was not run.
- `local-murakumo` default branch: merge `9e4f054` of `1325cee`.
  JS entry rewrites POST `/v1/chat/completions` and `/v1/messages`
  with model `qwen3.8-27b-fastmtp-aggressive` to `murakumo-main`.
- Worker `api.murakumo.cloud` last-writer-wins. Version
  `79fc440b-bcff-4fdc-b531-09b7bd84331a` (2026-08-26T10:47Z) answered
  FastMTP HTTP 200 in 3.1 s. A later upload
  `0bef970d-6c19-454b-aa84-4d2f60bb2121` (10:49Z) restored the
  SyntaxError 502. Closing re-deployed `d66024cb-3e63-4ac0-80f5-96c620a4a092`.
- 24-hour SLO gates above remain unmeasured.

## Resume

1. Live itonami JVM is still release `dacb86f` (PID 72151 on
   `127.0.0.1:1338`). Operator `~/.cloud-itonami/data/config.edn`
   already sets workforce `:model "murakumo-main"` and FastMTP
   fallbacks. Next install picks `0d0cd11`.
2. Six org-owned workforce Bots still store FastMTP. Personal CLI
   session cannot retarget them. Gateway rewrite is the cover.
3. `bot-709ea1c1-d295-471c-abcc-250f9228a550` stays on
   `qwen3.8-27b-throughput-5090` by design.
4. Durable Worker fix belongs in `src/local_murakumo/worker.cljs`
   `fetch-inference` (text then parse; hosted non-JSON retries
   fallbacks). That needs a governed `release/cljs/` refresh.
5. Do not deploy `local-murakumo` from a tree that lacks
   `src/dead_hosted_model.js`. Last upload wins.

## Acceptance gates

The live workforce is accepted only when a new post-change window measures:

- at least 100 turns;
- completion rate >= 90%;
- provider timeout rate < 2%;
- terminal tool-budget rate < 1%;
- no run stale for more than 30 minutes;
- at least 20 scored tasks with factual grounding >= 95%, instruction
  adherence >= 90%, and actionable answers >= 80%.

The immediate release gate is narrower: full repository tests pass, the
checkpoint mutation is caught, five of five direct model probes pass, the
resident health endpoint is 200, and at least one real resident Bot completes
on the new model. Passing this release gate does not retroactively turn the
24-hour baseline into a passing SLO.

## Verification

- Full suite after terminal-turn recovery: 1,818 tests, 10,801 assertions,
  zero failures and zero errors.
- Mutation: changing the checkpoint-state branch so it cannot match causes
  `a-goal-execution-slice-checkpoints-and-is-requeued` to fail on both durable
  state and automatic requeue; restoring the branch passes.
- Context remains 32,768 tokens for both Murakumo model ids and compacts before
  the configured threshold.
- Four non-cancelled post-change resident runs completed on the explicit
  FastMTP model; one used 17 tools across durable slices and still converged.
  A separate diagnostic run was deliberately cancelled after exposing the
  repeated-discovery problem that the resume instruction now prevents.
- Two historical AgentRuns were terminal `:failed` while their UI/SLO turn
  projections remained `:running`. Startup now converges a running projection
  to any terminal AgentRun (`succeeded`, `failed`, `cancelled`, or `rejected`)
  without changing genuinely active checkpoint recovery. This removes false
  stale-running failures; it does not rewrite the historical timeout outcome.
