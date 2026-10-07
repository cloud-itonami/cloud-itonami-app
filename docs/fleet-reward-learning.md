# Fleet reward and procedural self-improvement

The fleet previously retained evidence and reflected on conversations, but applied
model-proposed profile updates without an independent comparison. All Bot roles
now share `itonami.procedural-reward.v1`. SOUL records objectives and invariant
gates; a host-owned evaluator decides adoption. This is procedural learning, not
GRPO, model-weight training, or demonstrated customer-outcome improvement.

## Contract and scope

Producer objectives are verified publication, continuity, quality and diversity;
schedulers use measured reliability and latency; researchers use source-pinned
correctness and coverage; actors use task acceptance and customer outcomes.
Unknown measurements remain unknown. Completion and tool receipts do not prove
business success. Quality gates take precedence over tokens, cost and latency.
Neither rewards nor documents create permissions.

Native profiles receive an idempotent managed SOUL section through the existing
owned profile lifecycle. Existing content, revisions and rollback history remain.
For existing Hermes profiles, run:

```
kbb --backend sci --classpath src:scripts scripts/fleet_reward_contracts.cljk --plan
kbb --backend sci --classpath src:scripts scripts/fleet_reward_contracts.cljk --apply
```

The rollout backs up original private documents, records before/after hashes,
refuses symbolic links and concurrent edits, and never changes jobs or grants.
Hermes execution outcomes are consumed by the bounded local adapter
`scripts/fleet_reward_worker.cljk --run`. It reads genuine terminal cron records,
uses the same Murakumo-only proposer and swapped comparison contract, and writes
only MEMORY.md after retaining the baseline privately. It never executes proposed
commands, changes SOUL, grants or jobs. A private source-hash ledger deduplicates
crashes/timeouts, one worker lock serializes the fleet, and two attempts per UTC
day bound model cost. Active profiles are selected fairly by prior attempt.
Native Itonami uses its authenticated executor, including imported native Bots.

## Native loop

1. A genuine owned terminal turn queues a bounded review with a frozen profile
   revision, source turn, outcome and last eight bounded conversation messages.
2. `profile_update` proposes MEMORY or a named skill. It cannot apply a revision,
   change SOUL/USER, credentials or evaluator rules. Ordinary background reviews
   may also propose a change, or return no change.
3. The host validates document names, size and secret restrictions. Two separate
   Murakumo-only, tool-free, non-thinking grading calls compare baseline/candidate
   against identical evidence, swapping A/B order in the second pass.
4. Grounding, usefulness and preservation must all be non-regressing in both
   passes; total quality must improve by at least 0.3 (mean 0.1). Missing/invalid
   grades, position bias, contradictions or stale revisions fail closed.
5. Only adopted updates pass the existing compare-and-swap writer. Held and
   failed reviews retain state without applying changes. Revision history retains
   the preceding content and host evaluation, allowing restoration.
6. Learned skill validation still requires a later completed turn exercising its
   named tool; a document-quality grade does not validate skill execution.

These graders are independent calls, not independent ground-truth authorities.
The measured scope is **profile quality on fixed evidence**. Real task-outcome
improvement remains explicitly unmeasured; production A/B outcomes require a
task-specific verifier and must not be inferred from these scores.

## Runtime and budgets

The existing scheduler dispatches reviews, and native API completion dispatches
under that same actual authenticated session. Initial provisioning and review
dispatch run asynchronously, with one pending dispatch per owner/org and at most
64 pending owners; they cannot delay the original run completion response. Profile
discovery reads runtime identities/status without building the screen/tool catalog.
No owner session is synthesized.
One background worker, idle inference admission, queue cap64, retained review
cap512, three calls per ordinary review, and a default two reviews per owner/org
per UTC day bound work. `bots.self-improvement.daily-limit` may be configured
between0 and24. Failed attempts count. No retry of uncertain model calls.
Background review inference is not claimed free. Disabled self-improvement remains
disabled. Old queued records without the new baseline revision fail closed.

Owned human GET `/api/bots/<id>/skills` and existing authenticated Hermes GET
`/api/profiles/<id>/learning` return profile reviews including evaluation,
revision, changed files and held/adopted/failed state. It does not expose another
owner's profile. Public bots-status remains an aggregate signed projection.

## Adapter verification and limits

The Hermes adapter reads current enabled job status and bounded output; potential
credential material is refused before any model request. Claimed/in-flight jobs
and unsafe job IDs are excluded. Receipts retain the bounded source evidence and
output hash/path, not only grades. A terminal failure is
valid learning evidence, not a successful task. Oversized/unavailable evidence is
skipped. Frozen MEMORY source hashes guard concurrent edits and source IDs avoid
uncertain POST retries. JSON records retain the comparison; private Markdown
backups preserve the old MEMORY. No real task is re-executed during evaluation.

A separate hourly launchd job invokes one bounded pass from the immutable release.
The original Hermes schedules are untouched. Contract propagation reaches dormant
profiles too; automatic evaluation needs new eligible terminal evidence. Propagation
counts must never be presented as successful learning counts.

## Reference

MiMo-V2.6 publicly describes Groupwise Reward Synthesis and Groupwise Advantage
Redistribution, relative grading and reward-hacking defenses:
https://huggingface.co/XiaomiMiMo/MiMo-V2.6-Pro-RL/blob/main/README.md
We borrow comparison and verifier separation, not its training algorithm or
claims of general recursive model improvement.
