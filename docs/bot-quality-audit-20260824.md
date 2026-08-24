# Bot output-quality audit — 2026-08-24, 20 samples

The measured basis for `resources/cloud-itonami-bot-quality-receipt.edn`.
This supersedes the 3-sample baseline of 2026-08-23, which recorded points
but not the per-sample evidence, so it could not be re-checked or extended.

## Method

**Sampling (deterministic, no cherry-picking):** for every Bot, the newest
turn with `state :completed` and a result of more than 200 characters; the
20 newest of those across all Bots, one per Bot. 19 of 20 ran on
2026-08-24 (after the fastmtp model switch took effect), 1 on 2026-08-23.
All 20 are resident workforce ticks — that is what the fleet produces
unattended, so that is what is audited.

**Verification:** every mechanically checkable claim in each result —
working-tree states, file listings, commit hashes and subjects, string
searches, counted artifacts — was re-executed against the Bot's admitted
workspace (`:bot/workspace`, `orgs/network-awai/<business>`). A claim the
auditor could not check (statements about host-verifier internals, external
facts) is excluded from the grounding denominator rather than counted as
true — 'could not check' must not print as 'checked and true'
(ADR-2608136000).

**Metrics (fractions, as `bot_slo` consumes them):**

- `factual-grounding-rate` — samples whose every checkable claim held /
  samples with at least one checkable claim.
- `instruction-adherence-rate` — samples respecting the resident-tick
  contract: bounded reads, no invented work, no cross-business step, no-op
  completed with recorded evidence.
- `actionable-answer-rate` — samples ending in a concrete next action, a
  concrete prerequisite, or a no-op justified by verified evidence.

## Results

grounding **12/16 = 0.75** · adherence **20/20 = 1.00** · actionable
**20/20 = 1.00**. Four samples (S1, S2, S8, S10) contain no checkable
claim — host-fallback no-op templates plus a prerequisite — and are
excluded from the grounding denominator.

The quality-suite gate requires grounding ≥ 0.95, so with this receipt the
gate is **measured and failing**, which is the honest state: the audit
found a repeating defect class, detailed below.

### The defect class: reported searches that were not performed as reported

All four grounding failures are the same shape — the Bot asserts a search
result ("0 hits", "only in test files", "only in migrations/") that
re-execution contradicts:

| # | Bot | False claim | Re-measured |
|---|-----|-------------|-------------|
| S5 | Cloud Itonami · Sales | "All 'prospect' references are in test files" | `legal/company.md` and `data/australia-sydney-loop.edn` (`:sales/prospects {}`) also match — non-test files. The conclusion (no named qualified prospect record) still holds. |
| S13 | net kotobase · Product Manager | "CHANGELOG.md showing 4 Unreleased items all dated 2026-08-03" | 27 top-level Unreleased items, 6 dated 2026-08-03 |
| S16 | net kotobase · Kaizen analyst | "回遊" hits "only … in migrations/cljc-worker-v2-v3/test/" | `kotobase-api-gateway-cljs/src/kotobase/kaiyu_store.cljc` (landed 2026-08-07) and 3 more gateway files match; it is the 回遊 store itself |
| S18 | murakumo.cloud · Financial Chief | "search 'runway' → 0 hits" | 12 files match, incl. `scripts/homeostasis-collect.cljs` (`:treasury-runway-days`) |

In S5, S16 and S18 the *conclusion* each Bot drew happens to survive the
correction; in S13 the count itself was the content. Either way the
reported measurement was false, which is the exact class ADR-2608136000
exists for: an unperformed check printing like a performed one.

**Correction, same day:** the mechanism was measured and it is the TOOL,
not the Bots' honesty. `workspace_search` scanned the first 300 files in
filesystem-walk order, skipped files over 256 KiB, capped output at 200
lines — all silently. Against the audited workspaces that is 300 of
35,612 files for net-kotobase (0.8%), 300 of 1,465 for cloud-murakumo
(20%), 300 of 3,685 for cloud-itonami (8%) — so an empty result over a
sliver of the tree printed exactly like "absent from the repository", and
a Bot repeating it was repeating its instrument. Fixed by making every
search result begin with a coverage receipt
(`SEARCH RECEIPT: matches=… files-searched=n/m`) and an unmissable
`COVERAGE INCOMPLETE` warning produced by the same code that truncates;
the scan window is now deterministic (sorted) as well. The grounding rate
below stands as measured — the fleet's output was factually wrong
regardless of whose fault — but the remediation lands in the tool, and
the next audit measures whether the Bots now repeat the receipt instead
of the false zero.

### Sample register

Turn ids are the reproducibility key — each is in `[:bots :turn-history]`.

| # | Bot | Turn (UTC) | Checkable claims | Grounding |
|---|-----|-----------|------------------|-----------|
| S1 | app aozora · Engineer | 08-24T02:10 `c-…` | none (host-verifier prerequisite) | — |
| S2 | gameka · survivors-zombie-mall | 08-24T02:08 | none | — |
| S3 | net kotobase · Engineer | 08-24T02:06 | working tree `M sdk/kotobase-rust/{Cargo.lock,Cargo.toml,src/lib.rs}` + `?? kotobase-cf-wasm/ kotobase-head-store/` exact; lib.rs CID/C3 content | pass |
| S4 | nexus x402 · Sales | 08-24T02:04 | `docs/gtm/` = exactly the 2 named files | pass |
| S5 | Cloud Itonami · Sales | 08-24T02:03 | see defect table | **fail** |
| S6 | club shinshi · Marketer | 08-24T02:02 | README developer-facing; `90-docs/` = adr + web-service-score only | pass |
| S7 | network isekai · Marketer | 08-24T01:59 | README names kami engine + `:physics/world`; 6 evidence files | pass |
| S8 | animeka · The Last Ramen Master | 08-24T01:56 | none (consent determination is external) | — |
| S9 | murakumo.cloud · Product Designer | 08-24T01:55 | tip `2dbe86e` 2026-08-21 "Serve Node hero images from /img, not /onprem (#86)"; clean tree | pass |
| S10 | app aozora · QA | 08-24T01:53 | none in sampled text | — |
| S11 | network isekai · Product Manager | 08-24T01:52 | 6 evidence artifacts; log through 2026-08-20 | pass |
| S12 | network isekai · Business Owner | 08-24T01:50 | catalog-size 42, 30/30 checked pass (20260817 evidence), out-of-reach exactly `[gftd/castlevania gftd/sekaiju]` | pass |
| S13 | net kotobase · Product Manager | 08-24T01:47 | docs count 30+ ✓, 5 named docs ✓, CHANGELOG count ✗ | **fail** |
| S14 | nexus x402 · Financial Chief | 08-24T01:46 | 3 sellers at 0.01/0.001/0.50 in `wrangler.jsonc` SELLERS_JSON; 08-15 credits commits | pass |
| S15 | club shinshi · Sales | 08-24T01:43 | "no prospect record": 0 matches repo-wide | pass |
| S16 | net kotobase · Kaizen analyst | 08-24T01:41 | see defect table | **fail** |
| S17 | Cloud Itonami · Product Manager | 08-24T01:40 | launch-blockers.md says "6 `[CONFIRM: …]` placeholders"; file unchanged since 2026-07-12 (git log) | pass |
| S18 | murakumo.cloud · Financial Chief | 08-24T01:11 | H100 4.56 in `scheduler.cljc` ✓, clean tree ✓, runway search ✗ | **fail** |
| S19 | net kotobase · Business Owner | 08-24T01:05 | PLANS/RUNBOOK exist; working tree = sdk edits + 2 untracked dirs exact | pass |
| S20 | net babiniku · Customer Support | 08-23T23:40 | "no support inbox in repo": none found | pass |

### Component points

Points are the metric rates and per-sample judgments mapped onto each
component's scale, floored; the mapping is recorded so the next audit can
disagree with it explicitly.

| Component | Points | Basis |
|-----------|--------|-------|
| factual-grounding | 18/25 | 0.75 grounding rate × 25, floored |
| instruction-adherence | 20/20 | 1.00 — every sample bounded, no invented work, no cross-business step |
| specificity-actionability | 17/20 | 15 samples fully specific (1.0), 5 host-fallback templates thin (0.5) |
| clarity | 14/15 | all readable; 2 template-only samples at 0.75 |
| structure | 8/10 | observed/forecast/proposal separation explicit in 4, implicit in most, absent in the 5 templates |
| uncertainty-non-hallucination | 8/10 | S14/S18 exemplary refusals to invent figures; the 3 false precise counts (S13, S16, S18) penalized here as asserted-but-unperformed measurements |

Total **85/100**.

## What should change

1. The four grounding failures share one mechanism: `workspace_search`
   coverage (or the Bot's reading of it) diverges from what the Bot then
   asserts. Worth a host-side receipt: attach the actual match count to the
   search receipt so a Bot cannot truthfully say "0 hits" over a non-empty
   result.
2. The 5 host-fallback no-op templates are the thinnest outputs in the set.
   They are safe but carry only a receipts count; the prerequisite line the
   Bot adds is the only content. If the fallback quoted the last verified
   observation, the same turn would be evidence instead of a shrug.
