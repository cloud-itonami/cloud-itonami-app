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
| Compatible client access | OpenAI-compatible loopback HTTP API; MCP over stdio for the fleet |
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
| Drive (documents) | `kotoba-lang/drive` workspace + an object store | Creates and edits Sheets / Docs / Forms / Slides as office envelopes; per-user ACL, quota, versions and a reversible trash; a save the surface's own validator rejects is refused |
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

### EDN at rest, JSON on the wire

The object store holds EDN. `documents/stored-envelope` is the same
four-key shape the office envelope has — family, version, resource kind,
payload — written with `pr-str`, so the bytes are still self-describing and a
reader still does not have to know in advance which surface it is holding.

The reason is what plain JSON cannot carry. `:sheets/type` left as
`"workbook"` and a cell address `[1 1]` left as the string `"[1 1]"`, so
every reader had to put them back — which is why there are four
`rehydrate-*` functions and why each had to learn not to throw on input it
could not convert. None of that is needed at rest: EDN is what the models
already are, what every validator reads, and what `store.clj` already writes
for the rest of this app's state.

**Rehydration did not go away; it moved to the one place it belongs.** A
payload arriving over HTTP is JSON because HTTP is, and `update!` converts it
on the way in. Nothing converts on the way out.

The client contract did not move with the storage. `content` returns
`:payload` as the same plain-JSON projection the editor has always been
given — `transit.core/write-json` of the EDN — alongside `:resource`, the
EDN itself, for callers inside this process.

Documents written before this are JSON. `decode-stored` tells the two apart
by their first character and rehydrates a JSON one on read, so an old
document reads as it always did and the next save rewrites it in EDN.
**Migration is what the Drive does as it is used, not something anyone runs.**
An item's `:drive/media-type` is corrected by that same save, so it says
`application/edn` once it is.

### Editing

Two views of one value. The Drive detail pane offers fields for the surface a
document is — questions for a form, blocks for a document, a cell grid for a
workbook — and the JSON underneath for everything the fields do not reach.
Both mutate the same projected payload, which is the object the versions
endpoint accepts, so a save does not care which produced it and neither is a
parallel format that can drift.

The vocabularies those fields offer (`forms.model/field-types`,
`docs.model/block-kinds`, `slides.validate/shape-kinds`) travel from the
libraries through `documents/kinds` to the page, so the editor offers
exactly what the validator accepts.

Slides is the one surface whose validator does not take the resource.
`slides.validate/problems` takes a *workspace* — it looks for items whose
kind is `:slides/deck` — and it also runs `route-problems`, which reports an
error for each of four Pages hosts it cannot find. That is a question about
the slides website, not about this document, and asking it here would refuse
every save. So the deck is wrapped in a workspace of its own and only
`deck-problems` is asked. Two things it cannot reach — a `docs` table or list, a
workbook with no tabs — say so and hand over to the JSON view rather than
editing part of a structure and leaving the rest.

These are not `app-sheets`, `app-docs` and `app-slides`, which are separate
applications on their own origin. Reaching those would mean widening
`connect-src 'self'` in the page CSP, which is a decision about what this app
may talk to and not one to make as a side effect of adding an editor.

### References between documents

`docs.model` has had `:table-ref`, `:file-ref` and `:deck-ref` blocks all
along, each carrying a `:docs/target` string, and nothing ever resolved one.
Four surfaces sharing a pane is not the same as four surfaces that know about
each other; this is the difference.

A target is a Drive item id — not a URL and not the `slides:intro-deck`-style
scheme the seed document in `docs.model` uses, which is a placeholder rather
than a format anything parses. An id is what `documents/locate` already
resolves, so **a reference obeys the same permission answer as everything
else**: a link to a document you may read resolves, and a link to one you may
not is indistinguishable from a link to nothing. Backlinks are filtered the
same way, so an incoming reference never tells you a document exists that you
were not shown.

Dangling and mistyped references are save-time **warnings**, not errors. A
document being written may name something that is about to be shared, and
`docs.model` names the kinds without saying a `:table-ref` must be a
workbook, so pointing one at a deck is reported and not refused.

The check belongs to the app rather than to `docs.validate`: the validator
sees a target string and has no way to know whether it names anything,
because what it could name lives in a Drive it does not know about.

Only `docs` documents carry references today. A workbook has no block that
names another document, and a deck's links live on a `slides` *workspace*
rather than in the deck — the envelope carries one deck, so there is nowhere
in it for a link to sit.

### Comments

`:commenter` was a grantable role backed by nothing — `can-write?` excludes
it, so a commenter could read and do nothing else, which is `:viewer` with a
longer name. Comments are what it means.

They are kept beside the document rather than in `docs.model`'s
`:docs/comments`, and the reason is a boundary rather than a convenience. A
comment written into the resource is a write to the document, and
`drive.workspace` says a commenter may not make one — correctly, since a
commenter who could rewrite content would be an editor under a quieter name.
The alternative is to perform that write as somebody who may, and since
`:drive.version/author` now records who wrote each version, that would file a
comment under the wrong name in the one record that says who changed what.

The costs are real: a comment does not travel with the exported envelope, and
`docs.validate`'s comment checks never see it. If comments must travel with
the bytes, the fix is a constrained-write operation in `drive` that a
commenter may reach — not a workaround here.

Anyone who may read a document sees its comments; anyone above `:viewer` may
leave one; its author or the document's owner may delete it. An editor may
rewrite the document and still not delete what somebody said about it.

### Answering a form

A form is the one surface with a second thing to do to it. Editing changes
the questions; answering does not, and an answer is not a version of the
form — writing one into the stored envelope would charge every response to
the owner's quota and change the document every respondent is reading from.
So submissions are kept beside the document, in app state, keyed by its id.

Whoever may read a form may answer it, including through a share link:
requiring write access to submit would make every respondent an editor of
the questions. The answers belong to the owner, and only the owner reads
them — an editor may change the questions and still not see the responses.

`forms.validate/submission-problems` is what refuses one, on a **rehydrated**
form. Against a projected payload `missing-required` reads `:forms/fields`,
finds nil, and reports that nothing is required, so an empty submission would
pass. There is a test asserting exactly that difference.

### Sharing

Each principal has their own `drive.workspace`, and a grant is recorded on the
item — which lives in the granter's workspace. So a grantee looking only at
their own Drive would be told the document does not exist, and a grant nobody
can act on is a button that does nothing. `documents/locate` is what closes
that: own Drive first, then a scan of the others for an item this principal
has a role on.

- **A version says who wrote it.** `:drive.version/author` is the principal
  `drive.object/write-item` checked against the ACL, not a value this app
  passes in — an author the caller names is a history the caller can write.
  Before sharing this was redundant; with two possible writers a history that
  cannot distinguish them is a history of nothing.
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

## MCP surface

`cloud.itonami.app.mcp` serves the fleet capability's two tools — `fleet_search`
and `fleet_call` — over MCP on **stdio**, launched as `clojure -M:mcp`. It is an
adapter: `cloud.itonami.app.fleet` already owns the descriptors and behaviour for
the in-app agent loop, and this translates them for a client that is not that
loop. `mcp.model` holds the manifest, `mcp.execute` does the JSON-RPC dispatch,
and an `ITool` port calls the same two functions.

Stdio rather than a route on the loopback server: `/v1/*` is already the one
unauthenticated exception the loopback bind exists to protect, and an MCP route
would be a second. Over stdio the client is a process the operator launched, so
nothing new listens and the trust boundary is one they already set.

The fleet capability gate is honoured, so `tools/list` is empty until it is
enabled — the same fail-closed default as the other agent capabilities. Browser
and computer tools are excluded because their approval path verifies the
frontmost application between approval and action, which cannot survive a
protocol whose consent model belongs to the client; the workspace reads are
excluded because they sit behind the Passkey session. See ADR-0004.

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
3. Add an MCP **client** profile. (The server half exists for the fleet
   capability: `cloud.itonami.app.mcp` on stdio — see below and ADR-0004.)
4. Add memory distillation and relevance retrieval over kgraph.
5. Add schedules/watchers after tool isolation is available.
6. Add a function-call compatibility suite. (Streaming has one:
   `test/cloud/itonami/app/openai_compat_test.clj` reads the SSE frames over
   real HTTP, in both modes.)
