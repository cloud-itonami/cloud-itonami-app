# ADR-0146: A publication deposit is a bond with explicit forfeiture

**Status**: accepted (design) · 2026-10-25 · owner instruction「着手して, owner として ok」·
Linear JUN-418 / JUN-423. The bond contract and custody wiring are **not
implemented**; this ADR fixes their shape so implementation cannot quietly
weaken it.

## Context

ADR-0144's `escrow-backed` tier promises economic deterrence: a publisher
who ships fraud loses money. The old private chain (`chain-contracts`,
chainId 260425) is decommissioned (contract preservation only, redeploy
target Base L2 / etzhayyim per ADR-2605211950), so there is no settlement
surface today. Payment authority in this app is bounded by ADR-0005. A
deposit design that silently moved custody or signing authority outside
those boundaries would be a policy failure dressed as a feature.

## Decision

A deposit is a **bond**, not a fee and not escrow of user funds:

- **Posting.** Before a listing may reach `escrow-backed`, the publisher
  posts a bond sized by risk tier. Money-moving dapps post the highest bond.
  No bond → no `escrow-backed` badge, no money-moving listing. Fail closed.
- **Custody.** The bond is held by a dedicated bond contract on the
  etzhayyim/Base L2 redeployment target named in ADR-2605211950. Transaction
  signing and on-chain settlement origination remain etzhayyim-exclusive
  operating-entity actions; this app reads and verifies. Until that surface
  exists, `escrow-backed` stays **closed** — the tier is not partially
  simulated.
- **Forfeiture (slash).** Only on a confirmed case: proven fraud, a false
  identity or existence claim, or an artifact swapped after review. A report
  alone never forfeits. Forfeiture requires the human gate named in
  `docs/dapps-store-takedown-runbook.md` and writes an audit record
  (case id, evidence, approver, amount, contract receipt).
- **Return.** After a quiet period set per risk tier with zero confirmed
  reports, the bond returns to the posting account. Return is recorded the
  same way.
- **Accounting.** Post, forfeit and return events are appended to the same
  trust/audit ledger shape the step-up evidence uses; no ledger content
  enters a registry or a published record.

## Consequences

- Implementation work is JUN-423's remaining scope: the bond contract, its
  deployment on the ADR-2605211950 target, the read/verify adapter here, and
  the human-gate runbook integration. Until then the policy document keeps
  `escrow-backed` marked as unavailable.
- This ADR grants no payment capability and changes no existing route's
  authority (ADR-0005 stands).
- Disputes over forfeiture follow the appeal step of the takedown runbook;
  an overturned case returns the bond and records the correction.
