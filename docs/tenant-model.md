# Tenant and identity model

Cloud Itonami separates cryptographic identity from product naming and mail
addressing.

```text
Installation ── Session ── User (did:key)
                            │
                       Membership
                            │
                         Tenant
                            │
                    Organization profile
                      │       │       │
                   Domain   did:web  Relay alias
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
