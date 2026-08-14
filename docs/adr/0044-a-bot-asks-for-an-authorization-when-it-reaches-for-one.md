# ADR-0044: A Bot asks for an authorization when it reaches for one

Status: accepted and implemented

## Context

ADR-0034 gave a Bot a grant, an account binding and a connection card. The host
resolved all of it before the turn: `send!` called `resolve-accounts`, and if
any provider the Bot's grant touched had no live account, it said

```
先に接続が要ります。Google Calendar・Google Drive・Gmail を認証してください。
```

and returned. No model call.

The reasoning was sound and is quoted in the code it produced: "asking one to
plan around a service nobody authorized produces a plan that cannot run, and
the person then has to work out which step was fiction."

The implementation asked a different question than the reasoning did. It asked
*does this Bot's grant touch anything unauthorized*, which is a property of the
Bot; the reasoning is about *does this turn need something unauthorized*, which
is a property of the turn. Those differ on every turn that needs no tool.

Measured 2026-08-14 on the resident app: a Bot created from the Gmail template,
on a machine where Google had never been authorized, answered `先に接続が要り
ます` to every message — including "aa". The transcript was a column of
identical connection cards. Nothing was wrong with the Bot, the grant, or the
machine. The person could not ask the Bot what its brief was, what it could do,
or to wait until tomorrow, because the only sentence it could say was a demand.

A second defect compounded it. Nothing ever rewrites a stored card's
`:card/state`, so a card written while Google was unauthorized said `:offered`
for the life of the conversation. `presence` read that literally, so
`unmet-connection?` stayed true forever: a Bot whose Google was authorized ten
minutes ago still reported `waiting-connection`, and the transcript still
rendered an enabled 認証する button for something already done. The screen went
on asking after the answer had been given.

## Decision

**The connection demand is a consequence of reaching for a tool, not a
precondition for talking.**

A turn is taken. `advance!` checks admission at the call, in the same place and
the same shape as the write-approval hold that was already there:

1. the tool's provider is unresolved — connect or ask — so the run is cleared
   and the card is said;
2. the tool is not runnable, so it is refused by name;
3. the tool writes, so it is held for approval (unchanged);
4. otherwise it runs (unchanged).

Order 1-before-2 is a decision, not a sequence. A provider can be connected —
so its tools are admitted — while *which* of two accounts to use is still
ambiguous. Running then resolves no token and reaches nothing, and taking the
first account is the failure `connection-for` refuses one layer down.

**Offering a tool is not granting it.** `bot/reachable-tools` asks the same
`bot_core.kotoba` admission as `admitted-tools`, over the same four booleans,
with `connected` held true. It is still narrowed by the deployment's enabled
set, by the grant and by the write permission. That set is what the model may
reach for; `admitted-tools` remains the only set that may run. The core is
unchanged — the difference is which question the host puts to it.

A row with no `:provider` is not reachable: no authorization would ever connect
it, so offering it would put a tool in front of the model that nothing can
grant. A row with a provider but no client configured on this machine stays
reachable, because that Bot really is blocked on an authorization; the card
appears with its button disabled, which is ADR-0034's `:authable?` doing its
job.

**A card's state is recomputed, not replayed.** `met?` answers from the
provider's live accounts, for the same reason `public-card` already recomputes
`:authable?`: whether a connector is connected right now is not something the
conversation said. The stored card is left as the record of what was true when
it was offered.

## Consequences

- A Bot with an unauthorized connector holds an ordinary conversation. The
  authorization is asked for on the turn that needs it, naming the service.
- The model is asked on turns it was previously not asked on. That is the cost:
  one turn is spent discovering that the turn needed Google. The loop stops at
  the call rather than recurring, so it is one turn, not a budget.
- A blocked call is cleared rather than held. An approval card can resume,
  because the person's answer is the last thing the call was waiting for; an
  authorization is a round trip through a browser and another provider, and a
  run parked across it would resume from a transcript written before it. The
  person asks again, of a Bot that can now do it.
- Routines are unchanged and still refuse up front: `routine/admitted-steps`
  reads `connected-connectors`, and a scheduled routine has nobody watching to
  answer a card. The asymmetry is deliberate — it is the difference between a
  person at the screen and a timer.
- Three run-builders — a message, a routine and a handoff — assemble the same
  facts through `turn-admission`, so `advance!` cannot be reached with two of
  them and not the third. The handoff path previously supplied no connection
  facts at all and simply ran with fewer tools.
- Every route returning messages goes through `public-conversation`, so the
  recomputation cannot be had by some callers and not others.

## Verification

`clojure -M:test` — 1447 tests, 8656 assertions, 0 failures, 0 errors.
Baseline on the parent commit `e073007` was 1443 / 8642, also clean.

Four tests were added and six changed. The changed ones are the evidence:

- Three (`two-accounts-at-one-provider-are-asked-about-rather-than-guessed`,
  `a-card-does-not-offer-an-authorization-this-machine-cannot-perform`,
  `a-stored-card-reports-the-client-this-machine-has-now`) asserted that the
  card arrived *without ever asking a model*. That is the defect written down
  as a guarantee.
- `an-archived-bot-keeps-its-conversation-and-stops-working` sends "おはよう"
  and never stubbed a provider — it did not need to, because the gate answered
  every message without one. It errored with `:provider/denied` on the first
  run of this change, which is the clearest single measurement that the gate
  was answering greetings.
- `a-bot-bound-to-one-account-does-not-inherit-the-other` read `(:ask resolved)`
  from a map that no longer has that key. `(empty? nil)` is true, so it would
  have gone on passing while checking nothing. It reads `:blocked` now.

One fixture bug was found the same way and is worth recording, because it
produced a failure that looked like a defect in the code: the `reaches-for`
stub counts turns, and one instance shared between two `send!` calls answers
the second with no tool call, so no card appears. A stub with state needs to be
fresh per `send!`.
