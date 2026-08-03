# ADR-0013: A local Agent queries an EDN projection and publishes Kagi chunks

## Status

Accepted. 2026-08-03.

## Context

The storage question used to contain an expensive assumption: a remote store
had to answer Datomic/Datalog queries over encrypted data. That assumption made
`arrangement`'s four blind-key indices valuable enough to carry their write and
storage amplification.

It is not the product boundary. The Agent that needs the query is on the user's
device. It can query a local plaintext/materialized view. The remote system is
for durable encrypted history, synchronization, delegation and recovery; it is
not a query peer.

The second product boundary is equally concrete: an Agent must be able to edit
ordinary EDN. A storage format which is only intuitive after passing through a
Datomic transaction API excludes the simplest and most inspectable mutation
path. Conversely, editing Kotobase Prolly leaves, commit nodes or encrypted
vault EDN directly would bypass schema, retractions, authorization receipts and
the head compare-and-swap. "Agent edits EDN" therefore needs to name which EDN.

The threat boundary is remote disclosure. The editable working set may be
plaintext while it is local. Plaintext must never enter Git's object database,
DataLad, git-annex or a remote transport. Device-at-rest protection is an OS
disk-encryption concern and is not claimed by this storage layer.

## Decision

### Scope: this is the default for every repository-driven Agent

This decision is normative for `cloud-itonami`, domain actors, actor SDK
templates, and artificial-organism repositories. A repository does not escape
the rule because its process is called an Actor, organism, loop, cell or Agent.
If it holds private user, organization, credential, delegation, conversation
or operational state, it has the same boundary:

```text
local plaintext projection -> local query/reconcile -> Kagi-sealed payload
                           -> DataLad verified transport -> Kotobase head CAS
```

Repository source code, schemas and intentionally published facts may remain
ordinary Git data. The exception is never inferred from a repository name or
visibility: each such dataset needs an explicit `:data/public-classification
:required` declaration. Mixed public/private ledgers separate their paths and
heads; they do not mark an entire repository public.

The machine-readable contract is `langchain.repo-profile` profile
`:kotoba/local-agent-kagi-chunks-v1`. Its supported repository kinds are
`:cloud-itonami`, `:actor`, and `:artificial-organism`. A deployment gate must
call `validate!` and also scan the staged objects; declaring the profile alone
does not prove ciphertext.

### The editable EDN is a projection, not a Kotobase block

Each user has one local workspace:

```text
workspace/<opaque-user-storage-id>/state.edn       plaintext, editable, ignored
workspace/<opaque-user-storage-id>/.base.edn       retained merge base, ignored
workspace/<opaque-user-storage-id>/.basis.edn      local basis/head metadata
.itonami/blocks/<sealed-cid>                        ciphertext, DataLad-managed
.itonami/heads/<opaque-user-storage-id>.edn         signed public head
```

`state.edn` is the surface a person or Agent edits. Entity identifiers are
stable strings or CIDs, never array positions or freshly generated IDs on each
parse. The EDN reader is `clojure.edn`, with an allowlist for tagged values; it
is never the Clojure reader and never evaluates code.

There is one mutation membrane:

```text
Agent EDN edit ──┐
                 ├─> reconcile(base, candidate, current-head)
Datomic update ──┘       ├─ validate schema and authority
                         ├─ derive assertions and retractions
                         ├─ compare basis CID / expected revision
                         ├─ append an idempotent transaction
                         └─ materialize canonical state.edn
```

An EDN edit is not committed by noticing a new modification time. The
reconciler parses both the basis projection and candidate, computes the final
datom change set, and commits it against `expected-revision`/basis CID. A stale
basis invokes a three-way merge. Two different values for the same
entity/attribute are a visible conflict, never last-writer-wins by accident.

A Datomic-style update enters the same membrane and then regenerates the EDN
projection. This prevents the file and query view from becoming two writers.

### Queries are local only

The Agent queries the local materialized datom view. It may use Kotobase's
Datalog model, an in-memory `arrangement` source, or an injected Datomic-like
backend, but the query planner receives no remote XRPC/query capability. A
cold device must first hydrate, decrypt, reconcile and materialize its local
view.

The portable query contract is the intersection implemented by Datomic,
DataScript and `langchain.db`: EDN query vectors with `:find`, optional `:in`,
and `:where`, EAV clauses, scalar/tuple/collection/relation find shapes,
lookup refs, pull required by the application, and schema-declared identity and
cardinality. A repository may use a richer backend internally, but persisted
queries, Agent tools and conformance tests must stay in this common subset.
Backend-specific Datomic rules, transaction functions, Java predicates and
remote database functions are not portable contract data.

`langchain.kotobase-persist/local-conn` is the reference wiring. It hydrates a
local `langchain.db` connection by replaying an append/read transaction stream.
For private remote state the supplied Kotobase store must be wrapped by the
fail-closed seal/unseal adapter; no remote query handle is constructed.

Remote encrypted Arrangement remains an opt-in for a future product that
actually requires remote encrypted query. It is not the default persistence
format for cloud-itonami.

### Kagi owns keys and encrypted payloads; Kotobase owns transactions and heads

There is no `age` layer.

Kagi owns:

- the per-user VMK and its Passkey/OS-Keychain unlock wraps;
- random per-chunk and per-object DEKs;
- AES-GCM payload sealing with fresh nonces;
- organization/device grants using hybrid KEM-wrapped DEKs;
- routine VMK rotation by re-wrapping DEKs, without payload re-encryption;
- a repository-VMK keyring: epoch 1 remains compatible with the stable Kagi
  root VMK and later random epoch keys are wrapped under that root, so old
  retained heads remain recoverable after rotation;
- recovery and key lifecycle evidence.

Kotobase owns:

- idempotent transaction IDs and expected-revision comparison;
- the append-only change stream and immutable head chain;
- authorization and query receipts;
- the mapping from one committed logical change to its sealed block;
- per-user storage accounting from admitted sealed manifests.

DataLad/git-annex owns transfer, version retention and remote object presence.
It receives sealed blocks only.

### One logical change has more than one identifier

Kagi's current sealed-store `cid` is a versioned object key, while a Kotobase
CID is a content hash. Random-nonce encryption also means encrypting the same
plaintext twice need not produce the same ciphertext. They are not forced to
be equal.

The binding is:

```clojure
{:tx/id              "idempotent command id"
 :basis/cid          "previous committed head"
 :semantic/cid       "CID of canonical plaintext change; private"
 :sealed/cid         "CID/hash of exact ciphertext bytes"
 :key/epoch          7
 :schema/version     1
 :owner/storage-id   "opaque id"
 :sealed/bytes       18342}
```

The semantic CID is stored locally and inside authenticated encrypted
metadata, not exposed as a public equality oracle. A retry reuses the admitted
sealed block for its transaction ID instead of encrypting the same change into
a second object. The public signed head identifies the sealed root, previous
head, schema and key epoch, without names, organization IDs or EDN keys.
For epoch 2 and later it also carries that epoch's root-VMK-wrapped repository
key envelope. Another authorized device verifies the signed head, unwraps and
adopts the envelope with its Kagi root VMK, and can then hydrate the state. A
device without the root VMK learns no repository key from this envelope.

### Publish happens before Git sees the value

`git push` is too late to encrypt. Once plaintext has been staged, it can remain
in `.git/objects` even when a pre-push hook refuses the network operation.

The supported command is an explicit publish pipeline. Ciphertext transport
must precede the authoritative head CAS; otherwise a failed DataLad push could
publish a head which no other device can hydrate:

```text
validate -> reconcile -> canonicalize -> Kagi seal -> verify ciphertext CID
         -> DataLad save/push/whereis -> write signed Kotobase head by CAS
```

If the final CAS loses a race, already uploaded ciphertext is an unreferenced
orphan eligible for later garbage collection. It is never a dangling published
head. A ciphertext-only `.publish-pending.edn` journal preserves the exact
random-nonce blocks and transaction ID across retries.

Each head after the first also seals the complete preceding signed head as a
`:head` ciphertext block and signs its descriptor into the new head. A client
can therefore walk and verify retained history starting from the single current
Kotobase head without requiring a remote list/query API. A legacy head which
names a predecessor but lacks its encrypted locator fails accounting closed
instead of silently under-counting retained bytes.

The plaintext workspace is outside DataLad and ignored by Git. A client-side
gate rejects plaintext paths, but it is defense in depth. A server-side
pre-receive/CI gate also rejects unrecognized payload paths and any block that
does not verify against its sealed CID and admitted manifest.
Before writing a workspace inside any Git worktree, the runtime resolves the
nearest Git root and requires `git check-ignore` to accept the exact owner
directory. Merely being outside the DataLad dataset is not sufficient.

### Storage belongs to a user

Every admitted manifest has exactly one `owner/storage-id`. Usage is the sum of
that owner's live sealed bytes, including retained versions. A shared grant
does not duplicate or transfer the storage charge. If a physical object is
intentionally copied into another user's independent retention domain, the
copy is charged to that second owner.

## System Dynamics calculation

The executable model is
[`cloud_itonami_local_agent_storage.cljs`](../../../../kotoba-lang/loop-system-dynamics/src/loop_system_dynamics/cloud_itonami_local_agent_storage.cljs),
with its runner in
[`run_cloud_itonami_local_agent_storage.cljs`](../../../../kotoba-lang/loop-system-dynamics/bin/run_cloud_itonami_local_agent_storage.cljs).
It is expressed with `kotoba-lang/org-oasis-open-xmile` stocks, flows and
Euler integration. Tests execute the model rather than restating its arithmetic.

The stocks are:

- `Local_Plaintext_GB` and cumulative `Plaintext_Exposure_GB_Days`;
- `Unreconciled_Changes_GB`;
- `Local_View_Backlog_GB`;
- `Remote_Stored_GB`;
- `Sync_Backlog_GB`;
- `Local_Query_Backlog`;
- `Routine_Rotation_Backlog_GB`.

There is deliberately no remote-query stock or capacity. The four compared
architectures receive exactly the same local query demand.

### Baseline assumptions

These are illustrative capacity assumptions, not production observations:

- 1,000 users;
- 1 GB/day aggregate logical writes;
- 4,000 local Agent queries/day;
- 730-day retention and simulation horizon;
- 5 GB/day encrypted sync capacity;
- 20 MB total wrapped-key metadata, re-wrapped annually;
- whole-snapshot regeneration at 5% of current logical state per day;
- Euler integration, `DT=1 day`.

### Result

| architecture | remote GB day 365 | remote GB day 730 | peak sync backlog | query backlog day 730 | cold start minutes/user |
|---|---:|---:|---:|---:|---:|
| Kagi chunked EDN + local Datomic view | 321.87 | 517.03 | 0.00 | 0 | 2.99 |
| Kotobase encrypted Arrangement + local view | 517.29 | 830.94 | 0.00 | 0 | 4.48 |
| Kagi encrypted event log + local materialized view | 359.23 | 577.04 | 0.00 | 0 | 4.62 |
| Whole Kagi-encrypted EDN snapshot | 2,705.34 | 7,530.03 | 7,082.24 | 0 | 2.88 |

All four have the same 461.63 GB aggregate local plaintext working set at day
730 and approximately 195,908.60 GB-days of cumulative plaintext exposure. That
is the direct cost of the stated local-plaintext decision; changing the remote
format cannot reduce it.

With no remote query benefit to pay for its four persisted indices,
Arrangement stores 313.91 GB more than chunked EDN at day 730, about 61%.
Whole snapshots retain the reinforcing loop
`logical state -> snapshot size -> encrypted writes -> stored history`; their
sync arrival eventually exceeds the 5 GB/day transfer capacity.

The cold-start values are comparative outputs from assumed transfer/replay
throughputs, not latency promises. Event replay is slower despite moderate
stored bytes because every event must be applied before the view is queryable.

### Sensitivity

At 10,000 local queries/day, the three indexed local-view designs still end at
zero query backlog. The whole-snapshot/direct-EDN-scan scenario, modeled at
6,000 queries/day, accumulates 2,920,000 queries. The consequence is not to
add a remote index; it is to keep a local Datomic/materialized index beside the
editable EDN.

At 10 GB/day logical writes:

| architecture | peak unreconciled GB | peak sync backlog GB | bottleneck |
|---|---:|---:|---|
| Kagi chunked EDN | 1,460 | 2,890.8 | reconcile then sync |
| encrypted Arrangement | 2,920 | 4,234.0 | reconcile then sync |
| encrypted event log | 5,840 | 0 | reconcile; low output hides downstream pressure |
| whole snapshot | 0 | 102,042.3 | snapshot amplification and sync |

Zero downstream backlog is not evidence of health when an upstream queue is
growing. Production admission therefore checks every queue.

## Admission gates before cutover

The decision is accepted; a storage cutover is not admitted until measurements
from the current app prove:

1. peak reconcile throughput is at least 1.5 times peak logical write rate;
2. local-view apply throughput is at least 1.5 times reconcile throughput;
3. encrypted output remains below 70% of sustained sync capacity;
4. a full user hydrate, decrypt and materialize completes within the chosen RTO;
5. Agent EDN edits and Datomic updates converge to the same semantic CID;
6. stale-basis and same-attribute conflicts are refused or surfaced;
7. staged Git/DataLad objects contain no plaintext fixture markers, names,
   organization IDs or EDN domain keys;
8. routine VMK rotation re-wraps key metadata without payload rewrite;
9. storage usage reconciles exactly from signed manifests to physical sealed
   bytes;
10. losing DataLad transport after sealing cannot advance the published head;
11. every deployable repository declares a conforming repo profile, and every
    public Git exception carries an explicit data classification;
12. the repository's persisted Agent queries pass unchanged against its local
    backend and a second common-subset backend (DataScript or `langchain.db`).

`repository qualify` accepts only evidence marked `:production` with a
measurement timestamp and explicit cache-empty hydrate attestation. It derives
gate 7 from all non-trivial keyword/string markers in the current private
workspace, gate 9 from the encrypted retained-head chain and physical blocks,
and gate 11 from the explicit 29-repository inventory. Gates 5, 6, 8, 10 and
12 execute again inside the exact qualifying build using the production
reconciler, memory failure transport, VMK rewrap path, `langchain.db`, and
DataScript. An evidence file cannot override these eight live results and is
itself ignored by Git.

The cache-empty attestation is produced by `repository drill`, not typed by an
operator: the command refuses the warm DataLad dataset, proves that a separate
recovery dataset contains no materialized annex block, hydrates through the
configured remote, and atomically records elapsed time and downloaded bytes.
Both `drill` and `qualify` bind the evidence to the exact 40-character
`CLOUD_ITONAMI_SOURCE_COMMIT`; a well-formed SHA from another build is refused.
Peak logical writes, sustained upload capacity and the chosen RTO remain
deployment observations and are not fabricated by this drill.

Before either command, `repository preflight` performs a read-only readiness
check. Its output contains only check identifiers, booleans and bounded reason
keywords—never paths, owner identifiers, tokens, Kagi material, signed heads or
plaintext. It verifies both CLI tools, owner shape, warm/cold dataset isolation,
empty cold cache, annex remote configuration, editable workspace, exact source
commit, Kagi context, Kotobase read access and presence of a published head.

An emergency compromise of a blind/index key is distinct from routine VMK
rotation. If remote Arrangement is ever enabled, that event may require a full
reindex/re-encryption and must receive its own measured recovery model.

## Consequences

- The default remote representation is smaller Kagi-encrypted EDN chunks, not
  searchable Arrangement snapshots.
- Kotobase remains useful for transactions, immutable heads, receipts and CID
  binding; it is not asked to execute a remote query.
- Agent direct editing remains intuitive without making a generated database
  block an editable source file.
- Local plaintext is an explicit accepted exposure. Editor backups, crash
  dumps, logs and swap are in scope for operational hardening; this ADR makes
  no encrypted-at-rest claim for them.
- Cross-device merge is a datom-aware three-way merge against basis CIDs, not a
  text merge of encrypted files.
- Kotobase `sealed-store` now seals writes and verifies/decrypts reads,
  snapshots and transaction receipts before local replay. Plaintext and
  ciphertext-digest mismatches fail closed.
- `cloud-itonami` exposes a bounded read-only `local_datalog_query` Agent tool
  over its explicit `:datoms` projection. Its process-local view is keyed by
  exact datoms: the same basis reuses the connection, a stable schema applies
  assertion/retraction deltas, and a cardinality/schema change rebuilds from
  authoritative state. It never grants the Agent a remote query capability.
- The checked-out `animeka`, `dougaka`, `shinshi-growth` and `swachh` actor
  stores accept the shared append/read persistence port while keeping queries
  local. New actor and artificial-organism implementations inherit the same
  repo profile from the common library/template boundary.
- Every checked-out deployable `cloud-itonami/*-actor` repository now declares
  the private-default profile. None receives an inferred public-data exception;
  a public ledger must add the explicit classification and pass the payload
  scan. `repository-qualification/audit-profile-roots` verifies an explicit
  deployment inventory and fails on a missing, malformed or weakened profile.
- `kotoba-lang/organisms` now persists artificial-organism state through an
  injected append/read port, restores it on restart, and queries only a local
  EAV projection. Python owns no duplicate crypto implementation: the host
  wires the Kagi/Kotobase/DataLad stack. The public
  `com-etzhayyim-app-organism` projections carry an explicit public-only
  classification and prohibit private user state in that path.
- `cloud.itonami.app.repository-storage` implements canonical semantic CIDs,
  datom-aware three-way reconciliation, bounded chunks, Kagi AES-GCM sealing,
  hybrid-signed heads, ciphertext-only retry journals, DataLad
  save/push/whereis verification, Kotobase head CAS, hydrate/recovery, VMK
  re-wrap without chunk rewrite, encrypted retained-head traversal,
  physical-byte accounting and legacy-state workspace migration.
- `kagi.kotobase-seal` supplies the production seal/unseal callbacks for local
  replay of a Kotobase append stream; `kagi.repository-context` obtains VMK and
  hybrid signing keys non-interactively from Kagi/SecretStore. Its keyring
  stores historical repository VMKs only as root-VMK-wrapped metadata.
  Rotation is two phase: Kagi stages a signed rotation event, Kotobase head CAS
  chooses the winner, and only that published event enters Kagi's durable
  rotation DAG and local keyring. Competing children fail closed rather than
  letting two devices assign different keys to the same epoch.
- Kotobase head publication uses the already deployed
  `encryptedGraph.put/get` control plane. Its tenant lock and
  `expected_epoch` comparison are the CAS; cloud-itonami does not downgrade it
  to independent IStore `put` and `append` calls. Runtime cutover remains
  fail-closed until a production tenant token/DataLad remote are configured and
  measured evidence passes all twelve gates. The current whole `data/state.edn`
  remains migration input until then.
- `repository measure` collects representative warm reconcile, local-view,
  Kagi-seal and configured-transport hydrate capacity from the real workspace.
  It does not relabel a local annex cache hit as cold-device RTO or sustained
  remote-sync evidence.
## Repository adoption record

This record distinguishes an accepted default from completed runtime adoption.

| layer/repository | implemented in this change | remaining before cutover |
|---|---|---|
| `kotoba-lang/langchain` | common local query engine, Kotobase append/read adapter and machine-checkable repo profile are published on `main`; cloud-itonami pins the verified SHA | none for this decision |
| `kotoba-lang/kotobase` + Kagi | fail-closed seal/write/read adapter, wrapped repository-VMK epoch keyring, real Kagi callbacks and deployed encryptedGraph expected-epoch CAS | production tenant credential and recovery drill |
| `cloud-itonami-app` | local Agent query, DataScript parity corpus, reconciler, chunk publisher, per-user workspace/head, retry, hydrate, VMK rotation, encrypted retained-head chain, accounting, measurement probe, encryptedGraph adapter and executable 12-gate evaluator | configure production DataLad/Kotobase credentials and collect peak-write, sustained-sync and cold-RTO evidence |
| four Datomic-shaped cloud-itonami actors | optional shared persistence port; local query remains unchanged | host wiring to the sealed Kotobase store |
| other checked-out cloud-itonami actor repositories | private-default profile declared and executable inventory audit available | add a host persistence port when a repository begins to retain private runtime state; classify any public dataset explicitly |
| `kotoba-lang/organisms` | restartable injected persistence seam and local EAV query projection | wire the deployment host to the shared sealed store |
| `com-etzhayyim-app-organism` | public generated projection explicitly classified; private state prohibited on that path | keep generated-public and private-runtime heads separate |

An absent or unbuildable repository is not marked migrated. Deployment CI must
pass its complete explicit repository list to `repository profiles`; relying on
workspace discovery would silently omit a repository that was not checked out.
The app CI now consumes that same repository/path inventory directly: it
validates the PR checkout locally and fetches the other 28 `main` profiles via
the GitHub Contents API. Duplicate inventory entries, network/API failure,
missing repositories and invalid profiles all fail closed; there is no second
workflow list which can drift from qualification.

## What this model has not proved

The workload, throughput and amplification numbers have not been measured from
cloud-itonami production traffic. The model is deterministic and does not
represent correlated device reconnects, network outages, compromise
probability, merge-conflict probability, editor backup leakage, DataLad
metadata leakage or hardware failure. It answers capacity and feedback-loop
questions under named assumptions. The admission gates turn those assumptions
into measurements before a cutover.
