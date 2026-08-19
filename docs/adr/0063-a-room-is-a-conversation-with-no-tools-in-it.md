# ADR-0063: A room is a conversation, and there are no tools in it

**Status:** accepted — 2026-08-19

## Context

ADR-0061 named group chat as the coordination shape Grok Bots and Hermes both
have, and did not build it. What it built was the mailbox: since 2026-08-19 a
Bot can leave another one an attributed note that is read on its next turn, and
deliberately cannot wake it.

A note is not a room. The thing a person actually wants when they have four
Bots is to ask all of them at once and watch them answer each other — Hermes
describes it as up to three serial rounds where a member replies only when it
has something new and passes otherwise.

The obvious way to build that is to let a room turn be an ordinary Bot turn.
That is the version this ADR refuses.

## Decision

**A group is a durable room over Bots the session already owns, and a group
turn has no tools at all.**

Membership resolves through `owned!`, one member at a time, so a room cannot
become a way to name a Bot the session could not otherwise reach. Members are
kept in insertion order and deduplicated — a member listed twice would take two
turns per round and nothing downstream would notice.

**No tools, and that is the decision rather than a stage that is missing.**
Admission in this application is per Bot and decided at the call. A room where
eight Bots each reach for a connector is one sentence turning into eight
approval cards, and a person who wanted an answer is now doing paperwork. Worse,
it is the one place where "ask the Bot that holds the tool" would work by
construction — the failure `hand-off!`'s docstring already names as the thing
that would make every per-Bot grant advisory.

So: a Bot in a room can say who should do something. It cannot do it. Getting
it done is asking that Bot directly, or a handoff — which is bounded at two
rounds with a depth ceiling, neither of which a room has.

**Three rounds, and a round nobody answers ends it.** The ceiling follows the
shape Hermes describes and is finite for the reason every budget here is
finite: the alternative is model prose deciding when an agent loop stops. The
early exit is what makes three a ceiling rather than a schedule.

**Eight members.** One message costs one model call per answering member per
round, so an unbounded room is an unbounded bill arriving from a chat window.
Eight is the largest number where three rounds still reads as a conversation.

**Every line is attributed, in the room and in each member's transcript.** A
member sees its own lines as `assistant` and everyone else's as `user` prefixed
with their address. A model that read another Bot's line as its owner speaking
is the shape in which a permission system is defeated without looking like
delegation, and a room is where eight of them are speaking at once.

**Reachability is asked per member per round**, through `peer/may-address?`
(ADR-0062), not once at the start. A Bot disabled while the room is mid
conversation stops answering at the next question rather than at the next
message the person sends.

**The room is its own conversation.** It is not smeared into each member's 1:1,
because ADR-0061 already says that plane is the person and this Bot.

## Who may open one

A human passkey session, through `/api/bots/groups`. Not the agent surface: a
room is a person asking their Bots something, and `/api/agent-bots` exists so an
agent can submit work, not convene one.

The four routes are gated at the handler boundary **and** explicitly in each
route body. That is not redundancy. Measured while adding them: with the gate
only at the boundary, `route-scan` read all four as **unauthenticated** and the
command registry — which is what an audit reads — recorded them that way. They
were never reachable without a passkey; the record of them was wrong, which on
that surface is its own defect. `a-group-room-is-a-human-route-in-the-registry-too`
fails if it happens again.

## Verification

Six host tests and two surface tests, driven by a model stub keyed on the
system prompt — so a room that sent every member the same prompt fails here
rather than passing by looking plausible.

- Every member takes a turn and every line carries its speaker's address.
- A round nobody answers ends the room, and a pass is not recorded as speech.
- The ceiling bounds the model calls. `a-room-stops-at-three-rounds-however-talkative`
  was shown to go RED when the bound is changed to five. **Removing the bound
  entirely does not fail it, it hangs** — the same fact stated worse, which is
  why the demonstration used a different bound rather than no bound.
- The request carries no tools, asserted on the request the provider receives
  rather than on the code that builds it.
- A member disabled mid-conversation stops answering at the next question.
- A room cannot name a Bot the session does not own, and a refused room is not
  stored.
- `a-room-is-reachable-and-says-what-it-cannot-do` fails if the pane loses a
  control or stops saying that a room has no tools.
- `the-rooms-view-is-loaded-when-it-is-opened` fails if the dispatch is
  removed. It exists because nothing else caught that: removing it fails only
  the published-lock tests, and those fail for any byte change at all, which
  makes them a notification that something moved rather than a check that this
  still happens.

Not verified: no browser test drives the pane. The assertions are over the
rendered document and the interaction source, not over a running page.

## Consequences

- The coordination shape exists without the shared computer, the per-Bot screen
  or the cookie sharing of ADR-0061 — none of which this needs, and all of
  which remain the owner's to decide.
- A room cannot do anything. That is a real limit and people will hit it: the
  answer is that the Bot named in the room is one message away, with its own
  grants and its own approval card.
- There is no UI. The rooms are API-only, and `main`'s Bots view has moved
  twice this month; adding a pane on top of a layout in motion is how the
  earlier peer branch ended up unmergeable.
