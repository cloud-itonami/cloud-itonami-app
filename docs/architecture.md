# Architecture

## Goal

Own the durable layer around interchangeable models: agent identity, local
memory, tool authority, and the user surface. Local inference is the default
and cloud inference is an explicit policy decision, never a fallback caused by
an error.

The design takes Osaurus's public harness decomposition as a reference, then
maps each responsibility to an existing Kotoba authority instead of reproducing
its Swift implementation.

| Harness responsibility | cloud-itonami-app authority |
|---|---|
| Desktop lifecycle and input | `kotoba-lang/shell` native host |
| UI semantics | pure `kotoba:dom` surface program |
| Provider selection | safe `.kotoba` policy + host-side mirror |
| Local/cloud model transport | localhost service adapters |
| Session memory | `kotoba.kgraph` EAV datoms + durable EDN |
| Compatible client access | OpenAI-compatible loopback HTTP API |
| Secret access | named environment variables at provider boundary |

## Workspace integrations

`GET /api/workspace` composes read models from authorities that already exist
in this checkout. The UI does not synthesize missing mail bodies, files,
projects, or events.

| Surface | Authority | Current contract |
|---|---|---|
| Inbox | `m365-archive` and `net-kotobase/mail-worker` | Lists archive metadata; sealed reception remains recipient-key controlled |
| Projects | `kotoba-lang/com-github` | Reads GitHub Projects v2; shows `permission-required` without `read:project` |
| Drive | `m365-archive` OneDrive snapshot | Lists file state without silently materializing git-annex objects |
| Scheduler | `kotoba-lang/shell` EventKit + `kotoba-lang/calendar` | Reads seven days under the explicit `calendar/read` capability |

`GET /api/workspace/worker` is served next to these but is not one of them: it
reports live queue state rather than reading an external authority, so it
bypasses the read cache.

The combined read is cached for 60 seconds. It is intentionally separate from
model context: viewing a calendar or mailbox does not send its data to an AI
provider. Mutation adapters require a later capability and approval design.

## Artificial-organism workers

An organization can include an independently running artificial organism
through an `OrganismWorker` assignment. This is distinct from the ephemeral
background model runs in `cloud.itonami.app.worker`. Cloud Itonami projects the
organism's redacted activity and sends expiring typed intents, but does not own
its supervisor, memory, incarnation, or repository authority.

For Etzhayyim, the Tamaki repository AO runs under its existing local or
Murakumo supervisor and appears as an Etzhayyim worker. UI or network loss
therefore interrupts observation, not organism lifecycle. See
[ADR-0002](adr/0002-external-artificial-organism-workers.md).

The local management API exposes the active organization boundary:

- `POST /api/identity/organizations/accept` — accept a User-bound,
  one-time Organization invitation and select its Membership;
- `GET /api/organism-workers` — assigned AO directory;
- `GET /api/organism-workers/:id/snapshot` — safe current projection;
- `GET /api/organism-workers/:id/activity?cursor=…` — bounded cursor page.

The activity adapter seeks directly to the append-only event byte cursor. An
initial request starts near the tail, and no request folds the complete Tamaki
history. Prompt, command, goal, private body, credential, and arbitrary event
data are excluded; only allow-listed lifecycle and runner metadata cross the
workplace boundary.

## Runtime boundaries

The desktop process cannot call arbitrary remote URLs. It emits typed action
events to `bin/cloud-itonami-app-action`, which only calls the fixed loopback API.
The server selects a provider after policy evaluation. The default
configuration binds only to `127.0.0.1`, enables only Ollama, and denies cloud
egress.

```text
native window ── action event ──> fixed action adapter
      ▲                                  │
      │ kotoba:dom                       ▼
 pure app entry <── durable state <── loopback server
                                          │
                                   provider policy
                                     │          │
                                  local      cloud gate
```

The `.kotoba` policy compiles to a portable Wasm artifact. The Clojure host
mirror is intentionally small and covered by the same truth table. Moving the
actual server decision into a tendered Wasm component is the next hardening
step; the current host mirror is not described as if it were already tendered.

## API profile

The public compatibility slice is:

- `GET /v1/models`
- `POST /v1/chat/completions`

Management endpoints live under `/api` and are not part of the OpenAI
compatibility claim. The current completion endpoint is non-streaming.
Function-call deltas, Responses API, Anthropic compatibility, and MCP are
future profiles and will receive separate compatibility tests.

The first-party chat UI uses `POST /api/chat/stream`, a chunked NDJSON
management endpoint. Provider deltas are forwarded as they arrive; only a
completed assistant turn is persisted. A client disconnect or Stop action
therefore leaves the submitted user turn but does not record a partial
assistant message.

## Background worker runs

The Worker surface queues prompts that should outlive a single interactive turn.
A run is admitted by a fair semaphore (`:worker :max-concurrency`, default 2) so
background work cannot starve interactive chat of the local model, then streamed
through the same `service` path as chat — which means the fail-closed provider
policy applies to worker runs identically. There is no separate egress route.

| Endpoint | Purpose |
|---|---|
| `GET /api/workspace/worker` | Live queue: counts, per-run status, streamed output |
| `POST /api/workers` | Enqueue a run |
| `POST /api/workers/{id}/cancel` | Ask a queued or running run to stop |
| `POST /api/workers/clear` | Drop finished runs, keep active ones |

Runs are held in memory only, and this is a deliberate limit rather than an
oversight: `store/transact!` rewrites the whole state file on every change, so
streaming deltas through durable state would rewrite `state.edn` once per token.
The durable store instead receives one bounded `:worker/finished` event per run,
and each run's scratch chat session is dropped on completion — the run record
already carries its prompt and output, so keeping the session would only grow
`state.edn`. **Runs therefore do not survive a restart, and the UI says so.**

Cancellation is cooperative: the flag is observed at the next streamed delta, so
a provider that has stopped emitting can keep an in-flight HTTP request open
until its own timeout. Output is capped at 16,000 characters per run and marked
`truncated?` rather than silently trimmed.

## Persistence

`data/state.edn` is the durable local state. Each message is represented both
as ordered session data and three EAV datoms:

```clojure
[message-id :message/session session-id]
[message-id :message/role role]
[message-id :message/content content]
```

The ordered projection keeps chat reconstruction cheap; the datom projection
provides the Kotoba-native graph basis for later memory extraction and
relevance queries. Writes replace the state file atomically.

## Threat model

- Network exposure: non-loopback binding is rejected while
  `:bind-loopback-only?` is true.
- Accidental cloud fallback: no fallback chain exists. A remote provider needs
  provider enablement, the global cloud gate, and the explicit review-policy
  gate.
- Secret disclosure: config stores the environment-variable name, not the
  secret. Public state omits both.
- UI authority: the surface declares intent but owns no network or filesystem
  capability.
- Prompt retention: chats are stored locally by design. Deleting a session
  removes the ordered transcript; compaction/retraction of its historical
  datoms is not yet implemented and the UI does not claim secure erasure.

## Next slices

1. Tender the provider policy Wasm in the live request path.
2. Add tool manifests with per-working-folder capabilities and approval
   receipts.
3. Add MCP server/client profiles.
4. Add memory distillation and relevance retrieval over kgraph.
5. Add schedules/watchers after tool isolation is available.
6. Add streaming and function-call compatibility suites.
