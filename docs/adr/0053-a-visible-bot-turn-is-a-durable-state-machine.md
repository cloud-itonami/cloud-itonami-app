# ADR-0053: A visible Bot turn is a durable state machine

Status: accepted and implemented

## Context

The conversation was durable and the execution was not. A person could send a
direction, wait without receiving a model token, and have the resident process
restart. The person message survived, but `active-turns` was an in-memory atom
and no run was stored until a tool had run or a write was held. On the next
start the Bot looked idle and had no truthful answer to "what happened?".

Measured 2026-08-15: one coding direction was stored at 17:09, the resident
process started again at 17:52, and the conversation contained no Bot answer
and no resumable run. A second direction at 17:54 was also invisible in the
thread while its request was in flight because the client appended only an
empty provisional Bot bubble. The status line counted seconds under the label
"thinking" even though the server emitted a `phase=model` frame which the
client ignored.

## Decision

A visible streamed Bot turn has a bounded durable lifecycle, separate from the
conversation and from a resumable approval run.

Each record is keyed by the client-generated run id and carries the Bot,
direction, state, phase, optional tool name, and timestamps. States are
`running`, `completed`, `failed`, `cancelled`, and `interrupted`; phases are
`accepted`, `model`, `tool-proposed`, and the terminal state. History is capped
at 40 records per Bot. It does not duplicate the person's text, transcript, or
tool output.

Before a streamed turn enters the model loop the host persists `running /
accepted`. Live phase events update the active turn in memory and the response
stream; every exit persists the terminal state and last observed tool. This is
deliberately two durable writes per normal turn: the application store is a
single atomic snapshot, so rewriting it at every model/tool boundary would make
progress reporting itself a latency source. During server start, before Bots
accept new work, every record still marked `running` is closed as `interrupted /
server-restarted`: the new process has no thread that could own it, so reporting
it as idle would be false.

The API exposes only the latest bounded record on the Bot overview. The client
renders the person's message immediately, consumes real phase frames, and
reconciles its optimistic thread from the server after a request failure. An
interrupted or failed last turn remains visible when the Bot is selected.

This does not attempt transparent resume. A provider stream and its local HTTP
body cannot be reconstructed after process death. Durable recovery means a
truthful terminal fact from which the person can retry, not replaying an effect
whose completion is unknown.

## Consequences

- Conversation text no longer has to impersonate execution state.
- Restart recovery is deterministic and idempotent.
- The UI distinguishes accepted, model wait, proposed tool, response, failure,
  cancellation, and restart interruption instead of calling all silence
  "thinking".
- This is not Bot-scoped semantic memory and does not yet resolve an ambiguous
  coding direction to one west project. Those require a separately admitted
  context envelope and per-field data classification.

## Verification

The host tests cover a successful model/tool/model turn, provider failure, and
restart recovery. The web-source test fixes phase consumption and immediate
person-message rendering as shipped JavaScript invariants. The new recovery
test is mutation-checked by changing the running-state predicate and observing
it fail before restoring the implementation.
