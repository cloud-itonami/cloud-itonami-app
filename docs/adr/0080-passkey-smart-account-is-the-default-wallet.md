# ADR-0080: A Passkey Smart Account is the default Wallet

**Status:** accepted — 2026-08-28

## Context

Cloud Itonami already required a user-verifying WebAuthn P-256 credential for
human authority, but its Wallet screen then asked for MetaMask, Coinbase Wallet
or another injected EIP-1193 account. Bot creation produced only an
`:awaiting-signer` container. The result had two unrelated roots of control:
the Passkey was the person, while a browser extension had to supply the usable
Wallet.

That is the opposite of the product boundary: a person who completes WebAuthn
has already created a hardware-backed asymmetric key. The authenticator keeps
the private key; the registration result gives Cloud Itonami the verified
P-256 public key needed to name a counterfactual Smart Account.

## Decision

The first verified Passkey owns one deterministic ERC-4337 Smart Account for
the Principal. Every Bot owned by that Principal receives a distinct account.
Registration creates the Principal descriptor eagerly; Wallet reads migrate
existing Principals and Bots lazily. Adding another Passkey does not silently
move an address: the initial credential id and public-key fingerprint are
pinned in the stored descriptor.

No private key, seed or WebAuthn signature is stored. Address derivation is a
pure calculation over the verified public key, Principal id and scope. The
chain id is deliberately excluded from the account nonce, so the same scope
has the same address on every EVM chain where the configured deterministic
factory exists.

The current default reproduces Coinbase Smart Wallet v1.1
`getAddress(bytes[],uint256)` locally:

- factory `0xBA5ED110eFDBa3D005bfC882d75358ACBbB85842`
- implementation `0x00000110dCdEdC9581cb5eCB8467282f2926534d`
- owner encoding: the 64-byte P-256 point `X || Y`

Those are implementation defaults, not identity authority. Both addresses and
the factory family are configuration values. Principal ids, Passkeys and
account descriptors remain Cloud Itonami data, so Kotoba can deploy the same
open contract family under its own deterministic factory without depending on
Base chain, Base Account or a Coinbase service.

MetaMask, Coinbase Wallet, kagi and other EIP-4361 accounts remain optional
Principal links for legacy assets, funding, recovery and integrations. Linking
one never overwrites a Principal or Bot Smart Account. The existing per-Bot
assignment API is retained for compatibility but is no longer the primary
receive or proposal account.

## Current proof and boundary

The local calculation is pinned to a read-only canonical-factory vector:
P-256's standard generator as owner and nonce `0` produces
`0x4bF597E75af919CDbB04505C39F4957454262011`. Tests also prove that changing
the selected chain changes the CAIP-10 view but not the EVM address, that Bot
scopes are distinct, and that an optional external link cannot replace the Bot
account.

This slice does **not** deploy an account, sponsor gas, submit an ERC-4337
UserOperation or claim that every chain contains the factory. Descriptors say
`:counterfactual`, `:not-yet-deployed` and `:user-operation-ready? false`.
Receiving and copying the address are enabled; sending remains visibly disabled
until the Passkey assertion, WebAuthn signature encoding, bundler/paymaster,
deployment receipt and chain-specific factory-availability checks are landed.

## Consequences

- A fresh user needs no wallet extension to get a Wallet address.
- A Bot has a receive/proposal account because it belongs to a Passkey
  Principal, not because an EOA was assigned later.
- Account recovery is not implied by adding a login Passkey. Before on-chain
  use, owner addition/removal and loss recovery must be implemented and tested
  as explicit Smart Account operations.
- The UI distinguishes a valid counterfactual receive address from an on-chain
  deployment and from send readiness.
- A legacy session with no verified public Passkey record fails closed as
  `:passkey-required`; it does not fall back to generating a server-held EOA.

