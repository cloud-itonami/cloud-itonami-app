# ADR-0039: RFC 9728 discovery is a Kotoba decision

**Status:** accepted — 2026-08-14

## Context

ADR-0038 moved `GET /health` onto `kotoba-oracle`. The next request to move
was the next judgement, not the listen.

`GET /.well-known/oauth-protected-resource/mcp` is the RFC 9728 document MCP
clients fetch before they present a token. Authorization servers, resource
URL and scopes stay in `oauth_resource.clj`. The judgement is whether this
request is that document.

http-ingress `:native-aot` remains pending. This ADR does not change that.

## Decision

The route is admitted by `oauth_resource_core.kotoba` through the existing
oracle seam. `server.clj` still names the method and path for the route
scanner. Kotoba is the third conjunct. Inverting this artifact must stop
this route and must not stop `/health`.

Production `kotoba-oracle/call` stays on the KIR interpreter. The native
canary stays `policy.kotoba`.

## Consequences

- Shipped KIR: `resources/cloud/itonami/app/oracle/oauth-resource.kir.edn`.
- `GET /.well-known/did.json` moved in ADR-0040.
