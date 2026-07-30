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

## gftd distribution

The gftd profile is one distribution of the same application:

- `gftd` maps to the verified corporate apex `gftd.ai`;
- other managed organizations map to `{id}.gftd.ai`;
- account handles map to `{handle}@gftd.ai`;
- relay delivery remains a separate, authenticated service boundary.

No gftd-specific behavior is required by the core namespaces.
