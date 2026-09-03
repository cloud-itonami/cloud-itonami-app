# ADR-0081: Human Passport is an evidence-only Sybil step-up

**Status:** accepted — 2026-08-28

## Context

Cloud Itonami has a Passkey-first Principal and a deterministic EVM Smart
Account, while `kotoba-lang/identity` can verify the current Human Passport EAS
score schema. Publishing that adapter in discovery did not connect it to a
person or to an action. Accepting an arbitrary recipient would let one wallet's
attestation be attached to another Principal; treating a passing score as
authority would bypass every existing Passkey and approval boundary.

## Decision

`POST /api/v1/trust/human-passport/verify` is the only active external-humanity
action. It requires a human Passkey session, same-origin/CSRF protection and an
explicit attestation UID. The server reads Optimism through the configured
HTTPS RPC endpoint, verifies EAS chain/schema/attester/lifecycle, decodes the
Onchain Passport score, applies scorer 335, minimum score 200000 and maximum age
90 days, and requires the attestation recipient to equal the Principal's
Passkey Smart Account address.

The verified bundle and policy-bound receipt are written in one local store
transaction. The response says `effect: evidence-only` and
`grants-capability: false`. Success does not authorize payment, tenant approval,
delegation, Bot execution or any other route. Those routes retain their existing
authorization requirements.

An absent RPC, absent Principal account, malformed/unknown UID, chain mismatch,
unallowlisted schema or attester, stale/expired/revoked evidence, score refusal,
or recipient mismatch fails closed and writes nothing.

## Consequences

- Human Passport supplements Passkey identity; it does not replace it.
- Stored evidence can be inspected and refreshed without widening authority.
- This integration covers EAS Onchain Passport, not Human Passport Individual
  Verifications on Sign Protocol.
- A later action may consume this evidence only through a separate ADR and an
  action-specific authorization policy.
