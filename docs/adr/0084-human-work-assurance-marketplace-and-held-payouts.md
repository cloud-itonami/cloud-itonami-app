# ADR-0084: Human work uses authority receipts and x402 USDC escrow

**Status:** accepted — 2026-08-31

## Context

ADR-0083 made qualified human work one evidenced state machine, but stopped at
organization-entered verification, recorded compensation, and an authenticated
workspace API. A production marketplace also needs external claim checks,
identity assurance, payment, conditional release, and privacy-safe discovery.

Those concerns remain distinct. A licence registry does not identify the
browser user. Identity assurance does not prove professional scope. Payment
does not prove work, and work verification does not itself authorize a wallet
transfer.

## Decision

### Authority and identity checks

Online checks use operator-configured HTTPS providers. A browser may select an
enabled provider id but may not provide an endpoint. A credential response is
accepted only when it binds the same worker, credential id, immutable claim
version, and requesting organization. Editing a claim invalidates the result.

Identity providers bind a worker id and organization and return only an
assurance level (`basic`, `substantial`, or `high`), status, receipt, and
validity window. Cloud Itonami does not retain identity documents, document
numbers, birth dates, or raw provider output. Compensated and public requests
require at least `substantial` identity assurance by default.

### x402 and USDC payment state

Human-work compensation is USDC in atomic units (six decimals) on an EVM
network identified by CAIP-2. It does not use card currency minor units or a
Stripe account. A compensated worker registers an EVM payout address; this is
payment routing, not civil identity or qualification evidence.

The HTTP exchange uses x402 v2. An authenticated requester calls the request's
`/fund` route without payment and receives `402 Payment Required` plus a
base64-encoded `PAYMENT-REQUIRED` header. The requirements advertise the
`auth-capture` scheme, the USDC contract, the accepted worker as `payTo`, and a
custom escrow operator with `paymentFlow: escrow` and `captureMode: deferred`.
The retry carries `PAYMENT-SIGNATURE`. Cloud Itonami checks that its accepted
requirements are unchanged, confirms that the facilitator advertises the
scheme and network, and sends the payload to `/settle`. The signed payload is
never persisted.

The worker accepts before funding so the non-custodial receiver is known.
Compensated work cannot start until the first settlement records `funded`.
After evidence submission and requester verification, Cloud Itonami sends an
authenticated `capture` instruction to the configured custom operator. If an
accepted request is cancelled before starting, or submitted work is rejected,
it sends `void`. Transaction hashes, payer address, network, requirements, and
the non-secret payment reference are retained as receipts.

The custom operator is an explicit trust and deployment boundary. x402 defines
how a custom `auth-capture` operator collects into the canonical escrow while
lifecycle calls occur out of band. Cloud Itonami neither holds wallet private
keys nor represents itself as the custodian. Legal escrow, money transmission,
tax, sanctions, employment, and contractor-classification obligations still
require deployment-specific review.

### Public marketplace

`GET /human-work` and `GET /api/human-work/requests` are public, read-only
surfaces. They list only `public` and `open` requests. The projection includes
general service area, time, qualification and identity requirements, evidence
contract, and disclosed USDC/network/payment state. It omits exact addresses,
access notes, requester and worker ids, source/Goal ids, payout addresses,
provider evidence, submissions, x402 receipts, and audit records. Acceptance
remains authenticated and repeats eligibility transactionally.

## Operational boundary

- Authority, facilitator, and operator integrations ship disabled and fail
  closed.
- Provider URLs are fixed configuration, never request input. Optional bearer
  credentials come only from named environment variables.
- Mainnet activation must confirm `/supported` advertises x402 v2
  `auth-capture` for the configured network and must verify the custom operator
  contract and lifecycle service independently.
- Local deployment, public edge deployment, configured payment, testnet
  settlement, mainnet settlement, capture, and void are separate evidence
  levels.

## Consequences

The application has one coherent contract from public discovery through
qualification, identity, accepted worker routing, USDC funding, physical work
evidence, inspection, and conditional capture/void. A deployment without
configured external providers can still operate unpaid organization-only
HumanWorkRequests, but cannot present online assurance or payments as active.
