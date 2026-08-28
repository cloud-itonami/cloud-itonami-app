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

This change lands the non-custodial owner-addition layer:

- Passkey binding can persist server-verified RP ID and origin provenance.
- Smart Account descriptors declare the multi-RP controller model.
- Wallet reads distinguish the initial owner from verified credentials that
  still require an owner-addition UserOperation.
- `POST /api/wallet/owners/plan` returns exact unsigned Smart Wallet 1.1
  replay-safe owner-addition calldata behind Human session, origin and CSRF
  checks.
- `/api/wallet/owners/authorize/start` fixes one chain's EntryPoint nonce, gas,
  deployment initCode and optional paymaster only after RPC/bundler/factory
  preflight; it restricts WebAuthn to the current owner credential.
- `/api/wallet/owners/authorize/finish` verifies the raw assertion again,
  low-S encodes `WebAuthnAuth` inside `SignatureWrapper`, submits it and refuses
  a bundler hash that differs from the locally computed EntryPoint hash.
- `/api/wallet/owners/operations/{id}/receipt` accepts success only when the
  ERC-7769 receipt coordinates match and `isOwnerPublicKey` independently
  observes the candidate. Assertion bytes and provider URLs are not persisted.
- Ethereum Sepolia is a separate allowlisted rehearsal chain with independently
  injected RPC, bundler and optional paymaster endpoints. Hosted infrastructure
  is an adapter. The initial Pimlico v2 path obtains the `fast` tier from
  `pimlico_getUserOperationGasPrice` before fixing the WebAuthn challenge,
  because node `eth_gasPrice` can be below the bundler minimum. A bundler that
  reports JSON-RPC `-32601` for this optional method falls back to
  `eth_gasPrice`; every other gas-oracle failure is fail-closed. This adapter
  can therefore be replaced by Alto or another conforming provider without
  changing the Principal, Passkey records or deterministic Smart Account
  address.
- Cloud Itonami issues a 120-second, target-bound assertion only to the
  allowlisted Kotobase or Murakumo authentication origin.
- `auth.kotobase.net` and `auth.murakumo.cloud` run distinct WebAuthn RPs over
  the same stable account store. A federated high-assurance session reaches the
  existing Principal, then registration creates a separate RP-scoped Passkey
  linked to that Principal.
- Anonymous registration at the secondary Murakumo RP fails closed so it
  cannot silently create a second Principal during a controller-link flow.

General transfer readiness remains false; owner addition is a narrower,
implemented UserOperation. Each chain is prepared, signed, submitted and
confirmed independently because nonce, gas and receipt are chain state even
when `executeWithoutChainIdValidation` omits chain ID from the signing hash.

The federation handoff likewise proves account linkage, not on-chain owner
membership. A newly linked product Passkey remains an owner candidate until
that UserOperation is signed, submitted and confirmed on each target chain.

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
- EntryPoint v0.6 remains a compatibility boundary of Coinbase Smart Wallet
  v1.1, not a permanent protocol choice. Provider support and a future account
  migration to v0.7/v0.8 are tracked separately so infrastructure churn cannot
  silently redefine identity.

## References

- [WebAuthn Level 3: RP ID](https://www.w3.org/TR/webauthn-3/#rp-id)
- [WebAuthn Level 3: related origins](https://www.w3.org/TR/webauthn-3/#sctn-related-origins)
- [Coinbase Smart Wallet MultiOwnable](https://github.com/coinbase/smart-wallet/blob/main/src/MultiOwnable.sol)
- [Coinbase Smart Wallet replay-safe owner updates](https://github.com/coinbase/smart-wallet/blob/main/src/CoinbaseSmartWallet.sol)
- [Pimlico v2 bundler and paymaster reference](https://docs.pimlico.io/references/paymaster)
- [Alchemy bundler EntryPoint support](https://www.alchemy.com/docs/wallets/reference/bundler-faqs)
- [Coinbase CDP Paymaster network boundary](https://docs.cdp.coinbase.com/paymaster/faqs)
