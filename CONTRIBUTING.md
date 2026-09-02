# Contributing

1. Create a focused branch.
2. Keep product-neutral behavior in `src/`.
3. Put organization-specific, non-secret defaults in `profiles/`.
4. Pin runtime Git dependencies to immutable SHAs.
5. Default CI is `bash scripts/ci-jvm-free-emit` (no `clojure -M`).
   Leftover JVM suite `clojure -M:test` / `clojure -M:lint` is
   workflow_dispatch only (`.github/workflows/leftover-jvm-tests.yml`).
6. Never commit runtime data, credentials, mailbox exports, or OAuth tokens.

Changes to authentication, DID, relay, provider policy, or persistence should
include tests and an update to the threat model or architecture documentation.

## Decision-core builds: JVM-free parity route (opt-in)

The shipped decision-core KIR (`resources/cloud/itonami/app/oracle/*.kir.edn`)
is written only by the JVM oracle: `clojure -M:test:gen`
(`cloud.itonami.app.kotoba-oracle-gen`, the pinned compiler under `:test`).

An opt-in JVM-free route compiles the same cores with the Amu compiler and
proves KIR identity against the shipped artifacts:

    AMU=<amu launcher> nbb --classpath bin bin/gen_kir_amu.cljs
    AMU=<amu launcher> nbb --classpath bin:test test/kir_amu_parity_nbb.cljs

Both run `amu compile` (Amu's nbb Wasm path — no JVM, no `clojure -Spath`)
on every entry of `kotoba-oracle/cores` and compare the provenance's
`:kir-sha256` against the canonical SHA-256 of the shipped KIR
(`kotoba.artifact.core/sha256` semantics; see `bin/gen_kir_amu.cljs`). The
route writes only under `target/kir-amu/` — nothing under `resources/`.

Rollback is one step: stop running the two commands. `clojure -M:test:gen`
remains the oracle and the only writer of the shipped KIR.
