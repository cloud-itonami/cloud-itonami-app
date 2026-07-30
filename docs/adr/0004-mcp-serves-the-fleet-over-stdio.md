# ADR-0004: Serve the fleet over MCP on stdio, as an adapter

## Status

Accepted.

## Context

The fleet catalog is the app's most reusable read model: ~1,200 actors with
domain, governor, maturity and ISIC/ISCO/ISO-3166 coding, of which nine declare
an address. Only this app's own agent loop could query it, so the directory's
value was bounded by one client.

Three things already existed and shaped the decision:

- `cloud.itonami.app.fleet` owns tool **descriptors** (`fleet/tools`, with
  JSON-Schema parameters) and **behaviour** (`search-tool`, `call-tool`). They
  live there rather than in `agent-control` because `agent-control` requires
  `agent.run` and `hil.core`, which resolve only under the `:dev` alias's west
  sibling layout — anything defined there is untestable in a plain `-M:test` run.
- `kotoba-lang/org-anthropic-mcp` is a portable `.cljc` MCP kernel: manifest as
  EDN, validation, a JSON↔EDN bridge, and a pure JSON-RPC dispatcher with an
  `ITool` port. Zero third-party runtime deps.
- `agent-control` gates each capability behind a config default merged with an
  operator toggle, all off by default, and requires approval for tools that
  leave the machine.

So the work was never "write an MCP server". It was "route an existing tool
layer to a second client".

## Decision

Add `cloud.itonami.app.mcp` as an **adapter**. `fleet/tools` becomes an
`mcp.model` manifest, `mcp.execute/handle` dispatches, and an `ITool` port calls
`fleet/search-tool` and `fleet/call-tool`. No descriptor and no behaviour is
restated; a tool added to `fleet/tools` appears over MCP without touching this
namespace.

**Transport is stdio, not a route on the loopback server.** `/v1/*` is already
the one unauthenticated exception the loopback bind exists to protect, and an
MCP route would be a second one on the same port. Over stdio the client is a
process the operator launched, so nothing new listens and the trust boundary is
the one they already established.

**No launcher script.** MCP clients invoke a command with args and a cwd
directly, so a wrapper buys nothing — and interposing a process in a stdio
protocol stream risks its framing. The repo's shell launchers also predate the
workspace rule against new shell scripts, which this avoids rather than extends.

**Scope is the fleet capability, and the exclusions are reasoned:**

- *Browser and computer tools* verify the frontmost application between approval
  and action (`agent-control` throws `:agent/frontmost-changed` when it moved).
  That check is meaningful only when the approving party is the operator at that
  machine at that moment. Under MCP the consent model belongs to the client, so
  the check would still run but would no longer mean what it says.
- *Mail, calendar, drive, projects, chat* sit behind the Passkey session on
  `/api/*`. A surface with no session must not reach around that gate; doing so
  would move the app's privacy boundary without saying so.
- *`fleet_call`* is included. It performs egress, but read-only `GET` to an
  address the fleet itself published, named by repository so the caller cannot
  choose the host, with path validation and a truncated body. Its consent under
  MCP is the client's approval rather than an approval receipt in this app,
  which is a real difference and is documented rather than hidden.

The capability gate is re-read in `mcp/fleet-enabled?` rather than called on
`agent-control/settings`, because requiring that namespace would make this one
unloadable in `-M:test` — the same constraint that put `fleet/tools` in `fleet`.
Only the fleet branch is mirrored, so the drift surface is one flag.

## Consequences

- Claude Code, Claude Desktop and any other MCP client can query the actor
  directory and reach deployed actors, with no change to the app's network
  posture and no second copy of the tool definitions.
- `tools/list` is empty until `{:agent-control {:fleet {:enabled? true}}}`. An
  operator who has not enabled the capability sees a server with no tools rather
  than tools that fail when called.
- A tool that throws returns an `isError` result carrying fleet's `:type`, so a
  client can distinguish "no such actor" from "that actor has no endpoint" — and
  one failure does not end the stdio session for every later request.
- JSON-RPC notifications get no reply. MCP clients send
  `notifications/initialized` during the handshake; answering it would put an
  unsolicited error on the wire.
- Nothing may print to stdout in that process — it is the protocol stream. The
  `:mcp` alias exists so it is never run alongside `:server`.
- The MCP **client** half (this app consuming other servers' tools) is still
  future work. ADR-0003's roadmap item is now half done, and says so.
