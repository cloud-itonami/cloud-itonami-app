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

## Domain and device portability

The WebAuthn credential, the stable Principal and the Smart Account owner are
three different things. Federation may prove the same Principal without giving
another origin the Passkey. Registering another login Passkey does not make its
different public key an owner of an existing on-chain account.

The shared WebAuthn RP is `itonami.cloud`. Its ordinary domain-suffix boundary
admits the explicit origins `itonami.cloud`, `auth.itonami.cloud` and
`app.itonami.cloud`. The resident's local recovery ceremony uses RP ID
`localhost`, so that credential is separate. `murakumo.cloud` and
`kotobase.net` are unrelated registrable domains and do not currently request
RP ID `itonami.cloud`; the three apex `/.well-known/webauthn` endpoints return
404 as of this decision update.

Cross-product identity is the stable Principal plus its Smart Account, not the
`itonami.cloud` credential. `auth.itonami.cloud` remains a convenient OAuth and
step-up provider, but it is not the only identity root. Murakumo, Kotobase and
the resident may register their own RP-scoped Passkeys and, after an existing
owner authorizes it, add those public keys to the same Smart Account. Losing or
moving one product domain therefore need not rename the Principal or account.
Cookies, private keys and credential records are not copied between apex
domains.

WebAuthn Level 3 Related Origin Requests remain an explicit alternative, not
the selected default. They would require `itonami.cloud/.well-known/webauthn`,
one common RP ID in every ceremony and matching exact-origin verification on
the server. That expands the set of sites able to invoke the credential; it is
not justified merely to make the Principal portable: the multi-RP owner set
provides that property without choosing one product domain as the permanent
root.

A synced multi-device Passkey may present the exact same public key from a new
device. Nearby-device/QR authentication may also use the old device without
copying its private key. A separately created Passkey has a new public key and
requires an explicit `addOwner` UserOperation before it can control an existing
Smart Account. The current implementation records RP provenance, exposes owner
candidates, and builds the exact unsigned replay-safe
`addOwnerPublicKey(bytes32,bytes32)` call. The current owner can authorize a
complete EntryPoint v0.6 UserOperation with WebAuthn; the app checks the chain,
factory, implementation, counterfactual address, nonce and owner index before
signing, submits through a configured bundler, compares the returned hash, and
marks the new key active only after receipt plus `isOwnerPublicKey` verification.
Owner removal, general Passkey transfers, guardians and delayed recovery remain
separate work. Email or SSO can
recover a Principal session but cannot by itself recover the on-chain signing
authority.

The user and operator procedure, including the current per-domain matrix, is
documented in [Passkey Smart Account: ドメイン共有・別端末・復旧](../passkey-smart-account.md).
The portability boundary is normative in
[ADR-0082](0082-webauthn-credentials-are-rp-scoped-smart-account-controllers.md).

## Current proof and boundary

The local calculation is pinned to a read-only canonical-factory vector:
P-256's standard generator as owner and nonce `0` produces
`0x4bF597E75af919CDbB04505C39F4957454262011`. Tests also prove that changing
the selected chain changes the CAIP-10 view but not the EVM address, that Bot
scopes are distinct, and that an optional external link cannot replace the Bot
account.

Owner addition may deploy the counterfactual account through factory `initCode`
and may use an explicitly configured paymaster. It does not claim that every
chain contains the factory: start fails before WebAuthn unless the chosen RPC
and bundler report the expected chain, EntryPoint, factory implementation and
address. `:user-operation-ready? false` still describes the general transfer
path; owner management has a separate per-chain readiness flag. Provider URLs
may contain credentials, so they remain environment/config inputs and are never
projected through `/api/wallet` or persisted with an operation.

## Consequences

- A fresh user needs no wallet extension to get a Wallet address.
- A Bot has a receive/proposal account because it belongs to a Passkey
  Principal, not because an EOA was assigned later.
- Account recovery is not implied by adding a login Passkey. Before on-chain
  use, owner-addition plans must progress through current-owner authorization,
  submission and receipt verification; removal and loss recovery remain
  explicit Smart Account operations.
- The same Principal across products does not imply the same raw credential is
  callable from every product origin. RP-scoped controllers converge at the
  Smart Account owner set; federation is an optional convenience.
- A synced copy of the original credential preserves the owner key; a newly
  created credential does not.
- The UI distinguishes a valid counterfactual receive address from an on-chain
  deployment and from send readiness.
- A legacy session with no verified public Passkey record fails closed as
  `:passkey-required`; it does not fall back to generating a server-held EOA.
