# ADR-0064: DID is the axis; a Passkey binds to it

**Status:** accepted — 2026-08-20

## Context

Until this ADR a local User had no DID until the first P-256 Passkey finished
registration, and that credential's COSE `did:key` *was* the person. Replacing
the phone minted a new personality. Hosted sign-in at `auth.itonami.cloud`
already returned a `did:` subject (`did:web:kotobase.net:…`), then threw it
away and waited for a this-device Passkey to invent another name.

Workspace ADR-2608197300 already refused that shape for Kotoba: DID is the
holder (`:cap/holder`); Passkey / TouchID / TOTP / CACAO bind *to* it;
add/revoke must not move it. `kotoba-lang/identity` `identity.authenticators`
is the substrate (`did-survives-binding-changes`). This app still implemented
the rejected alternative.

Two curves made the collapse worse. WebAuthn here is ES256/P-256.
Kotoba DID/VC/CACAO is Ed25519. Drive already minted CACAO with a *host*
Ed25519 seed because the person's Passkey cannot sign SIWE. Treating the
Passkey as the User DID therefore named the person with a key they cannot
use for the rest of the stack.

## Decision

**The User DID is assigned at User creation. A Passkey is an authenticator
bound to that DID. Enrolling or replacing a Passkey does not move it.**

1. **Hosted `did:` subject is the User DID** when `complete-central-authentication!`
   creates the person on an empty install. Linking never overwrites an axis
   that already exists (first DID wins).
2. **Local-only creation** (`register!`, Email/SSO signup without a `did:`
   subject) mints an Ed25519 `did:key`. The seed is a 0600 file under
   `identity/<user-id>.ed25519` beside state, never `state.edn`, never derived
   from a Passkey.
3. **Passkey enrolment** stores the COSE `did:key` on the Passkey *record*
   (credential identity) and an `identity.authenticators/binding` under
   `:identity :authenticators`. It fills a blank User DID only for stores
   written before this ADR. It does not replace a DID that is already there.
4. **Acting is still step-up.** `may-act?` is unchanged: a federated session
   still needs phishing-resistant + `[:webauthn]`; a browser still needs a
   Passkey ceremony. Identity can exist without acting. OAuth connections
   require `may-act?`, not mere DID presence.
5. **esign names the person.** A commitment's signer DID is the User DID.
   Lookup is User DID → user-id → that user's live Passkeys. Verification
   uses the `credential-id` from the ceremony. The credential's COSE DID
   matching the signer DID is no longer the check.

Legacy `ensure-did-links!` may still backfill a User DID from a Passkey COSE
key when `passkey-enrolled?` and `:did` is nil. That is repair, not the
creation path.

## Consequences

- A cancelled system prompt still leaves a User (`:pending-passkey`). That
  User now has a DID. The surface still says Passkey is required to act
  (ADR-0031). What it no longer says is that the person does not exist.
- ADR-0012 (email is a session proof, not an identity root) stands. Passkey
  is the same kind of thing: a factor, not a root.
- ADR-0041's "local Passkey DID minting" is no longer the creation path.
  Local minting is Ed25519 at User creation; Passkey registration binds.
- Membership credentials name the User DID as subject. That DID is no longer
  "P-256, from the Passkey" (`docs/tenant-model.md`). A holder-signed VP is
  still unimplemented: WebAuthn still cannot produce a Data Integrity proof.
- Drive-issued CACAO remains Drive-attested, not user-issued. The person's
  Ed25519 seed existing is not this layer starting to mint as them.
- `deps.edn` pins `kotoba-lang/identity` at a revision that ships
  `identity.authenticators`.
- Runtime for these judgements is Kotoba + cljs (ADR-0065): `identity_core.kotoba`,
  no Yubico.
