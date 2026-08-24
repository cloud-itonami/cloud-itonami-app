# ADR-0074: A storefront is chat-centered, but orders are not prose

Date: 2026-08-24

## Context

ADR-0072 joined merchant DID, legal profile, x402 receipt configuration, and
shipping readiness in the seller's Bot conversation. It deliberately stopped
before a buyer storefront. Extending that conversation with a free-form
"purchase this" tool would make model text the authority for product identity,
quantity, delivery address, and price.

## Decision

The storefront is a view in Cloud Itonami's existing single-page document. Its
conversation searches only the published catalog and renders the result as
deterministic product cards. Cart lines, quantities, delivery address, total,
and payment request remain structured controls and records.

- Public catalog reads expose display name, merchant DID, products, and public
  x402 receipt parameters. Legal, ship-from, return, and buyer addresses are
  excluded.
- A seller publishes only after the DID-bound opening record is ready and at
  least one active product exists. Publication means this Cloud Itonami
  deployment serves the storefront; it does not claim DNS or a second deploy.
- Checkout requires a human Passkey session and CSRF protection. The server
  ignores client prices, reloads products by SKU, checks inventory, and
  recomputes the exact USDC amount.
- Creating an order stops at `awaiting-wallet-signature`. The returned x402
  request contains no signature. Inventory is not decremented, settlement is
  not claimed, and fulfillment remains `not-requested` until later verified
  effects land.
- Buyer DID and merchant DID bind the order. Delivery address is retained in
  the private order record and never returned by the public catalog route.

## Consequences

Shopping can begin as a conversation without turning model output into an
order ledger. The next slice can bind an external Wallet signature and verified
x402 settlement to the fixed order, then decrement inventory and propose
fulfillment without changing what this slice's statuses mean.
