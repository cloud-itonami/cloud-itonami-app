# ADR-0061: Bots are persistent peers — memory isolated, computer shared

**Status:** decision core, peer notes and the group room landed, each with a
screen; the shared computer and the ADR-0036 reversal **not taken** —
2026-08-19. Written 2026-08-14 as ADR-0042; renumbered because
0042 was taken on `main` by hosted-signin before this landed.

## What is on `main`, and what is not

**Landed here:** `peer_core.kotoba`, `peer.cljc`, the parity corpus, the oracle
rows and the compiled artifact. The four judgements — `may-message?`,
`computer-shared?`, `foreign-memory?`, `may-approve?` — run through
`kotoba-oracle` and are exercised over their whole truth tables.

**Landed 2026-08-19:** `send_message`, behind a new `:bot/peers?` permission
that is off by default and settable only from the human surface. A note lands
in another of the owner's Bots' conversations, attributed with the sender's
`bot:<id>` address, and is read on that Bot's next turn. `transcript` renders
the attribution rather than merging a peer into the person's voice — a model
that read another Bot's note as its owner speaking is the shape in which a
permission system is defeated without looking like delegation. Both Bots must
be opted in: a Bot nobody opted in is not a mailbox.

**It does not wake the target, and that is a decision rather than a missing
stage.** Waking one needs the isolated envelope and run lifecycle `hand-off!`
already owns, and a Bot that wants something DONE should hand off — a handoff
is bounded at two rounds with a depth ceiling, while a note that woke a peer
that answered with a note would be an agent loop with neither.

**The group room landed separately** as ADR-0063, and deliberately without the
rest of this: a room needs no shared computer, no per-Bot screen and no cookie
sharing, so it did not wait on the decision below.

**Not landed:** the shared computer. No shared browser profile, no per-Bot
screen, no mailbox trust on the messenger plane. `computer-shared?` answers the
question and nothing asks it; a Bot still gets its own profile, as ADR-0036
decided. **This is the one thing here that is a person's call rather than an
engineering one**, because it means one Bot's sign-in becomes another's.

The remainder was written against a `bots.clj` that `main` has since rewritten — durable turns, directions, handoff runs, goals and workforce all
landed in the same functions — and reconciling ~600 lines of one design against
~700 of another, in the file that decides tool admission, is a re-integration by
whoever holds the intent rather than a merge. Its branch is
`agent/grok-bots-peer-computer` (PR #75).

**Explicitly NOT taken by this commit:** the reversal below of ADR-0036's
cookie and login isolation between same-owner Bots. Today a Bot still gets its
own browser profile. That reversal is a security decision and belongs to a
person, not to a port; `computer-shared?` answering `true` for two Bots of one
owner is a judgement nothing yet consults.

**Relationship to ADR-0060:** `peer_core/may-approve?` still returns a flat
`false` for an agent, and that is not an oversight. ADR-0060 lifted a different
refusal — whether an actor may decide a card on the Bot it is acting as. This
one asks whether a message that arrived FROM ANOTHER BOT may stand in for that
approval, and no delegation makes it true: a person delegates to one Bot, not
to whatever that Bot is willing to relay.


## Context

The context below is as written on 2026-08-14.

ADR-0034 made a Bot a named durable teammate. ADR-0036 dispatched
`:bot/browser?` onto this machine's isolated browser and **chose isolation**:
`call-browser-tool!` bound `*browser-session*` to `(session-for bot-id)` so
two Bots would not share cookies. Handoff (`hand-off!`) stayed Hermes-shaped:
a bounded work-transfer that injects a task into the target's 1:1 and
immediately `advance!`s, with a depth ceiling of 4, and with grants kept out
of the record.

xAI's Grok Bots public semantics (docs.x.ai/grok-bot, 2026-08-14) are a
different shape. Named Bots are **persistent peers**, not parent/subagent.
They coordinate by **direct message and group chat**. Cognitive state is
per-Bot; the **computer is shared** (files, browser sessions, logins), with
each Bot assigned a **screen** on that computer so they can operate in
parallel. Handoff that a person should see goes in a **group chat**.

The internal protocol (A2A, NATS, actor mailboxes) is not published. This
ADR copies the **visible semantics**, not an invented wire.

What this application already refused, and still refuses:

- A Bot's computer is **this machine**, not a cloud VM. Closing the laptop
  still stops every Bot (ADR-0034/0036 thesis).
- A Bot cannot approve. An agent session cannot approve.
- Grants do not cross. Absence of grant fields is the mechanism, on handoff
  **and** on a peer message.
- Chronicle remains person/device-local. A Bot does not read a person's
  Chronicle, and another Bot does not read this Bot's memory.

What ADR-0036 chose that this ADR reverses: **cookie/login isolation between
same-owner Bots**. That was the opposite of the Grok shape, taken to prevent
one Bot's sign-in from becoming another's. The cost was handoff of logged-in
work. The Grok asymmetry is the better trade: **cognition stays isolated,
the work environment is shared**, inside one person's account.

## Decision

Same-owner Bots are persistent peers.

| Plane | Boundary |
|---|---|
| Identity | named, durable, per Bot (`bot_core`) |
| Memory | per Bot, not Chronicle, not another Bot's |
| Conversation | per Bot 1:1 with the person; peer traffic is mailbox |
| Computer | **one per owner** on this machine |
| Screen | **one per Bot** on that computer |
| Coordination | mailbox DM / group (`messenger`), distinct from `hand-off!` |
| Grant | never crosses (no field to copy) |
| Approval | still never an agent (`peer_core/may-approve?` restates it) |

`hand-off!` remains the bounded, depth-limited work-transfer. A peer message
is not a chain hop and does not increment handoff depth. A Bot that is busy
receives the mail and is not woken; an idle Bot may be woken, capped by a
host `*peer-depth*` of 2 so two peers cannot ping-pong a model all afternoon.

Mailbox address form: `bot:<bot-id>`. Same-owner Bots auto-trust each other
and their owner so a peer DM is not quarantined. Untrusted senders still
cannot become model context (ADR-0016).

Decision core: `peer_core.kotoba`, native word-typed, through `kotoba-oracle`. (This part is landed.)
The record `:peer/pair` has no tool, grant, task text, or depth. Judgements:

- `may-message?` — same owner, both enabled, distinct Bots
- `computer-shared?` — same owner
- `foreign-memory?` — distinct Bots (refuse reading the other's memory)
- `may-approve?` — identical refusal to `handoff_core`: an agent never

Internal protocol is not claimed. The user-visible semantics are DM, group
chat, shared computer, isolated memory.

## Verification

Suite at landing: 1674 tests, 10047 assertions, 0 failures; no new lint
findings.

- The four judgements run through `kotoba-oracle` over their whole truth
  tables, compared guest-against-host, and the host is driven through its own
  derivation rather than handed the same booleans on both sides.
- `a-note-tool-appears-only-for-a-bot-opted-into-peers` was shown to go RED
  when the `:bot/peers?` gate is removed, and so was the repository's own
  goal-parallelism test — which is the check that the gate really controls what
  the model sees rather than only what a settings screen draws.
- Every refusal has its own test and its own message: self, unknown name,
  ambiguous name, a target that never opted in, an empty note, another
  person's Bot (`not-found`, never `forbidden`, because `forbidden` confirms
  it exists), and a `@device` handle (`no-remote-transport`, never a silent
  local delivery).
- `every-permission-a-bot-has-can-be-switched-on-from-the-screen` fails if the
  checkbox loses its id. It exists because this capability landed WITHOUT a
  control and was, for one commit, ungrantable by the only party allowed to
  grant it.

Not verified: no end-to-end run of two live Bots exchanging a note through a
real provider. What is proven is admission, delivery, attribution and every
refusal — not what a model does with a note once it reads one.

## Consequences

- ADR-0036's "this machine, writes hold, computer-use stays off the Bot
  path" stands. Its **per-Bot cookie isolation** is superseded here for
  same-owner Bots. Different owners still get different computers.
- `AGENT_BROWSER_SESSION` is the owner's computer. `AGENT_BROWSER_SCREEN`
  is the Bot's screen. If the browser host ignores the screen variable, the
  binding is still the semantic; parallel display is best-effort.
- Shared files live under `computers/<owner>/files` in the data dir.
- A `send_message` tool is internal coordination (not a connector write) and
  does not hold. The recipient's writes still hold.
- No cloud VM is introduced. Always-on is still not a property of a Bot.
