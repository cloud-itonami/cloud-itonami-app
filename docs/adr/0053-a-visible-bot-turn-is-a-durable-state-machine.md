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

### Goal execution addendum

A person may mark one direction as a Goal. In that mode, a prose capability
statement is progress rather than a terminal answer. The host continues the
bounded model/tool loop until the model calls one of two host-owned terminal
tools: `goal_complete` with a summary and concrete evidence, or `goal_blocked`
with the exact external prerequisite. The ordinary chat path still terminates
on prose. Completion additionally requires at least one admitted tool to have
actually executed, so evidence-free capability prose cannot be relabeled as
success. Goal runs have a larger but finite ceiling of 24 model turns and 32
tool calls; admission, approval, cancellation, and isolated-workspace rules do
not change.

The lifecycle record adds the objective, provider/model, accumulated provider
token usage, tool/turn counts, result, and evidence. It deliberately does not
invent a price: current chat providers return token usage but no billed amount,
so the UI reports the fee as not calculated. The mobile run card shows the
live phase, elapsed seconds, tool count, tokens, and the last durable terminal
fact. This follows the useful observable pattern in the installed Anysphere
`Grok Bot.app`—progress, elapsed time, commands, and token usage are distinct
from chat text—without adopting that application's authority model.

## Consequences

- Conversation text no longer has to impersonate execution state.
- Restart recovery is deterministic and idempotent.
- The UI distinguishes accepted, model wait, proposed tool, response, failure,
  cancellation, and restart interruption instead of calling all silence
  "thinking".
- Goal directions cannot terminate with “I can help”; they end only with an
  evidence-bearing completion claim, a concrete blocker, cancellation, or a
  finite budget.
- This is not Bot-scoped semantic memory and does not yet resolve an ambiguous
  coding direction to one west project. Those require a separately admitted
  context envelope and per-field data classification.

## Verification

The host tests cover a successful model/tool/model turn, provider failure, and
restart recovery. The web-source test fixes phase consumption and immediate
person-message rendering as shipped JavaScript invariants. The new recovery
test is mutation-checked by changing the running-state predicate and observing
it fail before restoring the implementation.
