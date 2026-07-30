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
| Model transport | localhost adapters plus explicit Codex/Claude CLI runners |
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
| Drive (archive) | `m365-archive` OneDrive snapshot | Lists file state without silently materializing git-annex objects |
| Drive (documents) | `kotoba-lang/drive` workspace + an object store | Creates and edits Sheets / Docs / Forms as office envelopes; per-user ACL, quota, versions and a reversible trash; a save the surface's own validator rejects is refused |
| Scheduler | `kotoba-lang/shell` EventKit + `kotoba-lang/calendar` | Reads seven days under the explicit `calendar/read` capability |

`GET /api/workspace/worker` is served next to these but is not one of them: it
reports live queue state rather than reading an external authority, so it
bypasses the read cache.

The combined read is cached for 60 seconds — except the created documents,
which are read live, because a document missing from the list a moment after
it was created reads as a failed create. It is intentionally separate from
model context: viewing a calendar or mailbox does not send its data to an AI
provider.

Creating and editing a document is the one mutation here, and it does not
write to any of the external authorities above: it writes to a
`drive.workspace` held in the app's own state and to an object store the app
owns. Mutation adapters that write back to OneDrive, GitHub Projects or
EventKit still require a later capability and approval design.

A save is validated by the surface that owns the schema — `sheets.validate`,
`docs.validate`, `forms.validate` — after the payload has been rehydrated out
of its plain-JSON projection. The order is not an implementation detail: those
validators read namespaced keys, find none on a projected payload, and report
no problems, so validating before rehydrating would accept anything at all.
Warnings do not block; a `docs` document with no title is a draft, not a
rejected save. They are returned on the save response rather than dropped — a
warning that is computed and then discarded is the same as not having run the
validator.

Every save is a new version under a new object reference, and every version is
counted against the quota: `drive.workspace/add-version` adds and nothing
subtracts. `drive.workspace/trash` only sets a flag, so trashing frees
nothing. `documents/purge!` is the one call that does, it refuses anything not
already in the trash, and the Drive shows the trash and the quota together
because otherwise a Drive that fills up cannot say why.

### Sharing

Each principal has their own `drive.workspace`, and a grant is recorded on the
item — which lives in the granter's workspace. So a grantee looking only at
their own Drive would be told the document does not exist, and a grant nobody
can act on is a button that does nothing. `documents/locate` is what closes
that: own Drive first, then a scan of the others for an item this principal
has a role on.

- **The owner's Drive is where the bytes stay.** An editor saving a shared
  document writes into the owner's workspace and is charged against the
  owner's quota. Writing it back under the editor would fork it into a second
  copy the owner never sees; charging the editor would let anyone fill someone
  else's Drive by accepting a share.
- **Editing and disposing are different rights.** `can-write?` does not
  distinguish them, so the app does: trash, restore, purge, and all sharing
  changes are owner-only. An editor who could re-share could widen the access
  the owner granted narrowly.
- **`:owner` is not grantable.** `drive.workspace/grant` would accept it, and
  two owners either of whom can purge is a transfer dressed as a share.
- **A link may read and never write.** `create-share-link` refuses any role
  but `:viewer` and `:commenter`, and `drive.object/read-via-share-link`
  checks trash and expiry itself. Redeeming a link still requires an app
  session: the server binds loopback-only, so an unauthenticated route would
  be the only one in the app and would serve nobody who could not already
  reach the port.

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
- `GET /api/organism-workers/:id/receipts` — redacted admission/effect state;
- `POST /api/organism-workers/:id/intents` — enqueue an expiring typed intent;
- `POST /api/organism-workers/:id/intents/:intent-id/decision` — enqueue a
  human approval or rejection bound to its parent intent.

The activity adapter seeks directly to the append-only event byte cursor. An
initial request starts near the tail, and no request folds the complete Tamaki
history. Prompt, command, goal, private body, credential, and arbitrary event
data are excluded; only allow-listed lifecycle and runner metadata cross the
workplace boundary.

The cursor is persisted in the local Cloud state under the authenticated User,
active Organization, and Worker ID. Switching Organizations or AO workers
therefore cannot reuse another boundary's position. Event-file truncation or
rotation falls back to a bounded tail instead of leaving the observer stuck
beyond EOF.

Intent bodies do not enter Cloud's durable state or a public repository. The
local adapter atomically writes the complete envelope into Tamaki's private
`.tamaki/workplace/inbox/` and exposes only a digest-bearing receipt to the UI.
The UI says `admitted / not-executed` until the external supervisor emits an
effect receipt. Stop and approval intents additionally require the active
membership to be an Organization owner or admin.

## Runtime boundaries

The desktop process cannot call arbitrary remote URLs. It emits typed action
events to `bin/cloud-itonami-app-action`, which only calls the fixed loopback API.
The server selects a provider after policy evaluation. The default
configuration binds only to `127.0.0.1`. Ollama remains the configured default,
but a model that is not installed is not offered by the UI. Locally installed
Codex and Claude executables are offered as explicit CLI-backed choices. Their
subprocess boundary is local; their model traffic is governed by the CLI
account, so they are not described as local inference.

```text
native window ── action event ──> fixed action adapter
      ▲                                  │
      │ kotoba:dom                       ▼
pure app entry <── durable state <── loopback server
                                          │
                                   provider policy
                                ┌────┼──────────┐
                             Ollama CLI account cloud gate
```

### Provider-neutral agent loop

Interactive Agent work is projected through `agent-event.v1` rather than
making Codex or Claude wire payloads part of Cloud's state. The lifecycle
authority is the host supervisor; final model text is a report, while tool,
artifact, approval, and verification events are evidence.

Codex now uses the app-server stdio thread/turn/item protocol for streamed
interactive work; Claude uses stream-json. Both project into the same bounded
events. A text-only Agent result is `needs-review`, not verified work.

An Agent writer receives a detached Git worktree and an exclusive repository
lease. Codex approval requests pause at the local approval broker and can be
accepted or declined from the activity stream. Every run receives a result
score derived from artifact, tool, failure, verification, duration, and usage
facts. The remaining provider and evaluation gaps are tracked in
[ADR-0004](adr/0004-provider-neutral-agent-runtime.md) and the runtime safety
boundary is fixed by
[ADR-0005](adr/0005-durable-agent-execution-boundary.md).

### CLI conversation continuity

The CLI runner never invokes a shell and resolves only configured or fixed
absolute executable paths. Its stdin is closed immediately after launch because
both CLIs otherwise treat an open pipe as additional prompt input and wait for
EOF indefinitely.

For chat, the first successful result records the Codex thread ID or Claude
session ID at `[:runner-sessions cloud-session-id provider-id]`. A later turn
passes only the newest user message to `codex exec resume` or
`claude -p --resume`; the runner owns its earlier context. Each provider has a
separate mapping, so changing from Codex to Claude does not cross their histories.
Worker/coding-agent invocations retain their prior ephemeral posture.

The `.kotoba` policy compiles to a portable Wasm artifact. The Clojure host
mirror is intentionally small and covered by the same truth table. Moving the
actual server decision into a tendered Wasm component is the next hardening
step; the current host mirror is not described as if it were already tendered.

## API profile

The public compatibility slice is:

- `GET /v1/models`
- `POST /v1/chat/completions`

Management endpoints live under `/api` and are not part of the OpenAI
compatibility claim. Function-call deltas, Responses API, embeddings, Anthropic
compatibility, and MCP are future profiles and will receive separate
compatibility tests.

`POST /v1/chat/completions` honours `stream: true` as Server-Sent Events in the
`chat.completion.chunk` format: a role chunk, one chunk per provider delta, a
`finish_reason: "stop"` chunk, the usage chunk when
`stream_options.include_usage` asked for it, then `data: [DONE]`. Every chunk
repeats the completion id, which is the same id the store records for the turn.

The response headers are written on the first frame rather than when the
request arrives. Once `200` and `text/event-stream` are sent the status can no
longer change, so a provider refusal or a refused local model would have to be
reported inside a successful stream — where a client reads it as an empty
answer. Deferring means those failures reach the same `ex-data` status mapping
as a non-streaming request: a denied cloud provider is a `403`, under
`stream: true` as much as without it. After the first delta that is no longer
possible and the failure is an `error` frame instead, with no `stop` chunk
claiming the answer finished.

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

The OS installation directory's `state.edn` is the durable local state
(`~/Library/Application Support/Cloud Itonami/state.edn` on macOS). A
repository-local `data/` tree is a legacy source only: first launch copies a
valid tree non-destructively and never merges over an existing installation.
Each installation has a stable UUID. Before replacement, the previous state
is written as an AES-256-GCM recovery snapshot whose key remains in macOS
Keychain.

Each message is represented both
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
6. Add a function-call compatibility suite. (Streaming has one:
   `test/cloud/itonami/app/openai_compat_test.clj` reads the SSE frames over
   real HTTP, in both modes.)
