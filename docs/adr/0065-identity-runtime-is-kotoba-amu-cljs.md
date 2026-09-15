# ADR-0065: Identity runtime is Kotoba + amu + ClojureScript

**Status:** accepted — 2026-08-20

## Context

ADR-2608081500 already placed the app server on Cloudflare Workers
ClojureScript and named `com.yubico/webauthn-server-core` as droppable:
ceremony crypto is `webauthn.adapters.edge` (WebCrypto), not a JVM library.
ADR-2608095000 and ADR-0049 put new code on ClojureScript / Kotoba, with
`:clj` as a frozen compat layer.

The DID-axis work (ADR-0064) still landed on `.clj` + Yubico. That matched the
surrounding 135 `.clj` files and did not match the runtime rule. Continuing
to grow Passkey / DID on the JVM would cement the rejected shape.

Owner direction 2026-08-20: remove the JVM dependency and base this surface
on **Kotoba, amu, and ClojureScript only**.

## Decision

1. **Judgements for the DID axis are `.kotoba`**, compiled by amu
   (`kotoba compile` / the test-only compiler pin) into shipped KIR, and
   executed through `cloud.itonami.app.kotoba-oracle`. Host half:
   `identity_axis.cljc`.
2. **Passkey ceremony verification is ClojureScript** via
   `webauthn.adapters.edge` (same seam as `net-kotobase/authn`). Challenge
   options are plain EDN / WebAuthn JSON maps — no Yubico builders.
3. **`com.yubico/webauthn-server-core` is removed** from `deps.edn`. A JVM
   finish path that cannot verify must fail closed (`:passkey/cljs-verify-required`),
   not call a second crypto stack.
4. **Local desktop loopback `.clj`** may still *store* transactions and
   *start* ceremonies (EDN options). It is not authority for signature
   verification. Hosted `auth.itonami.cloud` (ADR-0041) and the Workers edge
   remain the production verify surfaces.
5. **amu is the compile path** for decision cores. The interpreter
   (`kotoba-kir`) stays a runtime dependency so Workers and the local host
   run the same artifact. The compiler stays test-only (existing pin).

## Consequences

- New identity / passkey / DID code must not add Maven/JVM crypto deps.
- `passkey_test` continues to cover transaction kind / expiry / single-use
  without an authenticator. Live verify coverage moves to cljs / edge
  suites (`bin/test-oracle-cljs`, app-edge, authn-shaped tests).
- Jackson CBOR in `did.clj` (credential COSE → `did:key`) remains a frozen
  JVM helper for legacy stores; new enrolment prefers the edge adapter's
  public-key bytes. Removing Jackson is a follow-up slice, not this ADR's
  completion condition.
- ADR-0064's product rule stands. This ADR moves *where it runs*.
