# ADR-0004: Mature agent loops around a provider-neutral event runtime

## Status

Accepted and implemented incrementally.

## Context

Cloud Itonami can invoke Codex CLI and Claude Code, resume their sessions, and
show their final answer. The Agent surface now defaults to `Agent / Auto`, but
the runtime beneath that surface is still a subprocess wrapper:

- the app owns only prompt, final text, usage, and provider session ID;
- provider tool calls and file changes are not durable first-class facts;
- the UI cannot distinguish reasoning, tool execution, verification, approval,
  or a stalled process;
- a provider saying it finished is treated too much like evidence that the
  requested outcome exists;
- the interactive CLI path and the older `agent-control` HIL path use separate
  lifecycle vocabularies.

Codex app-server exposes threads, turns, items, streamed progress, steering,
interrupts, and approval requests. Claude Code exposes stream-json, bounded
agent turns, resumable sessions, and an MCP permission-prompt tool. Copying
either provider's wire schema into Cloud state would make the other provider a
second-class compatibility layer and would couple Tamaki to product churn.

## Decision

Introduce a provider-neutral runtime with three separate authorities:

```text
provider adapter              agent-loop supervisor             evidence gates
Codex app-server ─┐          Objective → Discover → Plan       tests
Claude stream-json ├─ events → Execute → Verify → Review ────→ diff
local / AO actor ─┘                    ↕ approval broker         screenshot
                                      checkpoint                 review
```

### Event authority

`cloud.itonami.app.agent-event` is the shared event vocabulary:

- run and phase lifecycle;
- model lifecycle;
- tool lifecycle;
- artifact changes;
- approval requests and decisions;
- verification verdicts.

Every event has an event ID, run ID, session ID, timestamp, type, and bounded
data map. Raw prompts, command bodies, provider transcripts, credentials, and
arbitrary provider payloads are not public event data.

Live transports may carry token and reasoning deltas, but durable installation
state records only bounded lifecycle and evidence events. Persisting every
token would rewrite the complete EDN state for every delta.

### Loop authority

The supervisor, not the model prompt, owns the lifecycle:

```text
Objective → Discover → Plan → Execute → Verify → Review → Integrate → Reflect
```

A provider may perform several of these internally during the first migration
stage. The adapter must be honest about that opacity; it must not invent
fine-grained phases it did not observe.

A run completes as:

- `succeeded` when a plan was produced in Plan mode, or Agent mode produced
  verifiable tool/artifact evidence;
- `needs-review` when a provider returned text without verifiable artifacts;
- `blocked` when missing capability or human authority prevents progress;
- `failed` on provider, policy, timeout, or verification failure.

Final assistant text is a projection of work, not the work artifact.

### Approval authority

Plan is read-only. Auto means workspace write within the sandbox; it does not
mean unrestricted host access or automatic external side effects.

The target Auto posture is:

- workspace write;
- network off unless an explicit allowlist grants it;
- on-request approval for sandbox escape and outward capabilities;
- typed approval bound to exact tool input and expiry;
- optional independent reviewer for eligible low-risk requests;
- fail closed on reviewer error or timeout.

During migration, the existing CLI adapter remains bounded to the configured
workspace. It must not be described as equivalent to a bidirectional approval
broker until Codex app-server and Claude's permission-prompt MCP adapter are
connected.

### Isolation and concurrency

The write invariant is:

```text
one session = one worktree = one branch
one session/provider pair = one resumable provider thread
one active turn = one short write lease
```

Parallel read-only discovery may share a repository. Parallel sessions write
in separate worktrees; two turns may not concurrently write the same session
worktree. Integration is a separate reviewed phase.

### Context and repeatability

Durable repository guidance belongs in `AGENTS.md`; repeatable workflows belong
in scoped skills; live external context belongs behind typed capabilities.
Each objective carries outcome, constraints, and machine-verifiable done
criteria. Context compaction must retain decisions, unresolved blockers,
changed paths, failing checks, and approval receipts.

### Evaluation and kaizen

Loop quality is evaluated from results:

- verified completion rate;
- integration/merge rate;
- regression and rollback rate;
- human interventions per successful run;
- time, tokens, and cost per integrated result;
- scope-control and security-policy violations;
- repeated failure signatures.

Prompt, model, policy, and workflow changes are versioned experiments. They may
be promoted only when a representative task corpus improves without weakening
security or increasing regression rate.

## Migration status

| Stage | State | Delivered boundary | Remaining work |
| --- | --- | --- | --- |
| Event spine | Complete | `agent-event.v1`, durable lifecycle, live/history UI, honest verdicts | Event schema versioning policy |
| Native streams | Complete for interactive Codex and Claude | Codex app-server stdio; Claude stream-json | Long-lived pooled app-server and Claude MCP permission adapter |
| Approval broker | Codex complete | Exact-request digest, expiry, local UI decision, fail-closed timeout | Claude permission-prompt MCP and passkey escalation for outward capabilities |
| Evidence gates | Baseline complete | Agent success needs both artifact and successful tool evidence | Repository-declared test/lint/build/visual receipts |
| Isolation | Core complete | One persistent branch/worktree per session and one turn lease; clean idle cap | Snapshot restore, integration lease, steering, retry and idempotency |
| Evaluation | Per-run complete | Deterministic result score and grade | Replay corpus, regression comparison, experiment promotion |

Each stage must preserve the loopback-only server, local durable state,
provider fail-closed policy, and the OrganismWorker authority boundary from
ADR-0002.

## Consequences

- UI, persistence, evaluation, and Tamaki no longer depend on one provider's
  event names.
- A text-only completion is no longer mislabeled as completed implementation.
- The existing `agent-control` HIL can migrate onto the same event vocabulary
  instead of being replaced.
- Codex approval requests can now pause and resume a live turn. Mid-turn
  steering remains a separate capability and is not implied by approval.
- More evidence calls consume time and tokens, so budgets and measured
  promotion are part of the runtime rather than later optimization.
