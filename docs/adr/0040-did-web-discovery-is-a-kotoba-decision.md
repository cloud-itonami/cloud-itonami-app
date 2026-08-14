# ADR-0040: did:web discovery is a Kotoba decision

**Status:** accepted — 2026-08-14

## Context

ADR-0039 moved RFC 9728 discovery onto `kotoba-oracle`. The next request to
move was the next judgement, not the listen.

`GET /.well-known/did.json` is the public half of a key pair. A verifier who
has to authenticate to fetch it cannot verify anything. The document body,
Host→tenant resolution (ADR-0025) and the 404 when this deployment does not
publish did:web stay on the host.

http-ingress `:native-aot` remains pending. This ADR does not change that.

## Decision

The route is admitted by `did_web_core.kotoba` through the existing oracle
seam. `server.clj` still names the method and path for the route scanner.
Kotoba is the third conjunct. Inverting this artifact must stop this route
and must not stop `/health`.

Production `kotoba-oracle/call` stays on the KIR interpreter. The native
canary stays `policy.kotoba`.

## Consequences

- Shipped KIR: `resources/cloud/itonami/app/oracle/did-web.kir.edn`.
- The Bitstring Status List at `/credentials/status/` is the next public
  judgement of this shape.
