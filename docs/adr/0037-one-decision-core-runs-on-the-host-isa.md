# ADR-0037: One decision core runs on the host ISA

**Status:** accepted — 2026-08-14

## Context

Nine decision cores in this application are `.kotoba`. Production answers them
through `cloud.itonami.app.kotoba-oracle`, which loads shipped KIR and runs
`kotoba.kir/ir/execute` in-process on the JVM. Parity tests compile the same
sources for `:x86_64-kotoba-v1` and `:aarch64-kotoba-v1`. Compilation is not
execution: a module can be accepted by the backend and still compute the
wrong thing. Until this ADR, no export in this repository had ever run as
machine code in the default suite.

`kotoba-lang/murakumo` already solved the measurement. `murakumo.native-exec`
compiles once, runs the KIR interpreter and a kexe loader on the same
artifact, and treats agreement *or* mutual refusal as a pass. Its default
suite carries a one-core canary because an opt-in `:native` alias was
measured never to be invoked.

The HTTP server remains JVM. The native http-ingress kit is still
`:native-aot :pending`. This ADR does not change that.

## Decision

The default test suite (`clojure -M:test`) executes `policy.kotoba`'s
native-crossable export (`loopback-host?`) as a signed kexe on the host ISA,
differentially against the same compile's KIR interpreter.

`cloud.itonami.app.kotoba-oracle/call` stays on the interpreter. Native is a
process spawn per call; the exports that do real work take `[:ref …]` and
cannot cross a kexe boundary (ADR-2608110200). Flipping the production seam
today would add a switch nobody should flip.

A missing C toolchain fails the canary rather than skips it. A native
execution test that does not execute has verified nothing.

## Consequences

- `kototama-native` (and the C loader owned by amu) become test-only extra
  deps. They cannot change what the application does: no `src/` namespace
  requires them.
- `provider-allowed?` is counted as refused at the kexe boundary, not treated
  as a skip. The canary reports how many exports crossed.
- A full native sweep of every core stays out of the default suite. This file
  must not grow into one.
- Tendering a component with a capability gate remains the next hardening
  step when a core needs an effect. This ADR is execution, not tendering.
