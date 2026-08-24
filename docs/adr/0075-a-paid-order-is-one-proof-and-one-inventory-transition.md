# ADR 0075: A paid order is one proof and one inventory transition

Status: accepted

## Context

The storefront could create a server-priced order, but stopped before payment.
That left two unsafe gaps: stock was neither reserved nor captured, and a Chat
message could be mistaken for evidence that a payment happened.

The deployed `x402.nexus` facilitator currently verifies the transaction proof
shape used by its v1 transaction scheme. Its exact x402 v2 settlement path is
not yet the production boundary used by this app.

## Decision

- Creating an order reserves available stock for 30 minutes. It does not reduce
  physical on-hand inventory.
- The buyer must explicitly ask an EIP-1193 Wallet to send Base USDC. Cloud
  Itonami never asks for, receives, or stores a seed phrase or private key.
- After three confirmations, the client submits only the transaction hash and
  payer address. The server asks `x402.nexus/verify` to verify that proof.
- Only a valid proof may atomically change the order to `paid`, capture the
  reservation, and decrement inventory. A transaction hash can be captured by
  one order only. Retrying the same proof for the same order is idempotent.
- Fulfillment is a one-way merchant-owned transition:
  `ready-to-pack -> packed -> shipped -> delivered`. Shipping requires a
  carrier and tracking number.

## Consequences

A conversation may request these operations, but prose is never payment or
shipping evidence. An unavailable verifier fails closed, expired reservations
cannot be captured, and an unverified or replayed transaction cannot decrement
inventory. Carrier label purchase and pickup remain outside this boundary until
a carrier adapter records those external effects.
