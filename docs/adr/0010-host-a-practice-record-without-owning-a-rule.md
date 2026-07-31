# ADR-0010: Host a practice record without owning a single one of its rules

## Status

Accepted.

## Context

`cloud-itonami/lawfirm` is a law-practice OS: it records what a bar-verified
弁護士 decides and makes operations that skipped that decision structurally
impossible — 18 HARD invariants, 9 operations that require human sign-off, an
append-only ledger that carries holds as well as commits.

What it has never had is a host. Its `Store` was in-memory, it has no HTTP
ingress (Kotoba has no ingress capability), and its `lawfirm.workspace` ports
— an inbox to read arrivals from, a Drive to put a 一件記録 in, a calendar to
publish a docket to — were protocols nobody implemented. Meanwhile this app
has exactly those things: an archive inbox modelled as a `mail.mailbox`, a
Drive with ACLs and quota, and a `state.edn` written under a lock through an
atomic move.

The two were entirely unaware of each other. This app's fleet catalog did not
even contain a row for `lawfirm`.

## Decision

Adapt, and own nothing.

`cloud.itonami.app.lawfirm` supplies effects and reads projections. It does
not decide whether a 送達 may happen, whether a 期限 has lapsed, whether an
answer may be sent, or whether a destination is verified. Every one of those
is `lawfirm.governor`'s, and this app calls the same gate every other host
would.

Concretely:

- **The record lives in `state.edn` under `:lawfirm/db`**, written through
  `store/transact!`. `lawfirm.store/durable-store` asks for one function,
  `(fn [db] ...)`. Reusing the durability this app already proves beats
  inventing a second file format in the same directory.
- **`persist-db!` drops a snapshot older than the one already written.** The
  practice publishes a transition before persisting it — that is what makes
  its atom the in-process authority — and stamps each one with a version so a
  host can decline a stale arrival. It cannot do that itself: only the host
  knows what it has already written.
- **The inbox becomes arrivals.** A mailbox entry's id becomes the arrival's
  digest. Not the snippet: the snippet is the message's first 220 characters,
  which is prose, and prose is the one thing the practice's record must not
  hold. `lawfirm.governor/prose-keys` would refuse it at the gate — but as a
  bug caught, rather than as an attempt never made.
- **Arrivals become requests, not writes.** What this app knows is what its
  archive says arrived. It becomes a fact about the practice by clearing the
  governor, like everything else.
- **The surface ships disabled**, like `:agent-control` and every
  `:authority`. Enabling it means this installation holds a practice's
  一件記録 — client identities, 利益相反 clearances, 預り金 balances, 送達先 —
  which is a decision about what this machine is.

## What the Drive port deliberately does not do

It reconciles folders and stops.

`documents/create!` in this app makes an *editable office document* — a sheet,
a doc, a form. A lawfirm 書面 is an external document identified by an
`:object-ref`. Creating a blank spreadsheet named 準備書面 would put something
in the Drive that looks like the filing and is not it, and the person who
opened it would be one click from believing they had found the document.

So the folders are real and the documents are absent, and the absence is
stated rather than filled with a plausible placeholder.

## What the calendar port does not do at all

There is no `CalendarPort` implementation here. `workspace/calendar-snapshot`
reaches EventKit under a `calendar/read` policy; there is no write path in
this app to reach for. Instead `/api/workspace/lawfirm/docket` serves the
`calendar.model` projection — for this purpose the app *is* the calendar. A
host that gains a write path implements the protocol; the projection is
already the shape it wants, including `:calendar/all-day? true` on every 期限,
because a 出訴期限 is a calendar day and not midnight in some timezone.

## Consequences

- Five routes, all behind the Passkey session. `GET /api/workspace/lawfirm`
  answers while disabled — a client has to render *something* when the record
  is off — and reports `:matters nil`, not `0`. Zero matters would be a
  measurement of something never looked at. Every other route refuses.
- `POST /api/workspace/lawfirm/inbound/sync` reports, per arrival, whether the
  gate committed, escalated or held it, with the rule names for a hold. A sync
  that silently dropped what the governor refused would be a sync that hides
  the only interesting part.
- **This app cannot approve anything.** `:record-inbound-transmission` is not
  an escalated op, which is why the sync works at all; every op that *is*
  escalated parks for a 弁護士 in the practice console. This is the same line
  ADR-0006 draws for payment approval and the same one `cloud-itonami/kaisya`
  draws for its portal.
- 847 tests / 3770 assertions green, `-M:lint` 0 errors.

## Known gaps

1. **Only `:email` arrivals.** The m365 archive has no fax, so nothing here
   produces a `:fax` arrival even though the practice models one. A fax
   gateway is a separate host.
2. **Nothing executes a `dispatch-plan`.** The practice can compute exactly
   what to send, to which registered and currently-verified destination —
   there is no transport here to hand it to.
3. **The record is single-tenant.** `:lawfirm/db` is one practice per data
   directory, unlike the Drive, which is per principal. A practice is not a
   per-user thing and modelling it as one would have been the wrong shape,
   but multi-tenant hosting is genuinely absent rather than deferred.
4. **No UI.** The routes return JSON; the practice console lives in
   `cloud-itonami/lawfirm` and the company portal in `cloud-itonami/kaisya`.

See `com-junkawasaki/root` ADR-2607319600 for the practice-side design these
routes attach to.
