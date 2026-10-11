# ADR-0099: A membership credential is bound to a key the holder keeps

**Status:** proposed — 2026-10-10. Companion to ADR-0098: keys and credentials
stay with the person, not with the company.

## Context

`cloud.itonami.app.credential-sd-jwt` issues organization membership as an
SD-JWT VC. It sits beside the W3C Data Integrity VC in
`cloud.itonami.app.credential`.

The current design:

- `sub` is the one selectively disclosable claim, so a holder can prove
  "someone at this organization is an auditor" without saying who.
- `role` and `organization` are deliberately not concealable. A membership
  credential that could withhold its role would assert nothing.

Measured 2026-10-10, three things keep this from being a credential the person
actually holds:

1. **It is bearer-presentable.** `cnf` is omitted, and `verify` returns
   `:bearer-presentable? true` (`credential_sd_jwt.cljk:23-35`, `:138`).
   Whoever has the token can present it. The namespace calls holder binding "a
   decision nobody has taken". **This ADR takes it.**
2. **It lives in the app.** `present` is offered server-side "because this app is
   also where a holder's credential currently lives" (`credential_sd_jwt.cljk:148-152`).
3. **Withholding `sub` does not make presentations unlinkable.** Two verifiers
   shown the same token see:
   - the same issuer signature;
   - the same `_sd` digests;
   - the same status-list index.

   Any of these is a correlation handle. With `sub` withheld, a verifier cannot
   tell *who* the holder is, but colluding verifiers can still tell it is *the
   same* holder. The namespace docstring promises more privacy than the format
   currently delivers.

The Data Integrity path cannot be holder-signed with a Passkey. WebAuthn signs
its own `authenticatorData || clientDataHash` (`README.md:47-51`). The holder key
therefore cannot be the Passkey. It has to be a key the Passkey session merely
*authorizes*.

## Decision

### 1. The holder key is generated where the holder is, and never leaves

At issuance, the holder runtime generates an ES256 (P-256) key pair. That
runtime is one of:

- the **built-in web wallet**, which uses WebCrypto with `extractable: false`
  and stores the key in IndexedDB. This is the pattern `signal.js` already uses
  for messaging keys.
- the **desktop app**, using the platform keystore.
- an **external wallet**.

The server never generates, receives or stores the private key.

The issuance request carries:

- the public JWK;
- a proof-of-possession JWT signed by that key over a fresh server nonce, with
  `aud` set to the issuer. This is the shape of the OpenID4VCI `proof`.

The existing route requirements are unchanged. A human Passkey session is
required (`server.cljk:3385-3393`). The Passkey authorizes the issuance; it is
not the holder key.

### 2. Every issued credential is holder-bound

`cnf.jwk` carries the holder public key. `verify` then requires a KB-JWT that
meets four conditions:

- its signature verifies under `cnf.jwk`;
- `aud` equals the verifier;
- `nonce` equals the verifier's challenge;
- `sd_hash` covers exactly the disclosures presented, and `iat` is within a
  short window.

The result reports `:holder-bound? true`. A missing or invalid KB-JWT is a
refusal, not a downgrade.

Tokens issued before this ADR remain verifiable. They report
`:bearer-presentable? true` exactly as they do today, and are not re-labelled.

### 3. Issue in batches, use once per verifier

A single token cannot be unlinkable, so the wallet requests a **batch**.
Default: 8 credentials. Each credential has its own:

- holder key;
- salts;
- status-list index.

The wallet presents a given credential to one verifier only. It requests a new
batch when it runs low, which needs a Passkey session again. The batch size and
the refill threshold are wallet settings. The issuer limits issuance rate per
person.

The disclosure set does not change: `sub` is disclosable; `role`,
`organization` and `organization_name` are not. This ADR fixes linkability; it
does not reopen what the credential asserts.

### 4. The server keeps an issuance record, not the credential

The issuer persists, per credential:

- status index;
- `iat` and `exp`;
- the SHA-256 thumbprint of `cnf.jwk`;
- the subject DID.

These are kept so the issuer can revoke. The issuer does not persist the token,
the salts or the disclosures. `present` moves to the client. The server-side
function is removed rather than kept as a convenience, because keeping it would
re-establish the server as where the credential lives.

Verification routes (`verify-presented` and `credential-trust/verify-external`)
do not persist presented tokens. They log the result, the `vct`, the issuer and
the refusal reason only.

### 5. The Data Integrity path is unchanged, and the README is narrowed

The W3C VC path keeps its server issuer key and its full disclosure. It stays
the format for verifiers that need JSON-LD Data Integrity. The README sentence
on holder-signed presentations is narrowed:

- holder-signed presentation now exists for SD-JWT VCs, through KB-JWT;
- for Data Integrity VPs it remains structurally absent while the only
  authenticator is a Passkey.

The stale docstring at `credential.cljk:29-31` is corrected in the same change.
It says the subject `did:key` is derived from a WebAuthn credential; since
ADR-0064, the subject is the User DID.

## Consequences

- **A stolen token alone is worthless.** Presenting it needs the holder key,
  which is non-extractable on the holder's device. That is the property the
  bearer token lacked.
- **Credentials are device-bound.** A new device, or cleared site data, means
  re-issuance under a Passkey session. That is cheap, and it is the intended
  recovery. There is no server-side copy to restore from, by design.
- **Unlinkability is per credential, not absolute.**
  - Status-list privacy depends on the list's size, the herd. A small
    organization's list identifies more than a large one.
  - The issuer still learns at issuance that a person requested a batch.
  - Predicate proofs (BBS, ZKP), such as "member for more than a year" without
    a date, are not adopted. The membership credential has no claim that needs
    one.
- **Verifiers must do more work.** A verifier that ignores the KB-JWT gets a
  refusal from this app's verifier, not a silent pass. External verifiers
  implementing SD-JWT VC with key binding need nothing app-specific.

## Verification (for the implementation, not this draft)

- **Issuance proof.** Issuance without a valid proof-of-possession JWT over the
  current nonce is refused. A proof replayed with an old nonce is refused.
- **Holder binding.** A token presented without a KB-JWT, with a KB-JWT under
  another key, with the wrong `aud` or `nonce`, or with an `sd_hash` that does
  not match the disclosures is refused.
- **Batch distinctness.** Credentials in one batch differ pairwise in issuer
  signature, `_sd` digests, `cnf` thumbprint and status index.
- **Server storage.** No persisted store contains a token or a disclosure after
  issuance or verification. This is checked by scanning the store in the test
  fixture.
