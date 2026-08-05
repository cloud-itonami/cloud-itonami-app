# ADR-0014: Govern Kanban work through organizational performers

## Status

Accepted and implemented.

## Context

Cloud Itonami has four adjacent concepts that did not form one control loop:

- GitHub Projects v2 is a read-only workspace projection;
- `cloud.itonami.app.worker` runs one prompt in a restart-ephemeral queue;
- `cloud.itonami.app.agent-control` executes one bounded AgentRun and pauses for
  human-in-the-loop tool approval;
- an `OrganismWorker` is a durable artificial organism whose supervisor,
  lifecycle, memory, and repository authority remain external.

Treating any one of these as the missing orchestrator would erase a boundary.
A board column is desired work, not execution authority. A background model call
is not a durable worker identity. A human-looking AO persona is not a Person, and
organization ownership does not grant repository authority.

The application also stores Organization Memberships and several operation-bound
approval records, but has no organization graph or policy that resolves an
approver for a capability. The existing `owner/admin` check is a safe local gate,
not an organizational approval model.

## Decision

Add the pure `cloud.itonami.app.work-governance` contract as the join of six
planes. Its persisted representation is namespaced EDN; JSON is only a wire
projection.

```text
Organization graph                     Kanban demand
  Performer ─ Assignment ─ Role           WorkItem
       │             │                       │
       │             └── yakuwari policy ───┤
       │                        │              │
       └── Person approval ─────┤              │
                                ▼              ▼
                         reconciliation plan
                                │
                         bounded AgentRun
                                │
                       effect/test receipts
                                │
                         explicit board move
```

### Performer classification

The portable DoDAF profile is:

| Application entity | Performer kind | DoDAF types |
|---|---|---|
| Human User | `:person` | `:dodaf/performer`, `:dodaf/person` |
| Organization | `:organization` | `:dodaf/performer`, `:dodaf/organization` |
| OrganismWorker / agent runtime | `:system` | `:dodaf/performer`, `:dodaf/system` |

An artificial worker may have a display persona but cannot declare
`:dodaf/person`. Only a Person can supply a human approval decision. This keeps
the current `User`/`OrganismWorker` separation and prevents a role assignment
from laundering a system into human authority.

### Organization graph and approval policy

`OrgAssignment` connects a Performer to a position and a set of roles.
`ReportingLine` connects assignments for organization-chart projection.
Reporting lines never imply approval authority.

An `ApprovalPolicy` independently names:

- organization and capability;
- eligible roles;
- minimum distinct approvers;
- whether user verification is required;
- separation of submitter and approver;
- rejection behavior.

An approval is bound to WorkItem ID and content hash. Decisions for another
organization, capability, item, or content hash are ignored. System-authored,
unverified, and ineligible decisions are also ignored and remain visible as
non-authoritative evidence.

Operation adapters may require a stronger proof than the portable Boolean
`:approval.decision/user-verified?`. Money, signatures, and governed outward
authority continue to use their existing operation-bound WebAuthn assertion;
the contract does not weaken or replace those gates.

### Kanban state and execution

A WorkItem has the explicit lifecycle:

```text
backlog → ready → leased → running → review → done
             │        │        │         │
             └─ held ─┘        ├─ failed ┘
                  └─────────────└─ rejected / cancelled
```

Rejected and cancelled items cannot be silently requeued. Failed items may be
explicitly retried. A board adapter must perform only legal transitions and must
attach the approval, execution, and verification receipts that justify them.

GitHub Projects remains read-only until a separate mutation adapter proves all
of the following before updating a field:

1. the source item still has the basis/version observed by the WorkItem;
2. the requested transition is legal;
3. the content-bound approval policy is satisfied;
4. the AgentRun is terminal and the required result/test receipt exists;
5. the GitHub capability was explicitly granted.

Admission or a model response alone never moves a card to Done.

### Continuous reconciliation

`kotoba-lang/yakuwari` remains the authority for role policy and desired
capacity. `work-governance/reconcile-plan` supplies Kanban demand to its pure
reconciler and returns dispatch, hold, reject, block, and wait-capacity intents.
The host, not this pure contract, owns clock, persistence, leases, dispatch, and
side effects.

A deployment that wants continuous operation runs a supervised reconciler. On
each wake or source event it:

1. reads the durable EDN projection and current AgentRuns;
2. computes a pure plan;
3. atomically leases selected WorkItems;
4. creates bounded AgentRuns through the appropriate executor;
5. checkpoints `held` runs without consuming an approval as execution;
6. records terminal receipts and advances the WorkItem explicitly;
7. retries with backoff after transient source failures.

The in-memory Background WorkerRun is not used for this lifecycle. A local
AgentRun may be the executor for a bounded item. An OrganismWorker may receive a
typed intent and return receipts, but its external supervisor remains alive
independently of the Cloud Itonami process.

## Canonical EDN roots

The durable adapter exposes these collections as one logical ledger:

```clojure
{:work-governance
 {:organizations {}
  :organization-units {}
  :positions {}
  :organization-roles {}
  :performers {}
  :assignments {}
  :reporting-lines []
  :approval-policies {}
  :work-items {}
  :approval-decisions []
  :execution-receipts []
  :verification-receipts []
  :projection-receipts []
  :audit-events []
  :dead-letters []
  :source-bases {}
  :source-cursors {}}}
```

On disk this logical value is physically split. A generation-pinned manifest
commits one global EDN fragment and one `0600` EDN fragment per organization.
The manifest is replaced only after every fragment in the new generation is
durable, so a reader cannot combine two generations. Existing
`:work-governance` data in `state.edn` is used as a migration fallback and is
moved into partitions on the first subsequent commit; new `state.edn` writes
exclude it. Global scheduler/cursor state stays in the global fragment, while
organization graphs, policy, WorkItems and their evidence follow the owning
organization's physical fragment.

The pure contract is implemented by a runtime adapter with finite reconcile
ticks. `work-reconciler` supervises those ticks from the HTTP server lifecycle;
it has no independent unsupervised loop. `work-runtime` persists leases and
content/source-bound execution receipts in the canonical EDN root and dispatches
bounded runs through Agent Control. `github-projects-writeback` is an optional,
separately enabled projection that reads and compares the leased Projects v2
basis immediately before mutation. A mismatch fails closed and is retained as a
projection failure; it does not rewrite the AgentRun result.

The runtime adapter additionally closes the recovery and operation boundaries:

- every lease receives a monotonic fencing token and a deterministic AgentRun
  ID is persisted before dispatch; repeating dispatch returns that same Run;
- active leases heartbeat, orphaned running work is held for intervention, and
  the reconciler elects one filesystem leader while main-state and partition
  commits use a cross-process lock;
- Projects v2 items are ingested a bounded page at a time with durable cursors;
  signed webhooks only wake the same finite reconciler;
- write-back re-evaluates terminality, result evidence, current organizational
  approval and an explicit GitHub write capability, then re-reads the field
  after mutation to detect the API's unavoidable compare/mutate race;
- `review -> done` requires configured test/review/artifact receipts. Human
  approval and independent review are bound to WorkItem, organization and
  content hash by a user-verifying Passkey authorization;
- OrganismWorkers receive typed, fenced intents. Admission creates a held
  external AgentRun; only an external supervisor receipt makes it terminal;
- retry exhaustion lands in a dead-letter ledger with an authorized replay API,
  and every control transition appends a bounded audit event.

### Organization Studio

The `#organization` surface edits the same canonical graph rather than keeping
a second UI-only organization model. `OrganizationUnit` expresses nested
organization/division/department/team/program structure. `Position` belongs to
one unit; `OrgAssignment` joins a DoDAF Performer to a Position and declared
organization Roles with an optional effective range. A Performer may bind to a
typed runtime actor (`User`, Agent session, OrganismWorker, external System, or
Organization), but the actor kind must agree with Person/System/Organization.

Unit parent cycles, unknown units/positions/roles, reversed effective ranges,
and actor/performer type laundering fail closed before the graph is replaced.
Reporting lines remain a separate structural overlay. Approval routes are
previewed from explicit policies and eligible Person assignments; neither a
manager edge nor a System assignment becomes human approval authority.

## Implementation record

As of 2026-08-04, the governed Kanban contract, durable reconciler, idempotent
AgentRun dispatch, Projects v2 ingestion/write-back adapter, verification and
Passkey approval binding, OrganismWorker intent adapter, partitioned EDN store,
management API, and Organization Studio are integrated. Organization Studio is
available at `#organization` and edits nested units, positions, performers,
typed actors, roles, effective assignments, reporting lines, and approval
policies against the canonical organization graph.

Closure verification covers the governance, runtime, partition, GitHub source
and write-back, approval, OrganismWorker dispatch, and web surface namespaces:
34 tests and 121 assertions pass. The embedded browser script parses under
Node.js, the server-side Hiccup source reads successfully, and `git diff
--check` is clean.

The live GitHub mutation probe remains an environment acceptance step rather
than an implementation gap. Running it requires an explicitly designated
sandbox project, its project/field/option identifiers, a scoped credential, and
`CONFIRM=1`. The fake GraphQL transport already verifies pre/post basis checks,
conflict handling, cursor recovery, mutation verification, and rollback.

## Consequences

- Kanban demand, organizational responsibility, approval, and AgentRun capacity
  now share one executable vocabulary.
- DoDAF Person authority stays human; artificial workers remain Systems.
- Organization charts can change without silently changing approval authority.
- Missing capability or approval policy fails closed.
- Existing WorkerRun, AgentRun, authority, and OrganismWorker boundaries remain
  compatible.
- Runtime persistence and supervised wake-up are concrete adapters, while the
  pure contract remains independently testable.
- GitHub write-back is off by default and can only project a verified receipt
  whose lease-time project/item/field/option/updatedAt basis is still current.
- A separately confirmed live-sandbox probe performs target mutation, post-read
  verification, rollback to the original option, and rollback verification. It
  emits a content-addressed receipt and never includes the OAuth credential.
