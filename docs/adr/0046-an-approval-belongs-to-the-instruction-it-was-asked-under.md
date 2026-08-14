# ADR-0046: An approval belongs to the instruction it was asked under

Status: accepted and implemented

## Context

ADR-0034 gave a Bot an approval card: the first write tool stops the loop, the
person decides, `decide!` runs the held call. ADR-0044 moved the *connection*
demand to the moment it becomes necessary and, on the way, found that a stored
card's state was never recomputed — a card written while Google was
unauthorized went on offering 認証する after the person had authorized it.

The approval card has the same defect from the other side, and it is worse,
because what goes stale is a request to act rather than an offer to connect.

Measured 2026-08-14 against `96fc44e`, with a Bot holding
`gmail_send_message`:

```
after hold      — status: waiting-approval   pending-call? true
after a second
message         — status: waiting-approval   approval cards: 1  decisions: [nil]
                  [:runs bot-id] => nil
                  decide! on that card => threw 承認待ちの操作がありません。
```

Sending a second message replaces the run. The held call is gone. But the card
is still in the transcript with no decision, so `presence` counts it,
`bot/status` returns `waiting-approval`, and the Bots screen reports that
status **for the rest of the conversation**. The card renders an enabled
承認して実行 whose only outcome is a refusal — the exact failure `:authable?`
exists to prevent, one card over.

The application had no name for what had happened, so it reported the wrong
thing twice: the badge said a person still had something to answer, and the
refusal said the Bot had nothing held. Both were about the same card, and
neither said *the person asked for something else instead*.

## Decision

**An approval is scoped to a direction, and a direction is one instruction from
the person plus everything the Bot does carrying it out.**

`bots/send!` increments `[:directions bot-id]` before recording the message, so
everything from that point — including any request raised on this turn —
belongs to the new direction. An approval card records the direction it was
raised under in `:card/direction`.

`bot_core.kotoba` gains the judgement, over three scalars:

```clojure
(defn request-standing [r [:ref :bot/request]] :i64
  (if (record-get r :answered)
    (request-answered)
    (if (> (record-get r :current) (record-get r :asked-at))
      (request-superseded)
      (request-open))))
```

Three things about it are the decision, not the spelling:

1. **`answered` is tested first.** A decision the person actually gave is a
   fact about the past, and nothing they say afterwards unmakes it. Reversing
   these two branches erases a decision that was given.
2. **`>` and not `>=`.** A request asked under the direction still in force is
   open. Only a *later* direction retires it.
3. **A missing direction is 0, and real directions start at 1.** A card written
   before this field existed therefore reads as superseded rather than as
   something a person could still be waiting on — nothing recorded what it was
   waiting for, so the honest answer is that it is not answerable.

**Nothing rewrites a stored card.** The standing is recomputed on read, exactly
as `:card/state` and `:authable?` are on a connection card, and for the same
reason: the person did not decide anything, they moved on, and `:card/decision`
is the record of what they decided.

Three readers follow from it. `presence` counts outstanding requests rather
than undecided ones, so the badge stops lying. `public-card` reports
`:standing`, and `interaction.js` renders 古い指示のため取り下げ instead of a
live button. `decide!` refuses a superseded card with
「この承認はもう古い指示のものです」 before it reaches the held-call check, so
the person is told which refusal it is.

## What was deliberately not built

The mechanism this is modelled on (ADR-2608181200, measured from another
vendor's desktop agent) also remembers refusals — hashed by target, scoped by
direction, bounded, with a saturation marker so that evicting an exact refusal
refuses that direction wholesale rather than forgetting it.

**That has no path in this application and was not built.** Rejecting a held
write clears the run, so the loop cannot re-propose within the same direction;
and in a later direction the refusal correctly would no longer hold. Routines
and handoffs cannot reach it either — `may-start?` and `may-fire?` both refuse
while a run is held. A refusal memory here would be a mechanism that never
fires, and a check that cannot discriminate is theatre whichever direction it
fails in.

If a Bot ever runs a direction concurrently with the person answering, or a
rejection stops clearing the run, this becomes reachable and should be revisited
against that ADR.

## Consequences

- A person who changes their mind by saying something else gets a Bot that
  agrees with them. Previously they got a permanent 接続待ち-style badge and a
  button that errored.
- A held write is now unreachable once superseded. That is the point — but it
  means a person who *meant* to approve after a clarifying message has to ask
  again. The refusal says so.
- `:card/direction` is additive; cards without it read as superseded, which is
  the safe answer and needs no migration.
- Routines and handoffs do not advance the direction. They are new work but not
  a new instruction from the person, and letting one Bot's handoff retire
  another Bot's pending human approval would be the same leak in reverse.

## Verification

`clojure -M:test` on this branch — **1467 tests, 8814 assertions, 0 failures,
0 errors.**

The parent `96fc44e` was not run separately, and the number to compare against
is not the one ADR-0044 recorded. That ADR measured 1447 / 8656 on its own
branch *before* it merged, and the domain-binding work (ADR-0043) landed on
`main` in between — so 1447 is not this change's baseline, and quoting it here
would have made this change look like it added twenty tests.

Counted statically instead, which is exact:

```
git grep -c '^(deftest' <rev> -- 'test/**/*.clj' 'test/**/*.cljc'
  e073007  (ADR-0044 branch point)  1436
  96fc44e  (this change's parent)   1457   +21 from the ADR-0043 merge
  HEAD     (this change)            1460   +3, which is exactly these three
```

The ten parity cases add assertions and not tests: the oracle corpus is driven
by `doseq` inside one `deftest`, so a case is an assertion. Noticing that the
arithmetic did not close is what surfaced the wrong baseline.

The measurement above was taken before the change and is reproduced as
`a-new-instruction-retires-a-held-approval`, which asserts the standing, the
absence of a decision, the badge, and the refusal message. Two tests hold the
other directions: `a-decision-already-given-is-not-unmade-by-a-later-instruction`
(ordering of `answered` before direction) and
`an-approval-asked-under-the-current-instruction-still-works` (the change must
not make approvals unanswerable — it asserts the write actually ran).

Ten cases were added to the oracle parity corpus, covering both orderings, the
`>` boundary at `asked-at == current`, and the direction-0 legacy card.
`resources/cloud/itonami/app/oracle/bot.kir.edn` was regenerated with
`clojure -M:test:gen`; it was the only KIR that changed.
