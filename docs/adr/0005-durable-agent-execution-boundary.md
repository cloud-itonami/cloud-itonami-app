# ADR-0005: Durable agent execution boundary

## Status

Accepted.

## Context

A coding model can report success while leaving no artifact, two writers can
mutate the same checkout, and a subprocess approval prompt cannot be answered
by a web client. These are host-runtime failures, not prompt-quality problems.
Provider-native capabilities must therefore remain subordinate to one local
execution boundary.

## Decision

Cloud Itonami owns four authorities for every interactive Agent run.

1. **Protocol authority** — Codex app-server and Claude stream-json are
   adapters. Their payloads are projected into `agent-event.v1`.
2. **Consent authority** — a provider request is stored privately, exposed as
   a bounded summary and SHA-256 digest, expires, and is resolved exactly once.
   Missing UI, restart, timeout, or an unknown decision declines it.
3. **Write authority** — one chat session owns one Git worktree and one local
   `agent/session-*` branch. Each run acquires a short turn lease on that
   session; another session owns a different worktree and may proceed in
   parallel. The worktree retains uncommitted changes, commits, and index state
   across turns. Integration is separate.
4. **Outcome authority** — completion requires final content, an artifact
   event, and a successful tool event. A deterministic evaluation derives its
   score from recorded results rather than a model self-assessment.

Plan mode remains read-only and does not allocate a write worktree. Codex
app-server runs on stdio per turn, so it is not remotely reachable. Its sandbox
has the session worktree as the only writable root and network access disabled.

Agent mode is supervised as more than one provider call. The default budget is
two to four cycles. Later cycles resume the same provider thread and ask it to
review the actual worktree and diff. The host stops after the minimum only when
artifact and successful-tool evidence exist; otherwise it continues until the
maximum budget and returns `needs-review`.

## State

Private installation state records:

```text
:agent-loops       run summaries, bounded events, evaluation
:agent-approvals   exact pending request until resolution, then receipt only
:agent-workspaces  session worktree/branch and active turn lease
:runner-sessions   Cloud session → provider thread/session id
```

Public session projections never include commands, provider payloads, private
approval inputs, prompts, credentials, or arbitrary stdout. Approval UI sees
only kind, summary, reason, cwd, expiry, and digest.

## Failure semantics

- A concurrent turn in the same session fails before provider execution.
- Different sessions use different worktrees and can write in parallel.
- Worktree creation failure releases the lease as failed.
- Provider errors release the lease and fail the run.
- Approval timeout declines the action.
- Text without artifact plus tool evidence becomes `needs-review`.
- A run score does not upgrade its verification status.

## Consequences

Parallel read-only discovery remains cheap, while session write work is
isolated and reviewable. Worktrees share Git objects, but each keeps a checkout
and can accumulate dependencies and build caches. The runtime therefore keeps
at most 15 present worktrees by default and removes only clean, idle checkouts;
their branches and session records remain restorable. Dirty worktrees are never
auto-removed. Provider-native steering, checkpoint recovery, Claude permission
MCP, and repository-specific evidence recipes can be added without changing
the public event contract.
