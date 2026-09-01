# ADR-0069: Bulky-waste human computing is one evidenced chain

**Status:** accepted — 2026-08-22

## Context

The fleet already contains three adjacent capabilities, but no service joined
them:

- HC describes gig shifts and micro-tasks, while its runnable extraction is a
  record layer rather than a labour market;
- ISCO 9611 coordinates a registered collection crew and route but explicitly
  does not perform or authorize collection;
- ISIC 3811 records pickups/manifests and ISIC 3830 records recovery batches,
  while neither assigns a human worker to a resident's request.

Calling those repositories an end-to-end bulky-waste service would confuse
catalog coverage with a usable transaction. In particular, Cloud Itonami's
existing operator `matches` ranks blueprint fit and deliberately ignores
availability; it is not job-to-worker matching.

## Decision

Add `cloud.itonami.app.bulky-waste`, a portable `.cljc` state machine with a
JVM host adapter to the app's durable `state.edn`, and expose it under
`/api/workspace/bulky-waste`.

The chain is:

```text
draft -> open -> booked -> checked-in -> collected -> delivered -> recovered
```

- A requester creates and publishes a non-hazardous bulky-waste job.
- A human User registers their own service areas, accepted categories, vehicle
  capacity, availability, and evidence references. They are never represented
  as an `OrganismWorker`.
- Matching is the intersection of service area, category, vehicle capacity,
  containing availability window, and absence of an overlapping active booking.
- The worker books their own matching job. There is no operator-to-blueprint
  score in this decision.
- Check-in requires presence evidence. Collection requires an ISIC-3811-shaped
  manifest reference, measured weight and collection proof.
- Only the named facility operator may record delivery, with its facility
  receipt and ISIC-3830 batch id. Recovery requires a receipt and exact mass
  balance: recovered plus disposed equals accepted, and the material-output
  rows sum to recovered weight.
- Every transition appends actor, time, action and evidence to one audit chain.
- Manifest ids, facility receipts, recovery batch ids and recovery receipts are
  single-use across jobs, preventing one external proof from closing two chains.

Exact pickup address and access notes are withheld from open-job candidates.
They become visible only to the requester and booked worker. The facility sees
the chain but not the residential address.

All writes require an app session, same-origin request and CSRF token. State
transitions re-check actor, status and evidence inside one write lock, so two
workers cannot both book an open job.

## Trust boundary

Vehicle, insurance, carrier and service-location evidence starts as a
self-attested reference. ADR-0083 supersedes the old matching rule: a worker is
not eligible until an organization owner/admin binds verification to each
exact claim version and that decision remains valid through the pickup window.
This app still does not contact a regulator or insurer and must describe the
result as organization verification, not issuer verification. Trucks,
weighbridges, physical sorting and municipal acceptance remain external
authorities.

Only an explicit allowlist of non-hazardous categories is admitted in this
slice. Batteries, appliances and other hazardous or separately regulated
streams fail closed rather than being routed through a generic category.

## Consequences

- Cloud Itonami now has a runnable request-to-recovery API slice rather than
  three unjoined blueprints.
- The workflow is persistent and auditable, but it is not yet a municipal
  booking integration, payment/escrow service, regulator verification service,
  vehicle telematics system, or MRF control system.
- A public UI and production endpoint are separate release work. Their absence
  must not be reported as a live marketplace.

## Verification

- Domain tests exercise organization-verified qualification matching, address
  redaction, overlapping-booking exclusion, authorization, capacity, evidence
  and mass-balance failures.
- HTTP tests execute the complete request -> worker -> collection -> facility ->
  recovery chain and prove every write fails closed without CSRF.
- The production namespace loads under nbb, demonstrating the `.cljc` source is
  not a JVM-only path.
