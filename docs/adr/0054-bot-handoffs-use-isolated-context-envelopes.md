# ADR-0054: Bot handoffs use isolated context envelopes

Status: accepted and implemented

## Context

A Bot conversation was durable, but provider context was rebuilt from whatever
the whole conversation contained when a run started. That had two distinct
problems.

First, there was no durable answer to “which facts did this direction send to
the model?”. A later message, a recovered Goal, and a handoff all read the same
ambient transcript through different timing paths. The visible direction
number scoped approvals, but did not scope model input.

Second, `hand-off!` appended a task to the target and ran that Bot once. The
target's unrelated prior conversation entered the request, its answer remained
only in the target thread, and no durable exchange record said whether the
source received it. This looked like two Bots talking in the UI, but was a
one-way invocation.

## Decision

Every accepted person direction gets an immutable, bounded context envelope.
The envelope records its Bot, direction, source, creation time, and at most 40
text messages. Its projection is allowlisted: message text and provenance may
enter; cards, credentials, and tool results do not. Workspace identity is
classified as a local path, while credentials and tool results are explicitly
classified as excluded. At most 120 envelopes are retained.

Normal directions snapshot the bounded conversation, preserving follow-up
continuity. Runs and visible turns carry the envelope id, so recovery continues
from the same provider input instead of rebuilding it from newer ambient
state.

A handoff gets a different envelope shape. The target envelope contains only
the attributed handoff task—not the target's earlier conversation. After the
target answers, the host writes that attributed result into the source thread,
creates a second isolated envelope, and gives the source one synthesis turn.
The two-round exchange is stored as a handoff run with both context ids,
timestamps, state, and round count.

Two rounds are deliberate. An unbounded “keep talking until the models agree”
loop lets model prose allocate model calls and can cycle. The existing handoff
depth ceiling still bounds chains; this ADR additionally bounds one edge to a
target response and a source synthesis. A restart closes an in-flight exchange
as interrupted rather than replaying either Bot's possible effects.

The target always uses its own tools, accounts, workspace, provider admission,
and approval rules. Context provenance transfers; authority does not.

## Surfaces

- Browser: `POST /api/bots/{from}/handoff`
- Agent session: `POST /api/agent-bots/{from}/handoff`
- CLI: `itonami bots handoff --from … --to … --task …`
- MCP: `bot_handoff`

All four reach the same host function. Agent sessions may already submit work
to either owned Bot; the new route cannot create a Bot, change its grant, or
approve a write.

## Verification

The host test seeds unrelated text in the target's prior conversation and
asserts it is absent from the delegated provider request. It then asserts that
the target result enters the source provider request, the source produces the
terminal answer, both public transcripts expose one handoff id and context
ids, and the durable run finishes after exactly two rounds.

The mutation gate removes the isolated target message selection. The named
test must turn red by observing the target's private prior history in the
provider request; otherwise the context-isolation claim has no load-bearing
test.
