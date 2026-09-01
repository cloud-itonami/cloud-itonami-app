# ADR-0083: Bots outsource human work to verified people

**Status:** accepted — 2026-08-31

## Context

Cloud Itonami already separated a Human User (`:person`) from an artificial
performer (`:system`) and required Human approval for governed writes. That
answers who may decide, but not who performs work that cannot finish inside a
Bot: visiting a place, driving a vehicle, inspecting equipment, collecting an
item, or presenting a regulated qualification.

The bulky-waste slice supplied one physical workflow, but its worker evidence
was self-attested. A generic Bot could neither create a qualified-human request
nor ask why one person did or did not match it. Treating an uploaded licence or
address claim as verified would collapse evidence, authority, and eligibility
into one unsafe fact.

## Decision

Add the portable `cloud.itonami.app.human-work` control plane and expose it to
Human sessions at `/api/workspace/human-work`. A HumanWorkRequest follows:

```text
draft -> open -> accepted -> in-progress -> submitted -> verified
                                            \----------> rejected
draft/open -> cancelled
```

A request fixes its organization, public work area, work mode, time window,
credential requirements, private details, evidence contract, optional recorded
compensation terms, and source links. Exact addresses and access instructions
remain private until a matching person accepts.

A worker registers their own:

- service locations and onsite, remote, or hybrid work modes;
- availability windows;
- licences, qualifications, permits, insurance, training, and asset claims;
- issuer, jurisdiction, scope, validity dates, and evidence references.

Registration remains self-attestation. An owner or admin of the requesting
organization must record a separate verification decision. A worker cannot
verify their own claim. Verification is bound to the exact claim version and
organization; changing the claim clears its old verification.

Eligibility is recalculated both for matching and acceptance. The person must
be active, available for the complete work window, free of overlapping accepted
work, in a matching country/region/service area, and hold every required scope
in the requested jurisdiction. The claim must be issued by the start of work,
and both credential and verifier validity must cover the end of work. Defaults
are organization-verified for credentials and onsite locations; any explicit
self-attested policy remains visible in the request instead of being presented
as verification.

Only a Human User accepts and performs the request. A Bot remains a `:system`
performer and receives requester-side tools to create a draft, publish it, read
matches/status, or cancel it. Bot writes use the existing approval card or an
existing omakase delegation. Bots receive no registration or verification
tool. Their id, Goal, step, and WorkItem references are provenance on the
request; the Bot never impersonates the person doing the work.

The bulky-waste vertical now projects its service area, carrier licence,
vehicle insurance, and collection vehicle into the same registry. A registered
collector is not matchable until those four exact claims are verified for the
job's organization and valid through pickup completion.

## Trust and authority boundaries

- Cloud Itonami records organization verification. ADR-0084 adds optional,
  fixed-provider online issuer/registry and identity adapters without changing
  the exact-version and organization binding defined here.
- An organization decision proves only what that verifier checked and cites.
  It must not be rendered as regulator-issued verification without an issuer
  adapter and its own evidence.
- Human approval of a Bot write is not proof that physical work occurred.
  Presence, completion, and handoff evidence are separate state transitions.
- ADR-0084 adds an optional x402/USDC auth-capture escrow state machine. It
  does not decide tax, employment, contractor classification, or the legal
  status of the configured operator.
- Every browser write requires a Human session, same-origin request, and CSRF
  token. Verification additionally requires organization owner/admin role.
  Request operations fail closed outside the active organization.

## Consequences

- Bots can now hand bounded, evidence-defined work to people without turning a
  Bot into a Human performer.
- Matching has an explainable eligibility report, including the location and
  credential verification used for the decision.
- ADR-0084 supplies the public marketplace, issuer/identity provider contract,
  and x402/USDC auth-capture adapter. Dispatch notifications and each
  deployment's provider activation remain separate operational work.

## Verification

- Domain tests cover a complete request, private-data release, evidence review,
  claim-version invalidation, self-verification refusal, expiry, jurisdiction,
  availability, and overlap.
- HTTP tests cover the complete Human session flow, CSRF, owner/admin verifier
  authorization, self-verification refusal, and organization isolation.
- Bulky-waste domain and HTTP tests prove its previous self-attested worker no
  longer matches until all required HumanWork claims are verified.
