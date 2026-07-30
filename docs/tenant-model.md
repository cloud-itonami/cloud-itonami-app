# Tenant and identity model

Cloud Itonami separates cryptographic identity from product naming and mail
addressing.

```text
Installation ── Session ── User (did:key)
                            │
                       Membership
                            │
                         Tenant
                      ┌─────┴─────┐
                      │           │
           Organization profile  WorkerAssignment
                      │       │       │
                   Domain   did:web  Relay alias
                                      │
                               Artificial organism
```

## User

A User is created provisionally, then activated by a verified Passkey
registration. Its first P-256 public key determines its `did:key`. Display
name, mail address, membership, and Organization changes do not change that
DID.

## Tenant

A Tenant has an internal UUID. This remains stable even if its Organization
ID, legal name, domain, or branding changes. Memberships always reference the
internal Tenant ID.

## Worker assignment

A worker assignment relates a non-human service or artificial organism to a
Tenant without pretending it is a User. An artificial-organism worker retains
an external identity, supervisor, lifecycle, memory, and authority. The
assignment grants only named interaction capabilities and can be revoked
without rewriting organism history.

Human roles continue to use Membership. Model calls created inside this
application are WorkerRuns, not organization identities and not artificial
organisms.

One User may hold memberships in multiple Tenants. A session records exactly
one active membership at a time, so workspace reads, OAuth connections, AO
workers, and mutations cannot accidentally combine organizations. The
organization switcher changes only that session's active membership after
membership proof.

Authenticated users may create another Organization and become its owner.
Inviting a new User into the active Organization continues to use a one-time
enrollment code. When the account ID already identifies a registered User,
the owner instead issues a one-time, expiring Organization invitation. The
invited User must authenticate with a Passkey and explicitly accept the code;
only then is the new Membership created and selected for that session.
Matching an email address alone never grants Membership.

## Organization ID and domain

An Organization ID is a globally reserved human-readable slug within a
deployment. It may be used to derive a managed domain. A custom or apex domain
must pass deployment-specific DNS/HTTPS verification.

`did:web` remains absent until the deployment is configured to publish DID
documents for managed domains. Configuration alone does not prove ownership.

The application now *serves* that document: with `:publish-did-web? true` and an
Organization ID claimed, `GET /.well-known/did.json` returns a DID document whose
`assertionMethod` is the Ed25519 key credentials are signed with
(`cloud.itonami.app.credential/did-web-document`). What is still a deployment
responsibility is making `https://<domain>/.well-known/did.json` actually resolve
to this process — serving a document and controlling the domain that names it are
different things, and only the second proves ownership.

Until the domain resolves, credentials are signed under the issuer's `did:key`
rather than an unpublished `did:web`, so they stay verifiable by anyone instead of
naming an address that answers nothing.

## Organization membership as a credential

Membership is a row in this server's state *and* a W3C Verifiable Credential the
holder can carry elsewhere:

```text
issuer   = organization did:web (or the issuer did:key until it resolves)
subject  = User did:key            (P-256, from the Passkey)
proof    = DataIntegrityProof / eddsa-jcs-2022   (Ed25519, the app's issuer key)
status   = BitstringStatusListEntry -> /credentials/status/1
```

The subject's key and the signing key are different curves on purpose: a subject
is *named*, not a signer, so a P-256 Passkey DID is a perfectly good subject. The
consequence is recorded rather than hidden — a **holder-signed Verifiable
Presentation is not implemented**, because WebAuthn signs its own
`authenticatorData || clientDataHash` and cannot produce a Data Integrity proof
over a canonicalized document at all. See
`cloud.itonami.app.credential` for the full reasoning.

Revocation flips one bit in a signed status list, so a credential can be
withdrawn before it expires. `verify` reports `:verified` and `:valid?`
separately: a revoked credential is still correctly signed, and gating on
`:verified` alone is how one gets honoured after withdrawal.

## gftd distribution

The gftd profile is one distribution of the same application:

- `gftd` maps to the verified corporate apex `gftd.ai`;
- other managed organizations map to `{id}.gftd.ai`;
- account handles map to `{handle}@gftd.ai`;
- relay delivery remains a separate, authenticated service boundary.

No gftd-specific behavior is required by the core namespaces.

## Passkey and Wallet Account Links

The User DID is the durable product subject. Passkey credentials authenticate
that subject; Wallet addresses are replaceable linked accounts:

```text
User DID
├── Passkey credential A
├── Passkey credential B
├── eip155:1:0x... (active)
└── eip155:8453:0x... (revoked)
```

The app never copies or derives one private key from another. EIP-6963
discovers injected providers such as Coinbase Wallet and MetaMask. EIP-4361
proves address ownership, while CAIP-10 supplies the chain-qualified account
identifier. Storage quota and organization membership remain keyed to the User
DID, so Wallet rotation does not move or duplicate entitlements.
