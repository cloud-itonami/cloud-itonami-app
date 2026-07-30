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
3. **Write authority** — one Agent run acquires one repository lease. Git
   repositories execute in a detached worktree outside the source checkout.
   The worktree is retained as a reviewable result; integration is separate.
4. **Outcome authority** — completion requires final content, an artifact
   event, and a successful tool event. A deterministic evaluation derives its
   score from recorded results rather than a model self-assessment.

Plan mode remains read-only and does not allocate a write worktree. Codex
app-server runs on stdio per turn, so it is not remotely reachable. Its sandbox
has the run worktree as the only writable root and network access disabled.

## State

Private installation state records:

```text
:agent-loops       run summaries, bounded events, evaluation
:agent-approvals   exact pending request until resolution, then receipt only
:agent-workspaces  run workspace and repository lease
:runner-sessions   Cloud session → provider thread/session id
```

Public session projections never include commands, provider payloads, private
approval inputs, prompts, credentials, or arbitrary stdout. Approval UI sees
only kind, summary, reason, cwd, expiry, and digest.

## Failure semantics

- An active repository lease causes a second writer to fail before provider
  execution.
- Worktree creation failure releases the lease as failed.
- Provider errors release the lease and fail the run.
- Approval timeout declines the action.
- Text without artifact plus tool evidence becomes `needs-review`.
- A run score does not upgrade its verification status.

## Consequences

Parallel read-only discovery remains cheap, while write work is isolated and
reviewable. Local storage grows with retained worktrees, so a later lifecycle
actor must prune only integrated, abandoned, or explicitly rejected runs.
Provider-native steering, checkpoint recovery, Claude permission MCP, and
repository-specific evidence recipes can be added without changing the public
event contract.
