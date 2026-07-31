# ADR-0011: Fax through the API, because the UI is the hazard

## Status

Accepted.

## Context

`cloud-itonami/lawfirm` can compute exactly what to send and where: a
committed 送達, a document its 弁護士 approved, and a destination read from a
registered recipient whose fax number a named 弁護士 verified within 180 days.
What it has never had is anything to hand that to. `dispatch-plan` returned a
plan and nothing executed it.

The prior art in this workspace — `etzhayyim-project-fax` — used Dropbox Fax
as a **manual UI handoff**: `provider="auto" → manual-handoff (Dropbox Fax
UI)`, with a Phaxio API path marked `DEAD PATH ... never wired in production`.
An operator opens the web app, uploads the document, and types the number.

That is precisely the shape `lawfirm.transmission` exists to eliminate. The
practice removes the destination from send time so it cannot be mistyped; a UI
handoff puts it back, in the hands of whoever is in the biggest hurry, and
every invariant upstream becomes decoration.

Dropbox Fax has an API.

## Decision

Use it, and treat that as the control rather than as a convenience.

`cloud.itonami.app.fax` implements `lawfirm.workspace/DispatchPort` over
`POST https://api.hellofax.com/v1/Accounts/{guid}/Transmissions`. The `To`
parameter is filled from `dispatch-plan`, which read it from the record. **No
human is in a position to mistype it**, and a caller of the route can choose
*which* 送達 to execute and nothing about where it goes.

### It refuses to send an unidentified document

The practice approves a `doc-id`, not a file. If the work product records
`:object-ref "sha256:…"`, the bytes offered must hash to it; if it records no
ref, this refuses rather than sending.

That is deliberately inconvenient. The alternative is a system that will one
day fax the wrong file to a court and produce a green audit trail saying it
went fine, and the trade is not close: recording a digest costs an operator a
minute, and the failure it prevents is unrecoverable.

### Every refusal happens before the transport is touched

Disabled surface, missing credentials, a plan the practice does not bless, a
non-fax channel, unidentified bytes — all checked before anything reaches a
phone line. A request that cannot lawfully proceed never costs money and never
rings.

### The password is never in this repository's reach

The API takes HTTP Basic auth with the **account's own password**. So it is
not in config, not in the state file, and not in an environment variable: the
config carries `:username`, `:account-guid` and `:keychain-service`, and the
password is read from the OS keychain as one item, by service and account.
Never an enumeration — dumping a keychain to find one credential exposes the
metadata of every unrelated secret on the machine.

### `:dialled` and the three-valued check

The provider may echo the destination it used. When it does, that value goes
into the record as `:dialled` and
`lawfirm.transmission/direction-check` compares it with the registered
coordinate. When it does not, `:dialled` stays nil and the check returns
`:undeterminable` — **not** `false`. A confident "not misdirected" for a
comparison nobody made is the dangerous answer, so it is not available.

A mismatch is recorded, never refused. The pages have already gone; refusing
to write down where they went destroys the evidence of the incident.

### The callback

Dropbox POSTs a terminal status to a configured URL. That route has no session
— a fax machine has none — and is authenticated by a shared token in the path,
which is weak and is what the mechanism affords. What bounds the damage is
reach: a callback can record a status against a 送達 **this practice already
sent**, resolved by the provider's own GUID. It cannot create a transmission,
cannot change a destination, and cannot cause anything to be sent. A callback
for a GUID this practice never sent matches nothing, which is also the answer
for a forged one.

Idempotent by construction: the record upserts by `:transmission-id`, so the
retry schedule (15 minutes out to 16.5 hours) cannot duplicate.

## Consequences

- Three routes: status and dispatch behind the Passkey session,
  `POST /api/fax/callback/{token}` outside it.
- `:fax` ships disabled, like `:lawfirm` and every `:authority`.
- 872 tests / 3838 assertions green, `-M:lint` 0 errors.
- `lawfirm.workspace/dispatch-plan` gained `:client-id` and `:bengoshi-id`
  (lawfirm `8889aa0`). Without them the confirmation this app runs back
  through the practice's gate was held for `:no-client` — every fax would have
  been sent and none of the outcomes recorded. Found by a test, not in
  production.

## What has NOT been proved

**This code has never run against `api.hellofax.com`.** No account is
provisioned in this workspace; a live call costs money and reaches a real
phone line. Every test drives an injected `send-fn`. The request shape follows
Dropbox's published documentation and the first live call is a deployment
step, not something a test here has verified.

Also unresolved:

1. **API access is not self-serve.** It must be enabled by emailing Dropbox
   support, and requires a paid plan.
2. **v1 is being phased out** in favour of v3, per Dropbox's own docs. When
   that lands, `api-base` and `parse-response` change and the port contract
   above them does not.
3. **200 pending faxes per day**, then 429. Named in the refusal rather than
   surfaced as a bare HTTP code.
4. **No inbound fax.** Dropbox Fax can receive and POST to
   `DefaultInboundFaxCallbackUrl`; this app only reads `:email` arrivals from
   the m365 archive. An inbound fax path would give
   `lawfirm.transmission`'s `:fax` channel its receiving half.
