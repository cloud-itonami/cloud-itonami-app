# DApps Store publication policy

Operator-facing policy for registering and publishing a dapp on the Itonami
DApps Store (the marketplace DApps tab of `cloud-itonami-app`, itonami-registry
entries, awai.network distribution). Decisions: [ADR-0144](adr/0144-dapps-store-listings-carry-published-trust-claims.md)
(tiers), [ADR-0145](adr/0145-a-dapp-publication-consumes-action-specific-trust-evidence.md)
(publication gate), ADR-0146 (deposit, planned — Linear JUN-423).

## What is being published

A listing is description-only metadata pinned to one immutable source
revision with one sha256 per file. Publication grants no execution, no
install and no authority, at any tier. A tier changes what a listing may
claim about its publisher, nothing else.

## Prohibited content (every tier)

- Impersonation of an existing person, product or organization; brand or
  trademark cloning; look-alike domains.
- Fabricated claims: guaranteed returns, fake endorsements, false
  partnerships, invented user counts.
- Phishing, credential harvesting, malware, undisclosed fund solicitation.
- A listing whose published digest does not match the reviewed artifact.

One confirmed instance removes the listing, derates every listing from the
same publisher, and (for `escrow-backed`) forfeits the deposit.

## Trust tiers

| Tier | Required evidence | Expiry |
|---|---|---|
| `unverified` | none. Pinned revision, metadata only, warning shown | n/a |
| `human-verified` | Human Passport step-up (scorer 335, score ≥ 200000, attestation ≤ 90 days, recipient = the publisher Principal's Passkey Smart Account) + an identity credential | evidence `valid-until`; derates on expiry |
| `entity-verified` | the above + existence evidence: LEI / registry match for entities, existence record for individuals, contact proof (mail challenge or DNS TXT) | re-verification cycle of the existence source |
| `escrow-backed` | the above + a posted deposit sized by risk (money-moving dapps: highest) | deposit held for the agreed quiet period, then returned; forfeited on confirmed fraud or false claims |

Evidence is consumed only by the action it was approved for. Human Passport
evidence is evidence-only (ADR-0081) and is consumed for `dapp.publish` by
the gate in ADR-0145; it never grants a capability. Identity and existence
credentials live in the credential-card boundary (ADR-0093) and are bound to
keys the holder keeps (ADR-0098 / ADR-0099). No PII is copied into a
registry entry or a published record — the published record carries only the
tier, review date, artifact digest and evidence identifiers.

## Review pipeline (per listing)

1. Immutable pin: 40-hex revision + per-file sha256; signed CID/digest per
   the Awai release contract (AWAI-86). A digest mismatch refuses publication.
2. Static review: secrets, malware, obfuscated payloads, fabricated claims,
   trademark/brand cloning, dependency risk. Web dapps include a passive
   scan in the review receipt.
3. The gate of ADR-0145 admits the publication request only with valid,
   unexpired step-up evidence bound to the publishing Principal.
4. The published record (tier, review date, digest, evidence ids) is what
   the UI renders. Badges are generated from this record, never hardcoded.

## Reporting and takedown

A report names the listing and the evidence behind it. Confirmed fraud:
immediate quarantine, tier derating, credential revocation, deposit
forfeiture, and a sweep of the publisher's other listings and identical
digests. Appeals are recorded with their outcome. The runbook, its owners
and its SLA are Linear JUN-425; the UI surface is JUN-426.

## Status honesty

Identity verification (eKYC) is designed but unimplemented
(`cloud-itonami/ekyc`, its ADR-0001); deposit custody is unimplemented
(JUN-423). Until those land, only `unverified` listings exist in practice,
and that is the safe state: metadata only, pinned, no authority.
