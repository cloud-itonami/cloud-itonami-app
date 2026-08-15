# ADR-0055: Bot conversations reconcile from the resident

**Status:** accepted — 2026-08-15

## Context

The Bots view streamed a turn submitted by that same browser and loaded a
durable conversation when the person selected a Bot. CLI and MCP correctly
submitted work to the resident process and persisted both sides of the turn,
but an already-open Bots view had no reason to read the store again. The new
messages appeared only after changing views or reloading the app.

The resident HTTP process is the single writer. CLI and stdio MCP are clients
of its agent-session routes rather than independent readers of `state.edn`.
Therefore the UI must reconcile with the resident API, not watch the file or
create a second in-browser conversation state.

## Decision

While the Bots view is visible, the client reads the selected Bot's existing
`GET /api/bots/{id}/messages` projection once per second with `no-store` cache
semantics. It fingerprints the durable messages, visible turn lifecycle and
handoff state. An unchanged fingerprint performs no DOM work.

When the projection changes, the client:

1. replaces the selected Bot's messages and visible turn from the resident;
2. refreshes the overview once so the rail and titlebar use server-derived
   status;
3. renders only the run and message regions, leaving the composer and settings
   panel intact; and
4. follows the new message only when the person was already near the end of the
   thread. Reading older history is never interrupted by an external turn.

Polling pauses while this browser owns an active Bot stream, so a resident
snapshot cannot replace its provisional token stream. It also backs off while
the document is hidden, stops outside the Bots view, and reconciles immediately
when the view or document becomes visible again. A transient resident restart
keeps the last readable conversation and retries instead of replacing it with
an error pane.

This is bounded near-real-time reconciliation, not a second message transport.
The local one-second bound is preferable to a permanent SSE subscription here:
the resident already exposes the complete bounded projection, no event cursor
or replay ledger is required, and window closure cannot leave a server-side
subscriber behind.

## Consequences

- CLI, MCP, handoff and background Goal updates become visible without reload.
- The server remains the authority for ownership, ordering, status and context.
- At most one selected-thread request per second is made, only while the Bots
  view is visible. Overview is fetched only after a detected change.
- Another process writing `state.edn` directly is still unsupported; every
  supported external path goes through the resident HTTP API.

## Verification

- The interaction source parses under Node.
- `web-script-test` asserts the visible-view lifecycle, no-store request,
  change fingerprint, active-stream exclusion, scroll preservation and
  immediate visibility reconciliation.
- The complete suite passes: 1,591 tests / 9,474 assertions, zero failures and
  errors.
- The changed workspace document and graph were PUT to the content-addressed
  Kotobase archive and read back byte-for-byte before their lock was updated.
