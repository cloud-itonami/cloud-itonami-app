# ADR-0043: A tenant is named by a proof, not by configuration

**Status:** accepted and implemented — 2026-08-14.

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

**4. `:organization-domain-overrides` is removed.** The first draft kept it as a
seed that would materialise a `:pending` binding at startup; implementing that
required an organization record that need not exist when the profile is read, to
save the operator one form. The key is gone from
`resources/cloud-itonami-app.defaults.edn` and from `profiles/gftd.edn`, which
now carries the migration in a comment: publish one TXT record in a zone you
already hold, point the name here, activate. The same two gates as everyone
else. Configuration no longer confers a name at all.

**5. Creation may name a domain, and naming is never binding.**
`create-organization!` accepts `:domain` and answers with the TXT record to
publish for the tenant it just made. The tenant's name at creation is always
`:managed` — which is the honest answer to "can I attach DNS when I create an
org": you can *start* it there, and it becomes a name when it is proven.

`register!` is the exception, decided while implementing this: it accepted a
domain too, and it cannot authorize a claim, because a registrant has no Passkey
yet and `owner-authorized` is derived from a human Passkey session. Issuing
challenges on an unauthenticated call is a write anyone can cause. So `register!`
keeps reading `domain` for the slug, as it always did, and simply stops writing
`:contact-domain` — the field nothing read. The owner starts the claim from the
settings card once enrolled. `create-organization!` has no such problem: its
first line already requires a Passkey session.

**6. Host→tenant resolution is untouched.** `did-web-domain-for-host` keeps
matching `:domain`, because `:domain` now *is* the live custom name when there is
one. ADR-0025's rule — the document served is the one the `Host` asked about, no
guessing across tenants — survives without an edit. That is the payoff of
putting the name in one field instead of a fifth one.

**7. The promotion rule is a decision core.** `domain_binding_core.kotoba`, in
the shape `approval_core.kotoba` established: a `:record` of eight booleans the
host has already established — `owner-authorized`, `txt-observed`,
`claim-exclusive`, `probe-answered`, `probe-confidential`, `probe-fresh`,
`name-is-service-owned`, `previously-live` — returning an `:i64` state. DNS,
HTTP, collections and every `throw` stay on the host side, as they do in every
other core here. The guard of item 2 in Context becomes an argument rather than
a literal: whether a name is one this deployment already speaks for is derived
from the profile — its managed suffix, its account domain, and the origin it
serves itself on — and passed in.

Two facts named in the first draft of this ADR are not in the record, and the
reasons are worth keeping. `challenge-unexpired` bounds how long an *unproven*
challenge stays answerable; folding it in would have expired a claim that had
already been proven, so it is a host-side refusal in `claim!`.
`publication-enabled` would have duplicated `publish-did-web?`, which already
gates `identity/did-for-domain`; putting it here as well would have produced an
export nothing calls, which is the same emptiness as a gate that never fails.

`previously-live` replaced them, and is the one fact the first draft was missing:
without it the core cannot tell `:lapsed` from `:claimed`, because a binding
that stops answering still holds its TXT record.

The core also owns admission of the one public route this binding has
(`nonce-route?`), rather than a fourth route core beside ADR-0038/0039/0040 —
same subject, same file.

**8. A live name is re-probed, and a failure demotes it.** `:lapsed` reverts the
tenant to its managed name and stops issuing under the old one. It retracts
nothing: credentials already issued name the domain that was live when they were
issued and are signed by the same issuer key either way. Nothing here is
revocation, and this ADR does not add one.

**Both the owner and a timer re-probe.** `recheck!` is a route, so an owner who
has just repointed DNS does not have to wait out an interval; `binding-sweep`
runs the same measurement on a schedule (ADR-0044).

This paragraph used to say the opposite — that this application had no scheduler
to hang a periodic check on, so the owner was the only trigger. **That was
simply wrong.** `updater`, `mail-sync`, `folder-sync` and `work-reconciler` all
run a `ScheduledExecutorService` from `server/start!`, and the sweep now follows
`updater` exactly. The sentence was written from memory instead of from the
source, and it is left here rather than deleted because a limit that does not
exist is worse than an unimplemented feature: nobody goes looking for it again.

## Out of scope

**Mail.** `<handle>@<custom-domain>` is a different authority — sending as a
domain needs SPF and DKIM alignment, which `mail_authentication.clj` already
computes for *inbound* messages, and which a TXT proof of naming does not
establish. `account-domain` stays deployment-level here.

That reasoning did not change; what changed is that the other authority now
exists beside this one. ADR-0044 proves it separately, from its own three
records, and holding either binding still confers nothing about the other.

**Multi-tenant hosting.** As ADR-0025 put it, one process still holds one data
directory and one issuer key. This lets a tenant be *named* by a domain it
proved. It does not make this a host for tenants that do not trust each other.

**Certificate issuance.** Whether this deployment can terminate TLS for a
customer's name was an operator fact when this was written. Gate B fails until
it is true, which is the honest reading, and is the same boundary ADR-0025 drew.

ADR-0045 moved it: the deployment now orders its own certificates over ACME, for
exactly the domains Gate B has already proven it answers at — HTTP-01 needs the
CA to fetch a URL at that name, so this proof is the precondition. Gate B still
measures rather than assumes, which is what this paragraph was really about.

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
- Outbound HTTP now happens against a hostname a *tenant* supplied, which is new.
  It reuses `credential-trust/fetch-json` rather than adding a second path, so
  HTTPS-only, no redirects, a timeout, a size cap and the refusal of addresses
  inside this network all apply unchanged. Nothing here runs on a timer (item 8).
- `/api/identity/domain-verifications/verify` is gone, replaced by `/claim`,
  `/activate` and `/recheck`. The old name said one proof finished the job.
  `resources/cloud-itonami-app.commands.edn` is regenerated accordingly.
- The stored record's schema is `…domain-verifications.v2`: `:status` now carries
  four values instead of two, and the record gained a `:txt` measurement, a
  `:probe` measurement and an `:activation-nonce`. The nonce is the only field
  in this application deliberately kept out of its own `public-record` allowlist
  — a caller who could read it could serve it from anywhere.

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

`clojure -M:test` — 1460 tests, 8777 assertions, 0 failures, 0 errors.
`bin/test-oracle-cljs` — 153 cases over 13 shipped cores, all passing.
`clojure -M:lint` — errors: 50, warnings: 23, which is **exactly the count at
`e073007`**, this branch's base. None of them are in the files this ADR touches.

The four things the design said implementation would have to show, all in
`domain-verification-test`:
`a-proven-claim-does-not-name-the-tenant`,
`the-nonce-answers-only-for-the-host-that-proved-it`,
`a-live-name-survives-claiming-an-organization-id`,
`a-lapse-reverts-the-name-and-retracts-nothing`. The core's own table is in
`oracle-cases` (both runtimes) and `domain-binding-kotoba-parity-test`, which
also compiles it for `x86_64`, `aarch64`, `wasm32` and `js`.

**Watched failing, each break checked against the test that caught it:**

| break | what failed |
|---|---|
| `configure-organization!`'s `verified?` forced to `false` | `a-live-name-survives-claiming-an-organization-id` (4 assertions), nothing else |
| `nonce-for-host`'s `claim-holds?` replaced with `true` | `the-nonce-answers-only-for-the-host-that-proved-it`, at the pending-binding case |
| `check!`'s `:lapsed` revert made unreachable | `a-lapse-reverts-the-name-and-retracts-nothing` (4 assertions) |
| `binding-state` asking `previously-live` before `name-holds?` | the parity test's ordering case, the `oracle-cases` table, and the reversibility step of the lapse test — three places, which is what an inverted rule in a shared core should cost |

**Not verified, and the boundary has not moved:** that DNS and TLS actually
resolve a customer name to this process. Gate B *measures* it instead of
assuming it, which is the change — but the measurement is exercised through an
injected `*prober*`, exactly as Gate A is exercised through `*txt-resolver*`. No
test here reaches the public internet.

**Two smaller things measured rather than assumed, because both were wrong
first.** `handle` was already at javac's 64 KB method limit: adding one route
inline made the whole `reify` refuse to compile (`Method code too large!`), so
both new route bodies live in helper functions. And `route-scan` reads
`server.clj` as *text*, taking a clause's gate from the `require-*` call between
its test and the next clause start — so a helper carrying a session call and no
test of its own falls inside the preceding clause's window. It silently
reclassified `/api/chronicle/delete` until the helper was given a `cond` clause
of its own. Both are recorded in the code, where the next person will be
standing.
