# ADR-0077: Open agent interoperability uses MCP, A2A and AGNTCY SLIM

Status: accepted; MCP implemented, A2A and SLIM adapters not yet implemented

## Context

Cloud Itonami already has the durable semantics an agent protocol must not
replace: a Goal owns the desired outcome, an Assignment owns one Bot run, a
context envelope bounds what enters that run, and the resident owns grants,
approval and replay. Bot peer notes, Rooms and handoffs are local product
semantics rather than a claim that their wire is an industry protocol.

MCP is already the tool and data adapter. It is implemented over stdio and
authenticated Streamable HTTP, while the resident remains the single writer.
Approval, browser sessions and ambient workspace reads stay outside MCP where
their human or foreground-application proof cannot cross the client boundary.

External agents need a public task boundary, and a future multi-device or
cross-organization Room needs a transport that does not make every agent an
Internet-facing HTTP server. These are different responsibilities. Treating
one protocol as all three would either turn an agent into a tool, turn a
transport message into authority, or duplicate Cloud Itonami's durable state.

## Decision

Adopt this three-layer interoperability stack:

| Layer | Protocol | Cloud Itonami role | Current state |
|---|---|---|---|
| Agent to tools and data | Model Context Protocol (MCP) | Publish admitted tools through the existing dispatcher | Implemented: stdio and authenticated Streamable HTTP |
| Agent to agent | Agent2Agent Protocol (A2A) | Discover an external agent and adapt an A2A Task to an Assignment or handoff run | Adopted; adapter not implemented |
| Secure messaging transport | AGNTCY SLIM | Carry agent messages across devices or organizations, including group and reconnecting delivery | Adopted; transport not implemented |

AGNTCY is the adopted infrastructure family; SLIM is its selected messaging
component. Directory, identity and observability components may be profiled
later, but this decision does not claim they are installed or required.

The resident remains the authority and durable system of record. Protocol
objects are projections or inputs at its boundary:

- an MCP tool call reaches an existing admitted command;
- an A2A Agent Card projects only capabilities safe to advertise;
- an inbound A2A Task creates or addresses a resident Assignment/handoff run;
- an A2A `contextId` correlates exchanges but does not select ambient history;
- an A2A Artifact carries bounded content or a verified reference, never an
  implicit grant;
- a SLIM name addresses a transport participant or Room channel, not a Bot's
  authority;
- a SLIM delivery is untrusted input until resident identity, ownership and
  policy checks admit it.

MCP, A2A and SLIM do not transfer grants, approval receipts, wallet authority,
credentials, private memory, browser cookies or replay permission. No external
message may approve a card. A retry may resume a durable operation only through
the resident's operation key and recorded state; transport redelivery alone
may not repeat an effect.

Internal same-resident Bot work continues to use the direct resident path.
A2A is for framework, process or administrative-domain interoperability. SLIM
is introduced only when its group, secure routing, reconnect or cross-network
properties are required; it is not an extra hop for local calls.

## First compatible slice

The first A2A implementation must be a narrow, reversible adapter:

1. Serve a minimal Agent Card derived from an allowlisted Bot capability
   projection.
2. Accept one authenticated message/task method and create one isolated
   resident handoff run.
3. Map resident run states to A2A task states without copying provider history.
4. Return the final bounded result or artifact reference.
5. Prove that credentials, grants and approval cannot enter either envelope.
6. Interoperate with a second implementation, initially Hermes' A2A adapter.

The first SLIM slice comes later and carries that same admitted task envelope
between two test residents. It must prove authenticated peer attribution,
duplicate delivery safety, offline/reconnect delivery and group membership
removal before it may carry a Room.

## Consequences

Cloud Itonami can provide Grok-Bot-like discovery, delegation, completion
notification and group coordination without making a proprietary service its
system of record. External implementations can be replaced independently at
each layer.

There are deliberately three compatibility claims instead of one. MCP being
live does not imply A2A support, and an A2A adapter does not imply SLIM is
deployed. Product and health surfaces must publish `implemented`, `adopted` or
`disabled` per profile and may report a protocol ready only after an
interoperability exchange passes.

Shared computer, browser lease, per-Bot screen, Room UX, memory, routine and
human approval remain Cloud Itonami product contracts. None is inferred from
protocol compliance.

