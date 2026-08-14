# ADR-0049: A certificate is ordered for a name this process already answers at

**Status:** accepted and implemented — 2026-08-14.

## Context

ADR-0043's Gate B measures whether a custom domain presents a certificate this
process can validate, and the ADR scoped obtaining one out: "Whether this
deployment can terminate TLS for a customer's name is an operator fact. Gate B
fails until it is true."

That was an honest boundary and it left the owner with a step nobody here could
help with. It also left a gap in the loop the rest of ADR-0043 closed: a name
becomes `:live`, and stays live only while a certificate that expires in ninety
days keeps being replaced by somebody outside this process.

## Decision

**The deployment orders its own certificates, over ACME (RFC 8555), for exactly
the domains it has already proven it answers at.**

The ordering of those two is the design and not a convenience. HTTP-01 works by
the CA fetching `http://<domain>/.well-known/acme-challenge/<token>` from this
process. That only succeeds for a name whose DNS already points here — which is
precisely what Gate B established. `issue!` refuses a domain with no `:live`
binding rather than discovering it at the CA, because a failed order spends a
rate limit and returns an error that names none of this.

### Three parts, and the third is why the first two are not write-only

- **the challenge surface.** `/.well-known/acme-challenge/<token>`, public and
  unauthenticated — the CA holds no credential for this deployment — answering
  only for a token this process published for an order it started, and only for
  ten minutes. Served as `text/plain` with no envelope, because RFC 8555 has the
  CA compare the body byte for byte.
- **the certificate store.** The PEM chain, serial and expiry in `state.edn`.
  The private key never goes there: it goes through `*secret-store*`, the
  macOS Keychain the way `mail-account` and `agent-session` already use it, one
  named item at a time and never a sweep.
- **an opt-in TLS listener.** `HttpsServer` choosing a certificate by SNI.

The third is load-bearing. Nothing else in this process terminates TLS, so
without it an issued certificate would be a field that is written and never
read — which is the exact disease ADR-0043 spent itself removing, reintroduced
one layer down. With it, a name this deployment proved can be served by this
deployment, and Gate B's probe can succeed against it for real.

**SNI selection is the whole of the listener's judgement.** The default key
manager picks by key type and would hand every client whichever entry the store
yielded first — a certificate for the wrong name, which every browser refuses
and no log explains. A name this deployment holds no certificate for is
**refused**, not substituted: a handshake that fails is legible, and one that
succeeds under the wrong name is not.

### Ordering is an owner's act, and the timer only renews

Proving a name does not order a certificate. A deployment behind a CDN or a
reverse proxy already terminates TLS and should not be asking a CA for more; a
rate limit spent on a name somebody else serves is spent for nothing.

Renewal *is* on the timer, riding ADR-0048's sweep, and visits only certificates
inside the 30-day window. It is the reason that sweep must not be slow: a
certificate has a hard expiry, and one that lapses takes the name with it —
the probe stops validating TLS, and the binding lapses too.

### Nothing is ordered until an operator names a directory

`[:tls :directory-url]` ships nil and the listener ships off. A default pointing
at a real CA would spend a stranger's rate limit on a deployment that never
asked; `acme/staging-directory` is the one to point at first, and its
certificates are deliberately not publicly trusted.

### The two conversions that fail silently

Both are in the crypto, both produce something that is not malformed but merely
invalid, and the CA's error names neither:

- **the JWS signature.** `SHA256withECDSA` emits DER `SEQUENCE {r, s}`; JWS
  wants raw `R || S`, each left-padded to 32. Read through `asn1` rather than
  sliced at offsets, because the lengths vary with the values.
- **the CSR.** PKCS#10 built with `asn1.core` — the encoder every other signed
  structure in this workspace already uses — rather than by hand. The
  `CertificationRequestInfo` is encoded once and both signed and embedded, so
  there is no second chance to encode it differently.

## Consequences

- The app gains an outbound HTTPS client to a CA and an inbound public route.
  Both are narrow: the route answers a token or 404s, and the client talks only
  to the directory an operator configured.
- `state.edn` gains `:tls`. Challenges are short-lived and retracted in a
  `finally`, whatever the order did — a token left answering hands out a key
  authorization long after it proves anything.
- The account key is created once and kept; the certificate key is fresh per
  order. They are different secrets with different lifetimes, and a fresh
  account per order would also lose the contact address the CA uses for expiry
  warnings.
- A failed order is **recorded** with what the CA said, not just dropped. An
  order that failed and left nothing behind is one the next sweep repeats
  blindly.
- The `HttpsConfigurator` rebuilds its context per connection, so a certificate
  issued while the process is up is served without a restart. "Renewal worked
  but the old certificate is still being served" is the classic ACME operations
  bug and it is a cached context every time.

## Where the code runs

This repository's runtime order puts ClojureScript above the JVM for new code,
with `:clj` isolated as a compat layer rather than assumed. ADR-0043, ADR-0048
and this ADR all shipped their host halves as `.clj`, which matched the 111
`.clj` files already in `src/` and did not match the rule.

Corrected for the judgements, which is the part that had no reason to be
JVM-only:

| `.cljc`, run on both runtimes | why it could move |
|---|---|
| `domain-name` | name shape, service-owned names, freshness, exclusivity — strings, sets and one clock |
| `mail-domain-records` | SPF/DKIM/DMARC parsing, given the TXT values rather than a resolver |
| `tls-binding` | the ACME challenge token, and whether a certificate is due |

`bin/test-portable-cljs` runs their tests under nbb, because a `.cljc` file one
runtime ever executes is a `.clj` file with a longer extension — the state
`bin/test-oracle-cljs` was written to end for the decision cores, and the same
trap. One break (dropping the `.` from the suffix check in `service-owned?`)
fails the same assertion on both.

**What stays `:clj`, and why each one is a platform fact and not a habit:**

- `java.security` and `javax.net.ssl` — the ACME account key, the CSR signature,
  the KeyStore and the SNI key manager. WebCrypto is async, so porting `acme`
  means redesigning its call shape, which is a larger decision than this ADR.
- `javax.naming` DNS, `com.sun.net.httpserver`, `ScheduledExecutorService`, and
  the Keychain shell-out.
- `IDN/toASCII`. `url.domainToASCII` is not the same function under STD3 rules,
  and swapping one for the other would quietly change which names a tenant may
  claim.

**Not corrected: `interaction.js`.** The settings card's four-state logic was
added to a 9,000-line hand-written resource that `web.clj` slurps and inlines.
It is not generated, there is no ClojureScript build for the browser here, and
rewriting a shared file of that size is not something to do inside this change.
It is the largest remaining piece of this ADR's surface that the runtime order
would have written differently.

## Alternatives

**Leave it an operator fact, as ADR-0043 did.** Defensible, and it leaves the
90-day clock outside the loop that ADR-0048 just closed for DNS.

**Ship a directory URL by default.** One less step for the operator, and it
spends a real CA's rate limit on every deployment that installs this and never
configures anything.

**Order automatically when a binding goes `:live`.** Tempting and wrong for
every deployment that terminates TLS elsewhere, which is most of them.

**Write the DER by hand.** A second encoder to be wrong in, next to the one this
workspace already holds to a round-trip property.

**Use Ed25519, the workspace default.** Let's Encrypt does not accept it for
account keys. ES256 is not a preference here.

## Verified

`clojure -M:test` — 1491 tests, 8925 assertions, 0 failures on the branch, and
**1507 tests, 9041 assertions, 0 failures on the merged default branch at
closing**, which is the number that counts: several other branches landed alongside this one and touched
`server.clj`, `oracle_cases.cljc`, `core_test.clj` and `interaction.js`.
`bin/test-oracle-cljs` — 172 cases over 13 shipped cores.
`clojure -M:lint` — unchanged from this branch's base.

**The merged tip was RED the first time it was measured, and the defect was
this file's.** `verify-es256` rebuilt the DER signature from
`BigInteger/toString 16`, which drops a leading zero NIBBLE — so a coordinate
whose top four bits are zero produced a 63-digit hex string that `unhex`
refused, correctly. That is about one run in eight. It passed every run on the
branch and failed on somebody else's tip.

Two things are worth keeping about it. The helper was ALSO reading the value
with `integer-from-hex`, the signed reader, where r and s are positive by
construction — `unsigned-integer-from-hex` is the one that adds the leading
`0x00` DER wants. And the whole defect was in the test, not in `acme`: the
production direction is DER→raw through `coordinate`, which pads, and the CSR
embeds the JDK's own DER without rebuilding it. **A signature verifier that is
right most of the time cannot be the thing that says a signature is valid**, so
the padding is now asserted directly on a value with an odd-length hex, and the
JWS test verifies twenty-five fresh signatures rather than one.

What is actually proven, as opposed to exercised:

- **the JWS signature verifies against the exact input it claims to cover**,
  rebuilt into DER and checked with the JDK — the CA's own check.
- **the CSR round-trips as DER and its signature covers the bytes it carries**:
  `der-round-trips?`, then the JDK verifying the signature over the decoded
  `CertificationRequestInfo`'s own `:asn1/der`. That is the check a hand-rolled
  encoder could not have offered.
- **a full order walk** against a recording transport: the http-01 challenge is
  published and the dns-01 one is not, the authorization is polled until valid,
  the CSR posted to finalize carries the ordered name, and the token is
  retracted whether the order succeeded or failed.
- **a real TLS handshake**: two self-signed fixtures in the store, two clients
  with different SNI, each served its own certificate, and an unknown name
  refused.

**Watched failing:**

| break | what failed |
|---|---|
| SNI selection replaced by the base key manager's | the second name's assertion and the unknown-name refusal — the two properties, and nothing else |
| `raw-signature` returning `toByteArray` instead of the padded 32 bytes | the JWS verification |
| `renewal-due?` no longer treating an unreadable expiry as due | both its cases |

That third one is worth recording for a different reason: **the first time it
was broken, nothing failed.** `renewal-due?` had two branches — a nil check and
a catch — that reached the same answer by different routes, so inverting either
changed nothing a test could see. It was collapsed into one decision, and only
then did the break show up. A branch no test can discriminate is not caution.

**Not verified: a real CA.** The transport is injected, so no test reaches Let's
Encrypt or anything else. A recorded exchange proves this speaks the protocol's
shape; it does not prove a CA accepts it. That boundary is stated the same way
ADR-0025 stated its own — the first real order against staging is the thing
that will move it, and no test here can.

**Also not verified: the Keychain.** `*secret-store*` is replaced in every test,
so the macOS path is exercised by nothing. It is the same shape `mail-account`
uses for passwords and is unverified there too.
