# ADR-0082: WebAuthn credentials are RP-scoped Smart Account controllers

**Status:** accepted — 2026-08-28

## Context

Cloud Itonami, Murakumo and Kotobase have different registrable domains. A
WebAuthn public-key credential is deliberately scoped to the RP ID it was
created for. Making that credential domain-free would remove the phishing
boundary WebAuthn supplies. Related Origin Requests can authorize a limited
set of origins to use one common RP ID, but do not remove the RP ID or its
domain-control dependency.

Treating `auth.itonami.cloud` as the permanent signing root would make every
product's on-chain authority depend on one product domain. Treating every
product Passkey as a different person would instead fragment the Principal and
produce a different Wallet whenever a domain or device changes.

## Decision

The stable Principal and its ERC-4337 Smart Account are the domain-independent
identity layer. A Passkey is one replaceable, RP-scoped controller of that
identity. `auth.itonami.cloud` is an OAuth and step-up provider, not the
exclusive identity root.

Each verified Passkey record retains:

- credential id and P-256 public key fingerprint;
- the RP ID and exact registration origin used by the verifier;
- whether that provenance was recorded or inferred for a legacy local record.

The first verified P-256 key still determines the counterfactual account
address. A later Passkey registered at `murakumo.cloud`, `kotobase.net`,
`localhost` or another RP does not change that address and is not silently
granted authority. It becomes an owner only after the current owner authorizes
an ERC-4337 operation that calls
`addOwnerPublicKey(bytes32,bytes32)`.

For the current Smart Wallet 1.1 ABI, owner updates use
`executeWithoutChainIdValidation(bytes[])`. This makes one owner change
replayable across supported chains that have the same account and contract
family. Every target chain still requires submission and an independently
verified receipt.

Related Origin Requests remain an optional continuity tool when the same
organization intentionally wants one RP ID on several origins. They are not
the default identity architecture because they retain one RP domain as the
credential root and widen the origins able to invoke it.

## Landed slice

This change lands the non-custodial preparation layer:

- Passkey binding can persist server-verified RP ID and origin provenance.
- Smart Account descriptors declare the multi-RP controller model.
- Wallet reads distinguish the initial owner from verified credentials that
  still require an owner-addition UserOperation.
- `POST /api/wallet/owners/plan` returns exact unsigned Smart Wallet 1.1
  replay-safe owner-addition calldata behind Human session, origin and CSRF
  checks.

The endpoint does not sign or submit. Its result remains
`:user-operation-ready? false` until EntryPoint nonce acquisition, current
owner WebAuthn signing, bundler submission and per-chain receipt verification
are implemented.

## Consequences

- Moving away from `itonami.cloud` requires adding another owner before the
  old controller becomes unavailable; it does not require moving the
  Principal or Smart Account.
- A second product-domain Passkey is useful recovery diversity, but only after
  its on-chain receipt is confirmed.
- Email, SSO and federation may recover a session or locate a Principal; they
  cannot grant Smart Account authority by themselves.
- A lost last owner remains unrecoverable until guardian or delayed recovery
  is implemented. Domain portability must not be presented as completed asset
  recovery before that gate lands.

## References

- [WebAuthn Level 3: RP ID](https://www.w3.org/TR/webauthn-3/#rp-id)
- [WebAuthn Level 3: related origins](https://www.w3.org/TR/webauthn-3/#sctn-related-origins)
- [Coinbase Smart Wallet MultiOwnable](https://github.com/coinbase/smart-wallet/blob/main/src/MultiOwnable.sol)
- [Coinbase Smart Wallet replay-safe owner updates](https://github.com/coinbase/smart-wallet/blob/main/src/CoinbaseSmartWallet.sol)
