# ADR-0014: Agent loops use tenant-bound connections

Status: accepted and implemented

## Decision

An agent loop does not switch a session's active Organization. It requests a
tenant connection which binds exactly one user, requesting agent session,
membership, tenant, capability set, operation/storage budget and lease.

The connection starts as `pending-approval`. Only a human browser session may
approve it; CLI and MCP deliberately expose no approval operation. Approval
sets a bounded expiry. Renewal is another request and does not extend the lease
until the human approves it. Either the requesting agent or the human may
revoke it.

The connection id is an opaque handle, not a bearer credential. Calls still
authenticate with the agent session that created it, and another agent session
cannot borrow the handle. `tenant_connection_context` validates status, expiry,
capability and budget on every use, consumes one operation, and returns the
fixed tenant, membership and repository stream:

```text
tenant/<internal-tenant-id>/agent/<requesting-session-id>
```

Internal tenant ids, not mutable Organization slugs, are storage and authority
coordinates.

## Surfaces

The versioned HTTP API is canonical:

```text
GET  /v1/tenants
GET  /v1/tenant-connections
POST /v1/tenant-connections
GET  /v1/tenant-connections/{id}
POST /v1/tenant-connections/{id}/approve
POST /v1/tenant-connections/{id}/renew
POST /v1/tenant-connections/{id}/revoke
POST /v1/tenant-connections/{id}/context
```

The CLI and MCP server are clients of these routes. They do not read or mutate
the EDN store directly. MCP publishes tenant tools only while its configured
agent session resolves against the running app.

## Consequences

- Concurrent loops cannot race by changing shared active-organization state.
- Membership is checked when requesting and approving a connection.
- Unknown capabilities fail closed; the initial allowlist covers tenant,
  workspace, chat, actor invocation and local repository query operations.
- A connection cannot approve itself, exceed its operation budget, survive
  revocation/expiry, or cross to another agent session.
- The local editable repository remains the data plane. Kagi/DataLad/Kotobase
  publication remains owned by the repository host and is not exposed to the
  agent connection.

## Not yet implied

Creating a connection does not grant a tool which the application does not
otherwise implement. Each business operation must still map to a named
capability and call `tenant-connection/context!` before it can accept a
connection handle. ADR-0015 adds the first connection-gated repository
read/write/publish tools, storage accounting, Streamable HTTP MCP and OAuth
protected-resource discovery. Other business operations still need that same
explicit mapping before they may accept a connection handle.
