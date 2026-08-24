# ADR-0072: Commerce is one DID-bound conversation, not five apps

Date: 2026-08-24

## Context

The workspace already has commerce records (`cloud-itonami/ec` and
`app-shopping`), an x402 facilitator, a non-custodial Bot Wallet, a marketplace
fulfillment actor, and tenant identity. They were separate capabilities. Their
presence did not let a corporation or sole proprietor open a shop from Cloud
Itonami, and presenting the set of repositories as one product would confuse
source with an observed end-to-end flow.

## Decision

Commerce setup is a tenant-scoped aggregate reached through every Bot
conversation. It is not a second chat screen or a new navigation destination.

- A corporation is named by the active Organization DID. A sole proprietor is
  named by the User DID. That axis becomes immutable when the store is created.
- Legal display and address, x402 receipt configuration, and shipping/returns
  configuration are readiness checks on the same aggregate.
- x402 receives USDC on Base through the Bot's verified external signer. Cloud
  Itonami stores the public pay-to address and signer reference, never a private
  key and never a model-generated signature.
- Fulfillment configuration names the existing marketplace fulfillment actor,
  but remains plan-only. Label purchase and carrier pickup are separate effects.
- `ready` means the local opening record is complete. It never means a public
  storefront was deployed. Publication remains `not-published` until a later
  delivery slice performs and verifies that external effect.
- Commerce mutations use the existing Bot write/approval path. A read-only Bot
  may inspect readiness but cannot alter it; omakase and explicit approval keep
  their existing meanings.

## Consequences

The ChatUI can now guide one ordered flow instead of referring the owner to
separate repositories. The first slice intentionally does not implement a buyer
storefront, x402 settlement callbacks, carrier label purchase, pickup, or legal
filing. Those boundaries are visible in the returned state, so later work can
add an effect without retroactively redefining what `ready` meant.
