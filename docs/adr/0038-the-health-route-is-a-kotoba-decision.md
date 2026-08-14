# ADR-0038: The health route is a Kotoba decision

**Status:** accepted — 2026-08-14

## Context

Nine decision cores already run through `cloud.itonami.app.kotoba-oracle`.
None of them is on the HTTP surface. `GET /health` was two string equals in
`server.clj` and a host-constant JSON body. ADR-0037 executed one core as
native machine code; it did not move HTTP.

amu's `http-ingress-v1` kit is `:reference :implemented` and
`:native-aot :pending`. Its contract is host-listen, guest-poll. Accept and
reply are records; a kexe export cannot carry them (ADR-2608110200). Marking
`:native-aot :implemented` in this repository would be a lie. The JVM owning
the socket is the kit's design, not a fallback.

The landable slice is therefore the judgement, not the listen.

## Decision

`GET /health` is admitted by `health_core.kotoba` (`health-route?`,
`:string × :string → :bool`) through the existing oracle seam. The process
still listens in Clojure. The JSON body stays a host constant.

`server.clj` still names `(= method "GET")` and `(= path "/health")` so the
route scanner keeps seeing the clause. Kotoba is the third conjunct: a
handler that kept the two equals and dropped the call would still 200, so
the HTTP test inverts the artifact and requires the route to stop answering.

Production `kotoba-oracle/call` stays on the KIR interpreter. This ADR does
not flip the seam to native, does not tender a component, and does not
qualify http-ingress for `:native-aot`.

## Consequences

- A tenth shipped KIR artifact (`resources/cloud/itonami/app/oracle/health.kir.edn`).
- The native canary remains `policy.kotoba` (ADR-0037). `health-route?` is
  native-crossable and could become that canary later; growing the canary
  into a second sweep is still refused.
- The rest of the HTTP surface stays Clojure. The next request to move is
  the next judgement, not the next listen.
