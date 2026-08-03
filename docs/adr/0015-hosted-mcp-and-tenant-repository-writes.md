# ADR-0015: Hosted MCP is an OAuth resource server over tenant repositories

Status: accepted and implemented

## Decision

Keep stdio MCP for a process launched on the user's machine and add a stateless
Streamable HTTP adapter at `POST /mcp`. Both transports call the same
`mcp/respond` dispatcher and publish the same tool descriptors. HTTP GET and
DELETE authenticate and return 405 because this server has no unsolicited
server messages or protocol session to terminate.

The HTTP endpoint implements the stable MCP `2025-11-25` transport contract:
JSON-RPC requests return one JSON response, notifications return 202, `Accept`
must contain JSON and event-stream, and the protocol-version header is checked.
The `Mcp-Method` and `Mcp-Name` routing headers from the 2026 release candidate
are validated when present but are not required until that version is stable.

Every HTTP MCP request requires a bearer token. Existing local opaque agent
sessions remain accepted. Externally issued tokens are resolved through RFC
7662 introspection and must be active, unexpired, scoped and audience-bound to
the exact MCP resource. Its agent-session identity derives from issuer,
subject and OAuth client ID, so access-token rotation does not orphan a live
tenant connection. The resource publishes RFC 9728 metadata at:

```text
/.well-known/oauth-protected-resource/mcp
```

The authorization server remains a separate deployment authority. The app is a
resource server; it does not mint OAuth authorization codes or access tokens.

## Repository write surface

A tenant connection can request `repository.query` and `repository.write`.
After human approval it may use:

```text
GET  /v1/tenant-connections/{id}/repository
POST /v1/tenant-connections/{id}/repository
POST /v1/tenant-connections/{id}/repository/publish
```

Read returns the safe canonical wire EDN and semantic CID. Write accepts that
wire EDN and requires the previously read CID once a workspace exists. The
existing repository lock checks the CID before the connection consumes an
operation or storage capacity, so a stale writer neither overwrites nor spends
the lease budget.

Storage is one opaque owner per user and internal tenant ID. Quota usage is the
local canonical projection plus ciphertext reserved by publication. Sealed
transactions are charged by stable transaction ID, so retrying a pending
publication does not double-charge. `storage-used-bytes` must not exceed
`max-storage-bytes`. Publish takes no keys from the request: it uses the
server's Kagi context, stages ciphertext in DataLad, and advances the Kotobase
encryptedGraph head only after block publish.

## Consequences

- Organization switch UI state is never an agent authority coordinate.
- OAuth tokens for another audience or without the route scope fail closed.
- Browser-supplied Origin is checked even when a bearer token is present.
- Direct agent EDN edits are intuitive but retain optimistic concurrency and
  repository validation.
- A hosted deployment must configure a real OAuth 2.1 authorization server,
  introspection credentials, DataLad remote, Kagi keyring and Kotobase token.
