# ADR-0060: A Bot decides its own approval cards

**Status:** accepted — 2026-08-18. Supersedes the "a Bot may never approve"
clause of ADR-0034 and the effect allowlist in ADR-0052.

## Context

ADR-0034 wrote a categorical refusal into the decision core:

> A Bot may never approve.

`bot_core.kotoba`'s `may-approve?` implemented it as a flat `false` for
`actor-kind = "agent"`, tested first and alone so that no combination of the
other three facts could reach the human branch, and the comment beside it said
this was "the one thing that must be impossible rather than merely
unimplemented."

ADR-0052 then opened a hole in it without saying so in those words. Omakase
let a Bot execute a write immediately and record a receipt marked
`decided-by=bot`. The refusal survived only in the sense that the *route* still
said no; a Bot in omakase was already deciding, through a different door.

The hole was also drawn in the wrong shape. `omakase-tool?` was a hand-written
allowlist of three effects — workspace writes, the networkless shell,
`gmail_send_message` — with browser and connector writes carved out to stop for
a human regardless. That list was a **second, weaker opinion** about what a Bot
may do, sitting beside the real one: the grant, intersected with what the
deployment enabled, computed by `bot/admitted-tools`. A tool that survived that
intersection is one a person put in a grant on a deployment that turned it on.
Naming it again in a predicate added nothing to that and subtracted nothing
from it. All it decided was whether the person got asked a second time.

The owner lifted the refusal on 2026-08-18: bots may approve.

## Decision

**`may-approve?` takes a fourth fact, `delegated`, and it is the only one the
agent branch reads.**

```
(if (string=? actor-kind "agent")
  (record-get d :delegated)      ; was: false
  (and human identified authorized))
```

`delegated` is `:bot/omakase?`. What makes this an owner's authority carried
out by a Bot, rather than a Bot's own, is that **only the human `/api/bots`
surface may write that field** — ADR-0052's narrowest and most load-bearing
sentence, now the thing the whole design rests on. `handle-bots!` is behind
`require-human-session!`, so an agent session cannot create a Bot, widen a
grant, or set its own delegation. It can only spend one it was given.

The ordering survives for the reason it was written. Actor kind is still tested
alone and first, so a caller cannot reach the human branch by asserting three
booleans about itself, and an agent with no delegation is refused exactly as
flatly as before.

**The omakase allowlist is deleted, not extended.** A delegated Bot decides
every write it is admitted to call, including browser writes and connector
writes. `omakase-tool?` is gone rather than widened to return true, because a
predicate that cannot say no is not a gate and would read like one at every
call site.

**Admission does not move.** `run-tool!` is still reached only through
`(:runnable run)`, which is the grant intersected with the deployment's enabled
set. The delegation replaces the **wait**, never the grant: a Bot cannot decide
its way to a tool nobody gave it, and `grant-widens?` still surfaces a grant
naming something nobody enabled rather than pruning it.

**The host stops holding a second admission rule.** `decide!` used to special-
case the agent session in an `or` arm *around* the core — a rule written where
the core could not see it and the parity corpus could not cover it. Both kinds
of session now ask the same question of the same function.

**The receipt is unchanged and stays mandatory.** Every automatic execution
writes the same approval card into the durable conversation, already decided,
with `decision-mode=omakase`, `decided-by=bot` (or `agent-session` when an
external agent decides a held card) and a timestamp. What changed is who may
write the decision, not whether it is written down.

## What this does not change

- **Work-governance approvals.** `approval_core.kotoba` keeps
  `:actor-is-person` as an eligibility fact, so a Bot still cannot count toward
  the quorum on a governed WorkItem. That is a different plane with different
  stakes — a multi-actor tally with separation of duties and content-hash
  binding — and lifting it is not implied by lifting this one. If it should
  also move, it needs its own decision.
- **Step-up authority.** `work-approval`'s WebAuthn user-verifying assertion is
  untouched. `may-approve?`'s `identified?` was always the weaker fact and is
  documented as such.
- **The grant, the workspace, the accounts, the network, the credentials.**
  None of them widen. This ADR is about the wait.
- **Workforce Bots.** ADR-0056's projected roles receive no standing write
  grant and no omakase delegation; nothing here provisions one.
- **Handoffs.** `handoff_core/may-approve?` still returns a flat `false` for an
  agent, and should: it answers a different question — whether one Bot's
  message can stand in for a person's approval of *another* Bot's held run.
  ADR-0054 already settled that provenance transfers and authority does not. A
  delegation is something an owner places on one Bot, not something that
  travels down a handoff chain.

## Consequences

- A delegated Bot completes end-to-end work — including the browser and the
  desktop capability of ADR-0059 — without parking on a person, which is what
  ADR-0036 named as the Grok-Bot-shaped gap and then declined to close.
- The blast radius of `:bot/omakase?` is now the Bot's whole admitted tool set
  rather than three effects. That is the point, and it is also the risk: the
  toggle deserves to say so on screen, and the grant is now the only thing
  between a delegated Bot and an effect.
- "A Bot may never approve" was a sentence this repository was proud of. It is
  worth being plain that it is gone, rather than leaving it in ADR-0034 to be
  quoted by someone reading only that file.
