# ADR-0043: A tenant is named by a proof, not by configuration

**Status:** accepted — 2026-08-14. Design only; nothing below is implemented.

## Context

Four things in this application call themselves a tenant's domain. They do not
agree, and the only one carrying a proof is the only one nothing reads.

| field | where it comes from | what it proves | who reads it |
|---|---|---|---|
| `:domain` | derived: `<slug>.<organization-domain-suffix>`, or `:organization-domain-overrides` | nothing | `did:web`, membership credentials, Host→tenant resolution — **everything** |
| `:contact-domain` | free text passed to `register!` | nothing | nothing. It is selected into the client payload and no script reads it |
| `:verified-domain` / `:verified-domains` | DNS TXT at `_itonami-verification.<domain>` | **control of the zone** | nothing. Two writes in `domain_verification.clj` and one assertion in its test |
| `:organization-domain-overrides` | deployment config — `profiles/gftd.edn` carries `{"gftd" "gftd.ai"}` | nothing | `identity/organization-domain` |

`docs/tenant-model.md` already states the rule that this arrangement breaks:
"Configuration alone does not prove ownership." The override is configuration
naming a domain. The TXT challenge is a proof naming nothing.

Three smaller faults come from the same gap:

- **`create-organization!` accepts no domain at all**, while `register!` accepts
  one, stores it as `:contact-domain`, and — when no Organization ID is given —
  derives the slug from its first label. Two ways to create a tenant, two
  different answers to the same question.
- **The managed-domain guard protects a name this deployment does not issue.**
  `domain_verification` refuses `itonami.cloud` and its subdomains as a literal,
  while `:organization-domain-suffix` ships as `cloud-itonami.app` and
  `profiles/itonami.edn` does not override it. The names actually issued are
  `<slug>.cloud-itonami.app`, which the guard does not cover. Nobody can pass a
  challenge for one — the TXT would have to be written into the operator's own
  zone — so this is not an open door. It is a guard reading a literal instead of
  the profile, which is the same class of mistake as naming by configuration.
- **A verified domain never expires.** `verify!` stamps `:verified-at` once.
  Nothing re-reads DNS afterwards, which is tolerable while the field is inert
  and is not once it decides what a tenant is called.

## The two facts this ADR refuses to conflate

1. **The naming right** — this tenant may be called `example.co.jp`. A TXT
   record under that zone proves it. This exists today.
2. **The resolution fact** — `https://example.co.jp/.well-known/did.json`
   actually reaches this process. A TXT record proves nothing about it.

Publishing `did:web:example.co.jp` requires both. ADR-0025 said the second half
out loud already — "serving a document and controlling the domain that names it
are different things" — and left it as a deployment step. That was right while
the only publishable name was a subdomain of the deployment's own suffix, where
the deployment owns the zone by construction. An override like `gftd → gftd.ai`
is what breaks it: configuration asserting a name the process may not answer for.

A tenant that gets `:domain` from a TXT proof alone would publish a `did:web`
that answers nothing, which is exactly what `membership-credential-context`
already refuses to do when it falls back to the issuer `did:key` rather than
"naming an address that answers nothing."

## Decision

**1. One field carries the name, and a proof is the only thing that sets it.**
`:domain` stops being re-derived at each write. It is accompanied by
`:domain-source` ∈ `#{:managed :verified}`:

- `:managed` — `<slug>.<organization-domain-suffix>`. The deployment owns its own
  suffix by construction; that is the proof for this case.
- `:verified` — a domain that passed both gates below.

`configure-organization!` and `create-organization!` set `:managed` and never
overwrite a `:verified` name, which they would do today by re-deriving.

**2. A binding is a lifecycle, not a boolean.** The record in
`:identity :domain-verifications` gains states:

`:pending` (challenge issued) → `:claimed` (TXT observed; the name is now
reserved to this tenant and to no other; it is **not** yet the tenant's name) →
`:live` (the deployment answers at that name; `:domain` **is** this) →
`:lapsed` (a later check failed; `:domain` reverts to the managed name).

Today's `:verified` becomes `:claimed`. Nothing is renamed downward: a claim
that never becomes live simply never names anything, which is what it means
today.

**3. Gate B is a self-probe, and it reuses the fetch this app already hardened.**
`credential-trust/fetch-json` exists for resolving other issuers' `did:web`
documents: HTTPS only, redirects never followed, a hard timeout, a response size
cap, and a refusal to talk to an address resolving inside this network. That is
precisely the shape an outbound probe to a customer-supplied hostname needs, and
no second egress path is added for this.

The probe fetches `https://<domain>/.well-known/itonami-domain-binding.json`.
This deployment serves that route publicly — it holds a per-binding random nonce
and no secret, like `/.well-known/did.json` — and answers **only** for a binding
whose domain equals the request `Host` and whose state is already `:claimed`.
So an attacker who points their own DNS at this deployment gets nothing: they
would have to pass Gate A for that name first. The nonce coming back is the
proof, because only this process knows it.

Passing Gate B is a strictly stronger statement of control than the TXT record —
it requires DNS pointing here *and* a publicly trusted certificate for the name.
Gate A still earns its place, for ordering and for arbitration: a tenant must be
able to reserve a name before cutting production DNS over to it, and two tenants
wanting the same name must be separated before either does.

**4. `:organization-domain-overrides` is retired as a naming mechanism.** It
becomes a seed: on startup an override materialises a `:pending` binding for that
tenant. The operator publishes one TXT record in a zone they already control and
points the name here — the same two gates as everyone else. Configuration
chooses the candidate; it no longer confers the name.

**5. Creation may name a domain, and naming is never binding.** Both `register!`
and `create-organization!` accept `:domain`, and both answer with the TXT record
to publish. `:contact-domain` is retired into that `:pending` binding rather than
staying a field nothing reads. The tenant's name at creation is always
`:managed` — which is the honest answer to "can I attach DNS when I create an
org": you can *start* it there, and it becomes a name when it is proven.

**6. Host→tenant resolution is untouched.** `did-web-domain-for-host` keeps
matching `:domain`, because `:domain` now *is* the live custom name when there is
one. ADR-0025's rule — the document served is the one the `Host` asked about, no
guessing across tenants — survives without an edit. That is the payoff of
putting the name in one field instead of a fifth one.

**7. The promotion rule is a decision core.** `domain_binding_core.kotoba`, in
the shape `approval_core.kotoba` established: a `:record` of booleans the host
has already established — `owner-authorized`, `challenge-unexpired`,
`txt-observed`, `claim-exclusive`, `probe-answered`, `probe-confidential`,
`probe-fresh`, `publication-enabled` — returning an `:i64` state. DNS, HTTP,
collections and every `throw` stay on the host side, as they do in every other
core here. The guard of item 2 in Context becomes an argument rather than a
literal: whether a name is the deployment's own managed suffix is a fact the
host derives from the profile and passes in.

**8. A live name is re-probed, and a failure demotes it.** `:lapsed` reverts the
tenant to its managed name and stops issuing under the old one. It retracts
nothing: credentials already issued name the domain that was live when they were
issued and are signed by the same issuer key either way. Nothing here is
revocation, and this ADR does not add one.

## Out of scope

**Mail.** `<handle>@<custom-domain>` is a different authority — sending as a
domain needs SPF and DKIM alignment, which `mail_authentication.clj` already
computes for *inbound* messages, and which a TXT proof of naming does not
establish. `account-domain` stays deployment-level here.

**Multi-tenant hosting.** As ADR-0025 put it, one process still holds one data
directory and one issuer key. This lets a tenant be *named* by a domain it
proved. It does not make this a host for tenants that do not trust each other.

**Certificate issuance.** Whether this deployment can terminate TLS for a
customer's name is an operator fact. Gate B fails until it is true, which is the
honest reading, and is the same boundary ADR-0025 drew.

## Consequences

- Two write-only fields disappear. `:contact-domain` becomes a pending claim and
  `:verified-domain` becomes the binding's own state, so the four rows in the
  Context table collapse to one name plus one source.
- `profiles/gftd.edn` stops naming `gftd.ai` by assertion. Until that deployment
  publishes a TXT and points the name here, the tenant reads as
  `gftd.<organization-domain-suffix>`. That is a visible change in the identity
  card and it is the correct one: the old display was a configuration file's
  opinion.
- `publish-did-web?` remains a deployment switch, and it is now the *only*
  remaining unproven input to publication — `:managed` names rest on the
  deployment owning its suffix, and `:verified` names rest on two proofs.
- A custom domain becomes visible to the outside world at
  `/.well-known/itonami-domain-binding.json`, for `:claimed` bindings only. It
  discloses that this deployment holds a claim on a name whose DNS already
  points here, to a caller who already knows the name.
- Outbound HTTP happens on a schedule for the first time in this application
  (the re-probe). `credential-trust`'s fetch already refuses internal addresses,
  so the new surface is timing, not reach.

## Alternatives

**Promote `:verified-domain` into `:domain` and stop.** One gate, a few lines,
and it publishes `did:web` names that resolve to nothing the moment a tenant
proves a domain it has not pointed here — which is the exact failure
`membership-credential-context` already goes out of its way to avoid.

**Keep the override and mark it "operator-asserted".** Cheaper, and it preserves
a second way to be named that carries no proof. The whole complaint in Context is
that there are several; adding a label to one of them does not reduce the count.

**Detect the resolution fact by observing an inbound `Host` header instead of
probing.** Free, and worthless: the `Host` header is written by the caller. It
proves that somebody typed a name, not that the name resolves here.

**Model the binding as a fifth field and leave `:domain` derived.** Avoids
touching `configure-organization!`, and puts Host→tenant resolution in the
position of consulting two fields and deciding which wins — that decision is
exactly what ADR-0025 removed from `did:web` resolution.

## Verified

**Nothing. This ADR is a design.** No code in this repository implements any of
the eight items above, and the four fields described in Context are the state as
of `ffd9e2b`.

What implementation will have to show, in the shape this repository already
requires: a `domain_binding_kotoba_parity_test` against the core; a probe test
that a `:claimed` binding for one tenant does not answer for another tenant's
`Host`; a test that a `:live` binding survives `configure-organization!` being
called again; and a demotion test that `:lapsed` reverts `:domain` while leaving
an already-issued credential's issuer untouched.

**Not verifiable here, as before:** that DNS and TLS actually resolve a customer
name to this process. Gate B measures it rather than assuming it, which is the
only change this ADR makes to that boundary.
