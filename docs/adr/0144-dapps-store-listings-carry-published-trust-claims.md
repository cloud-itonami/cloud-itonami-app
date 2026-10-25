# ADR-0144: DApps Store listings carry published trust claims

**Status**: accepted · 2026-10-25 · owner instruction「cloud itonami の dapps store の
公開に関して, human passport や 本人確認、実在確認, deposit などを使って, 不正,
詐欺などが発生しないような dapps 登録、公開の仕組みを整えて, linear でまとめて」
＋「着手して, owner として ok」· Linear JUN-418 / JUN-419

## Context

The marketplace DApps tab in `cloud-itonami-app` lists etzhayyim actor
repositories at pinned revisions as description-only metadata
(`web.cljk` / `interaction.js`, `etzhayyimDapps`): no execution, no install,
no authority. `itonami-registry` entries are the same shape: pinned commit
plus per-file sha256, no runtime qualification. That is safe, but it says
nothing about the publisher. A listing that looks official can be issued by
anyone, and nothing on the surface distinguishes a verified publisher from an
impersonator. When the store grows past first-party repositories, fraud
arrives as impersonation, fabricated claims and swapped artifacts before it
arrives as malware.

## Decision

A published listing carries trust claims, and the store shows them as tiers.
The tier is a published record, not a vibe: each level is machine-checkable
evidence with an expiry, and the UI renders the record, never a hardcoded
badge.

| Tier | Evidence required | What the listing may claim |
|---|---|---|
| `unverified` | none | description-only metadata, warning shown, no badge |
| `human-verified` | Human Passport step-up (ADR-0081 evidence, scorer 335, score ≥ 200000, ≤ 90 days, recipient = the Principal's Passkey Smart Account) **and** an identity credential for the publisher | named publisher, identity-verified mark |
| `entity-verified` | the above, plus existence evidence for the entity (LEI / registry match / contact proof) | named entity, existence-verified mark |
| `escrow-backed` | the above, plus a posted deposit (ADR-0146) | verified badge, eligible for money-moving listings |

Prohibited at every tier: impersonation and brand or trademark cloning,
fabricated revenue or return guarantees, phishing, malware, undisclosed
fund solicitation. Money-moving dapps require `escrow-backed`. Any tier
expires when its evidence expires, and a confirmed report derates the
listing immediately (runbook: JUN-425).

Unverified listings stay exactly as safe as today: pinned revision, metadata
only, no execution, no install, no authority. A tier changes what a listing
may *claim*; it never grants the store or the app a capability.

## Consequences

- `docs/dapps-store-publication-policy.md` holds the operator-facing policy;
  this ADR holds the decision.
- The publication gate that consumes the evidence is ADR-0145. Deposit
  custody and slashing are ADR-0146 (planned, JUN-423).
- Identity and existence credentials are bound to keys the holder keeps
  (ADR-0098 / ADR-0099) and stored in the credential-card boundary
  (ADR-0093); no PII enters a registry or a published record.
- Tier rendering, review pipeline and the report/takedown path are JUN-424,
  JUN-425 and JUN-426; until they land, the tier model exists as policy and
  gate, not as UI.
