# ADR-0062: A cross-machine handle names a device, and authority stays where it is

**Status:** decision core landed, host integration pending — 2026-08-19

## Context

Hermes lets a person address a Bot on another of their machines, and
disambiguates identical names with a `@name-device` handle. This application
had nothing of the kind, and ADR-0036 looks at first like the reason:

> Closing a laptop still stops every Bot. That is the cost of the thesis, not
> a defect to patch with a cloud VM.

Read as "one machine, ever", that sentence forbids this. Read for what it
actually defends, it does not. What ADR-0036 refused was a **shared persistent
cloud PC** — a place where the work happens that the fail-closed policy does
not reach, so that reviewing the chat window reviews nothing the Bot did. A
second machine the same person owns, running this same reviewed application, is
the opposite of that: it is somewhere the policy **is the one running**.

The distinction is not who owns the hardware. It is whether the effects happen
under a policy somebody reviewed. So the question this ADR answers is not "may
a Bot run elsewhere" — it always could, on that person's other install — but
"may a Bot **here** name one **there**, and what is allowed to cross when it
does".

## What already existed

Almost all of it, which is why this is small.

- `messenger/register-device!` already registers devices **per principal**,
  with Signal public material, inside an organization. A device is a
  first-class registered thing, not a string somebody types.
- A Bot is already a durable non-human principal with an address, `bot:<id>`
  (ADR-0061).
- ADR-0016 already refuses to let an untrusted sender's mail become model
  context.
- `peer_core/may-approve?` already refuses an agent outright.
- ADR-0026 already calls memory **device-local** and means it.

## Decision

**`bot:<id>@<device>` names a Bot on another of the owner's registered
machines. `bot:<id>` means this one.** A blank device is the local form, so a
caller that does not know about devices writes what it always wrote.

What crosses the boundary is **a note**. What does not cross is everything
that could make something happen over there:

| | |
|---|---|
| grant | `->pair` and `->reach` have no field for one. The absence is the mechanism. |
| approval | `peer_core/may-approve?` is `false` for an agent. A remote Bot's message can never answer a card here. |
| memory | `foreign-memory?` already says a Bot reading another's is reading someone else's; a device boundary does not soften it. |
| effects | A remote Bot's writes hold on **its own** approval cards, under **its own** grants, on **its own** machine. Nothing here decides what may happen there — and `:peer/reach` deliberately has no field describing the remote policy, because a record with one would invite this side to reason about it. |

`may-address?` decides four things in this order: same owner, target enabled,
**is it this machine**, and only then whether the device is registered and the
deployment permits remote addressing.

The ordering is load-bearing twice.

- `device-is-local` is asked before `device-known` because the machine you are
  on is known by being the machine you are on. The first version asked them the
  other way round and the parity corpus caught it: the host never produces
  `device-known false` with `device-is-local true`, so the core was refusing a
  combination the host could not hand it — a trap for the next host rather than
  a rule.
- The deployment switch is read **last, and only on the remote branch**, so
  turning remote addressing on cannot rescue a handle that failed anything
  before it, and turning it off cannot stop a person addressing the Bots on the
  machine they are sitting at. That second failure would have looked like the
  Bots being broken.

**An unregistered device is not addressable.** There is no discovery step. A
handle is not a guess, and a message that reached a machine nobody enrolled
reached somewhere nobody can name.

**Liveness is stated honestly, not preserved by pretending.** ADR-0036's
sentence becomes: closing a laptop stops **that laptop's** Bots. A person with
two machines has Bots on two machines and both facts are visible in the handle.
What is unchanged is that no Bot runs anywhere its owner has not enrolled and
reviewed.

## What is landed, and what is not

**Landed:** `may-address?` and `reaches-another-machine?` in
`peer_core.kotoba`, the `:peer/reach` record, the address grammar
(`peer/address`, `peer/parse-address`), the oracle rows, and the compiled
artifact. The full 32-row truth table is compared between the guest and the
host, and the grammar is checked against nine malformed forms.

**Not landed:** every host caller, for the same reason as ADR-0061 — the peer
plane has no host integration yet, and building a device-aware one on top of an
absent one would be building on nothing. There is no remote transport, no
`remote-enabled?` setting on any screen, and no delivery. `may-address?`
answers a question nothing asks yet.

`reaches-another-machine?` exists separately from `may-address?` on purpose:
"that Bot is on your other machine" and "there is no such Bot" are different
things to tell a person, and a host that only had the yes/no could tell them
only the second.

## Verification

- The full 32-row truth table of `:peer/reach` is compared between the guest
  and the host, with the host driven through `->reach` rather than fed the same
  booleans — a parity test that handed both sides the record would agree even
  if the derivation were wrong.
- That is not hypothetical: it is how the first branch ordering was caught.
- `a-handle-for-another-persons-bot-is-refused-however-it-is-configured` sweeps
  the sixteen configurations where the owner differs.
- `the-remote-switch-cannot-turn-off-the-machine-you-are-sitting-at` sweeps
  every local-handle row, because that failure would have looked like the Bots
  being broken rather than like a switch.
- `an-unregistered-device-is-not-addressable` sweeps the remote rows with no
  registration.
- The grammar round-trips and returns nil for nine malformed forms.

Not verified, and unverifiable today: that a note actually reaches another
machine. There is no transport. `send_message` refuses a `@device` handle with
`no-remote-transport` rather than delivering locally, and that refusal is
tested — which is the honest end of what exists.

## Consequences

- The `@name-device` shape becomes available without a cloud VM, without a
  shared computer, and without moving a single grant.
- The reverse direction is now askable and is deliberately unanswered here:
  whether a Bot on the other machine may address one on this one is that
  machine's `may-address?`, evaluated there, against its own registrations.
  Symmetry is not assumed.
- The device string is a name in the messenger plane. It is not a hostname, not
  a URL, and nothing here resolves it to one; a transport that needs an address
  will have to say where it got it.
