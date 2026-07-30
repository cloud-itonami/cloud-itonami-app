# ADR-0002: Represent artificial organisms as external organization workers

## Status

Accepted.

## Context

Cloud Itonami already has two nearby but different concepts:

- a `User` is a human identity related to a tenant through a membership;
- `cloud.itonami.app.worker` is an in-process, restart-ephemeral model run.

Neither represents a durable artificial organism. Etzhayyim Tamaki is a
repository-bound AO with its own supervisor, append-only memory, incarnation
lease, Actors, AgentRuns, homeostasis, and repository authority. Embedding that
lifecycle in a Cloud Itonami process would make UI availability and cloud
connectivity accidental conditions of life.

## Decision

Add **OrganismWorker** as an organization assignment for an independently
running AO:

```text
Tenant / Organization
├── Membership → User
└── WorkerAssignment → OrganismWorker
                         └── external supervisor
```

The assignment declares identity, organization, capabilities, repository,
incarnation projection, and authority locations. It must use
`:ao.worker/runtime :external-supervisor`. Memory, lifecycle, and source
authority remain `:organism-local`, `:organism-local`, and
`:repository-local`.

Cloud Itonami is the workplace and human control surface. It may:

- show redacted snapshots and cursor-based activity projections;
- present objectives, topology, Actors, AgentRuns, resource state, and results;
- submit expiring, typed intents within an assigned capability;
- record human approval, refusal, stop, and budget receipts.

It may not:

- treat an admitted intent as an executed effect;
- mutate the organism event authority or repository directly;
- store model credentials, private memory, or unredacted event bodies in the
  public assignment;
- extend an incarnation lease;
- gain cross-repository authority from organization ownership;
- stop the external supervisor merely because the UI or network is offline.

Tamaki evaluates admitted intents through its own capability, authority,
homeostasis, HIL, and lifecycle gates. Result events return through a redacted
projection. The append-only Tamaki stream remains the event authority.

## Contract

`cloud.itonami.app.organism-worker` defines:

- `assignment` — fail-closed validation of the authority boundary;
- `public-assignment` — a safe directory projection;
- `intent-decision` — organization, worker, capability, and expiry admission.

The initial wire profile is cursor-based NDJSON or SSE over a loopback or
mutually authenticated adapter. Reconnection resumes from a durable event ID;
it does not replace the organism's local-first memory.

## Consequences

- Tamaki can belong to Etzhayyim without becoming a child process or a human
  account.
- Cloud Itonami can govern interaction without owning organism identity.
- Existing in-process WorkerRuns remain useful but must not be presented as
  artificial organisms.
- Remote deployment transports bounded capabilities; it never expands them.
