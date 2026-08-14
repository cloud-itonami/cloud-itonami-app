# Tenant and identity model

Cloud Itonami separates cryptographic identity from product naming and mail
addressing.

```text
Installation ── Session ── User (did:key)
                            │
                       Membership
                            │
                         Tenant ── kind: personal | organization
                      ┌─────┴─────┐
                      │           │
           Organization profile  WorkerAssignment
                      │       │       │
                   Domain   did:web  Relay alias
                                      │
                               Artificial organism
```

A Tenant is either a person's own namespace or an organization. Both are
Tenants, so everything scoped by tenant — workspaces, connections, governed
Kanban partitions, repository owner ids — behaves the same in either. What
differs is who may be in one. See ADR-0023.

## User

A User is created provisionally, then activated by a verified Passkey
registration. Its first P-256 public key determines its `did:key`. Display
name, mail address, membership, and Organization changes do not change that
DID.

## Tenant

A Tenant has an internal UUID. This remains stable even if its Organization
ID, legal name, domain, or branding changes. Memberships always reference the
internal Tenant ID.

## Personal tenant

Every User owns exactly one personal tenant, created with the User. It is the
person's own namespace and it stands beside organizations rather than inside
one — the arrangement GitHub has, where a personal account is an owner in the
same namespace as an organization. There is no "primary organization".

Its slug is the User's account ID: one string, one owner. A slug names a Tenant
or a User and never both, because `<slug>.<organization-domain-suffix>` and
`<slug>@<account-domain>` are derived from those two and would otherwise
address two different parties. The personal tenant is the single exception, and
only because it holds its owner's handle by construction.

A personal tenant has exactly one member. `add-user!` refuses it: a second
person inside a tenant named after the first would be working under someone
else's name. Agent access across a tenant boundary uses a tenant connection
(ADR-0014) — approved, capability-scoped, expiring — not a membership.

Handing work *over* is a different act from reading across, and it has its own
operation: `POST /api/projects/{project}/transfer` moves one project to another
tenant the caller administers (ADR-0024). It requires owner or admin on both
sides and a browser Passkey session — an agent holding a connection to a tenant
must not be able to move a project into it — and refuses a project anything has
been published from, because that ciphertext is encrypted under a key bound to
the storage owner it would be leaving. Mail filed against the project stays in
the tenant it was filed in, and the receipt says how much.

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

Where a *new* session lands is the User's `:default-membership-id`: the tenant
they last selected, set at registration and updated by the switcher and by
accepting an invitation. It falls back to the oldest membership. It is never
map order — which is what it was before ADR-0023, so which organization a
person signed in to was decided by an insertion order they never saw.

Governed Kanban state adds a physical boundary to this logical ACL. Its
organization graph, approval policy, WorkItems, decisions and receipts are
stored in a generation-pinned EDN file dedicated to that organization under
`data/work-governance/`. A global manifest is the atomic commit point. File
partitioning does not replace membership checks: the HTTP projection still
filters by the session's active organization, and owner/admin authority is
required for graph and policy mutations.

Authenticated users may create another Organization and become its owner.
Inviting a new User into the active Organization continues to use a one-time
enrollment code. When the account ID already identifies a registered User,
the owner instead issues a one-time, expiring Organization invitation. The
invited User must authenticate with a Passkey and explicitly accept the code;
only then is the new Membership created and selected for that session.
Matching an email address alone never grants Membership.

## Organization ID and domain

An Organization ID is a globally reserved human-readable slug within a
deployment — reserved against User account IDs as well as other Tenants, since
they share one namespace. It may be used to derive a managed domain. A custom or
apex domain must pass deployment-specific DNS/HTTPS verification.

`did:web` remains absent until the deployment is configured to publish DID
documents for managed domains. Configuration alone does not prove ownership.

The application now *serves* that document: with `:publish-did-web? true` and an
Organization ID claimed, `GET /.well-known/did.json` returns a DID document whose
`assertionMethod` is the Ed25519 key credentials are signed with
(`cloud.itonami.app.credential/did-web-document`). **Which** document depends on
the request's `Host`, because that is what `did:web:<domain>` asked for; with
several named tenants an unmatched Host gets nothing rather than a guess, and a
membership credential names the tenant it was issued in rather than the first
one this deployment happens to hold (ADR-0025). What is still a deployment
responsibility is making `https://<domain>/.well-known/did.json` actually resolve
to this process — serving a document and controlling the domain that names it are
different things, and only the second proves ownership.

Until the domain resolves, credentials are signed under the issuer's `did:key`
rather than an unpublished `did:web`, so they stay verifiable by anyone instead of
naming an address that answers nothing.

### What a custom domain does today, and what it is designed to do

An owner can prove control of a company domain — `domain_verification` issues a
TXT challenge at `_itonami-verification.<domain>` and reads public DNS to confirm
it, exclusively across tenants. **That proof currently names nothing.** It writes
`:verified-domain` on the tenant, which no code reads; the tenant's `:domain`
stays the managed `<slug>.<organization-domain-suffix>`, or whatever
`:organization-domain-overrides` asserts. So the sentence above about
configuration not proving ownership is true, and the field that carries a proof
is not the field that names the tenant.

ADR-0043 is the accepted design that joins them: one `:domain` with a
`:domain-source`, a binding lifecycle of `:pending → :claimed → :live → :lapsed`,
and two gates — the existing TXT proof for the naming right, and a self-probe
over the hardened fetch in `credential-trust` for the fact that this process
actually answers at the name. **It is not implemented.** Until it is, treat a
verified domain as a record that a proof happened and not as a name.

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
