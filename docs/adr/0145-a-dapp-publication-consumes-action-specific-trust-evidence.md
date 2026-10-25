# ADR-0145: A dapp publication consumes action-specific trust evidence

**Status**: accepted · 2026-10-25 · owner instruction「着手して, owner として ok」·
Linear JUN-418 / JUN-420. This is the "separate ADR and action-specific
authorization policy" ADR-0081's consequences required before its stored
Human Passport evidence could be consumed anywhere.

## Context

ADR-0081 made the Human Passport step-up evidence-only: `POST
/api/v1/trust/human-passport/verify` verifies an EAS Onchain Passport
attestation live, binds it to the authenticated Principal's Passkey Smart
Account, persists a policy-bound receipt, and answers
`grants-capability: false`. Its consequence: a later action may consume this
evidence only through a separate ADR and an action-specific authorization
policy. The DApps Store publication action (ADR-0144) is the first such
action.

## Decision

`dapp.publish` is admitted by `cloud.itonami.app.dapp-publish-gate`, an
action-specific policy over the *already persisted* step-up receipt:

- It reads `[:trust :external :human-passport <principal-id>]` from the store
  snapshot — the exact shape `humanity-trust` writes — and never re-verifies
  on chain, never contacts anything, and persists nothing.
- It is fail closed. Missing evidence, a stored refusal, an evidence subject
  that does not match the publishing Principal, a missing or passed
  `:valid-until`, an unreadable instant shape, a malformed repository name,
  a revision that is not a 40-hex immutable SHA, or files without one
  64-hex sha256 each all refuse the publication. Time shapes never mix:
  epoch seconds compare with epoch seconds, ISO-8601 UTC with ISO-8601 UTC;
  anything else is unreadable and refuses.
- Refusal returns `{:allowed? false :reason <keyword>}`; `admit!` throws the
  same refusal as a typed ex-info. There is no partial admission.
- Admission carries `grants-capability? false`. It lets the publication
  proceed to the review pipeline (JUN-424); it grants no payment, tenant,
  delegation or Bot execution authority, and it does not promote the stored
  evidence to general trust.

The gate is pure: snapshot plus request in, decision out. Route wiring into
the marketplace registration endpoint lands with the review pipeline
(JUN-424) and the UI (JUN-426), which together define what "publish" means
operationally.

## Consequences

- The stored Human Passport evidence now has exactly one consumer, named
  here. Any second consumer needs its own ADR and its own policy.
- The 90-day score age bound (ADR-0081) is enforced at verification; this
  gate enforces the receipt's `:valid-until` at publication time. Both must
  pass; neither widens the other.
- `dapp_publish_gate_test` covers the admitted case and every refusal case
  (8 tests / 47 assertions on the kbb sci engine). The test is registered in
  `cloud.itonami.app.test-runner`.
- A publication record written by a future route must echo this decision's
  `:evidence` map (attestation uid, policy id, claim id, valid-until) so the
  published trust claim of ADR-0144 can be audited against the receipt.
