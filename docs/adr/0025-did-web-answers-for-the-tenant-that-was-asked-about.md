# ADR-0025: `did:web` answers for the tenant that was asked about

Status: accepted and implemented

## Context

`identity/organization-domain-for-did-web` returned the first tenant it found
carrying a domain. That was defensible while a deployment held one tenant, and it
was documented as such — `docs/tenant-model.md` scopes multi-tenant hosting out
of this application.

ADR-0023 made it false. Every User now owns a personal tenant, and a claimed
personal tenant carries a domain like any other, so a deployment with one
organization and one person has two named tenants and `some` picks between them
by map order.

Two things depended on that function, and only one of them wanted a
deployment-level answer:

- **`/.well-known/did.json`** served whichever tenant came first, to any Host.
  `did:web:<domain>` resolves to `https://<domain>/.well-known/did.json`, so a
  verifier asking about `etzhayyim.example` could be handed the document of
  `owner.example`. Since both documents name the *same* Ed25519 key — this app
  signs with one issuer key — the answer would have verified. It would have been
  a valid signature on the wrong claim.
- **`membership-credential-context`** put it in every membership credential, so a
  credential issued while acting in one organization could name another as its
  issuer.

## Decision

**A DID document is resolved from the request's `Host`.**
`identity/did-web-domain-for-host` matches the hostname (port stripped) against
each tenant's domain. When nothing matches it falls back to the single named
tenant, and only when there is exactly one — a request to `localhost` is how this
is developed, and refusing it would make the document unreachable exactly where
it is being written. With several named tenants there is no fallback: guessing
which organization a verifier meant is how a key gets published under somebody
else's name.

**A membership credential names the tenant it was issued in.**
`membership-credential-context` returns the active tenant's `:domain` when the
profile publishes `did:web`, and nil otherwise — at which point `credential`
falls back to the issuer's `did:key`, as it already did.

**A deployment-level artifact names no tenant when several exist.**
`organization-domain-for-did-web` keeps its name and its caller — the revocation
status list, which is one signed list covering every credential issued here
regardless of tenant — but now answers only when exactly one tenant is named. Any
other case is nil, so the status list is signed under the issuer's `did:key`.
That is always resolvable and belongs to no tenant in particular, which is what a
deployment-level artifact needs.

## Consequences

- Publication is still a deployment step. Serving a document per Host does not
  make DNS point at this process, and `publish-did-web?` is still false as
  shipped.
- A deployment that previously served one tenant's document at every Host now
  serves it only at that tenant's own name and at unmatched hosts. No document
  is served for a name this deployment does not have a tenant for, which was
  already true and is now true for the right reason.
- The status list's issuer changes from an arbitrary tenant's `did:web` to the
  `did:key` on any deployment with more than one named tenant. Credentials
  issued before this keep naming whatever they named; the key is the same either
  way, so verification is unaffected.
- Nothing here makes this multi-tenant hosting. One process still holds one data
  directory and one issuer key. What changed is that it no longer *claims* a
  tenant it was not asked about.

## Verified

`clojure -M:test` — 1216 tests, 4968 assertions, 0 failures, 0 errors.

`core-test/did-web-answers-for-the-tenant-that-was-asked-about` seeds two named
tenants and asserts each Host resolves to its own domain, that a port is
stripped, that an unmatched Host resolves to nothing, that the deployment-level
function names no tenant, and that a membership credential issued in either
tenant names that tenant. `credential-http-test` still covers the route itself
end to end over HTTP, including the 404 while `publish-did-web?` is false.

**Not verified: a real resolution.** `did:web:<domain>` resolving to this
process is a DNS and TLS fact, and no test here can establish it — which is the
same boundary the document already had before this ADR.
