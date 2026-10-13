# ADR-0098: State is a signed, hash-linked log; `state.edn` is its projection

Status: **proposed (draft)**, 2026-10-13. Not implemented. Derived from
[docs/decentralization-gap-analysis.md](../decentralization-gap-analysis.md),
priority A. Number 0098 follows 0097; `0142-bot-profile-background-review.md`
is already out of sequence in this directory, so confirm the number at merge.

## Context

The resident is the sole authority for Goals, Assignments, grants, approvals,
replay and Bot turns (ADR-0077, ADR-0092 §2). Its durable truth is
`data/state.edn`, with a write-ahead journal beside it
(`state.journal.edn`, schema `cloud.itonami.app.state-journal.v1`,
`store/replay-journal`, `store/fold-journal!`). The journal records ops
(`apply-op`, `state-delta`) and exists for crash recovery and compaction.

That has three properties a reader cannot change from the outside:

1. **Nothing is signed.** A journal line says what changed, not who authorised
   it. Authorisation (CACAO grant, Passkey-bound approval) is checked at the
   door and then forgotten; the resulting state carries no proof of it.
2. **Nothing is chained to a verifiable head.** The file can be replaced with
   another valid-looking file. Only the resident's own lock (`state.edn.lock`)
   protects it.
3. **Only one process can be the writer.** A second resident, a witness or a
   restored replica has nothing to compare against, so moving authority means
   copying a file and trusting it.

The pieces to do better already exist in this repository and its dependencies:
`chain.core/commit!` (IPLD commit with `prev`), `kotoba.protocol.graph`
(`cloud.itonami.app.graph/seal`, today used only for the app-bundle overlay),
`did:key` user identity, CACAO capabilities, W3C VC Data Integrity
(`eddsa-jcs-2022`), and the append-only, org-scoped, one-EDN-map-per-line
discipline of `vf_journal`. ADR-0092 leaves a "third component" slot in
`mesh/itonami.app.edn` for moving the authority onto the lattice once its
prerequisites exist. This ADR defines the data that has to exist first,
independent of where it runs.

This ADR does **not** make the system distributed. It makes the state
**verifiable by someone other than the resident**, which is the precondition for
every later step (replication, a second writer, lattice-hosted validation).

## Decision

### 1. An entry is a signed, hash-linked claim

Every change to governed state is recorded as one entry:

```
{:log/schema   "cloud.itonami.app.log-entry.v1"
 :log/org      <organization id>
 :log/seq      <n, per-org, starts at 0, gap-free>
 :log/prev     <CID of previous entry in this org's log, nil at genesis>
 :log/kind     <keyword from a closed set, see §2>
 :log/body     <EDN, bounded codec (kotoba-lang/edn)>
 :log/authority {:grant   <CACAO CID or nil for resident-originated>
                 :approval <approval entry CID or nil>
                 :actor   <did:key of the human or Bot that caused it>}
 :log/author   <did:key of the signer: the resident's key, see §3>
 :log/at       <instant>
 :log/sig      <Ed25519 over the JCS (RFC 8785) canonical form of all other fields>}
```

- The entry CID is the dag-cbor CID of the entry (`chain.core/commit!`), so
  `:log/prev` is a real content address, not a counter.
- Canonicalisation reuses `org-ietf-jcs`, the same as VC proofs. No new hash.
- The log is **per organization** (the tenant boundary already in
  `docs/tenant-model.md`), plus one global log for deployment-level facts.
  Cross-org reads need no log merging.

### 2. Closed set of kinds, starting small

Phase 1 covers only the facts whose authority is already cryptographic or
human-attested, because those are the ones a third party can check without
re-running a Bot:

| kind | meaning |
|---|---|
| `:goal/created` `:goal/closed` | Goal lifecycle |
| `:assignment/issued` `:assignment/closed` | Assignment issued to a Bot, with the context-envelope hash |
| `:grant/issued` `:grant/revoked` | CACAO grant or its revocation (status-list index) |
| `:approval/recorded` | Passkey/WebAuthn assertion bytes plus the operation hash it was bound to |
| `:run/receipt` | Hash of a Bot run's result and tool-call record. **Not** its content |
| `:membership/changed` | Organization membership VC issued or revoked |

Chat messages, mail, Drive objects, Bot memory and settings stay outside the
log in Phase 1. Anything else is added by amending this table, so the set stays
reviewable.

### 3. The resident signs; humans' approvals ride inside

- The **resident's signing key** is a dedicated Ed25519 key, distinct from the
  IPNS seed (`CLOUD_ITONAMI_APP_IPNS_SEED`) and from any user key. It is custodied
  like the IPNS seed (kagi item, never in the repository). Its `did:key` is
  published in the organization root (`did:webvh`, ADR-0068) as an authorised
  log author, so rotation goes through the existing pre-rotation commitment.
- A human approval is a Passkey assertion. It cannot produce a Data Integrity
  proof (README Status: the "presentation gap is structural"). Therefore the
  **resident signs the entry and embeds the raw WebAuthn
  `authenticatorData`, `clientDataJSON` and signature** in
  `:approval/recorded`. A verifier re-checks the assertion against the user's
  registered public key and the operation hash. The resident is the *notary*,
  not the *source* of the approval.
- A Bot never signs. It acts through a grant; the entry's `:log/authority`
  names the grant.

### 4. `state.edn` becomes a projection

- The log is the source of truth for the kinds in §2. `state.edn` (and the
  `work-governance` manifest generation) is produced by folding the log, and
  carries `{:log/head <entry CID> :log/seq <n>}` for each org.
- The existing journal is **kept and unchanged**: it remains the crash-recovery
  write-ahead for the *projection* and for everything not in §2. The log
  does not replace it.
- Write order, in one `transact!` under the existing process lock: append the
  entry (fsync) → fold into state → write journal delta → update head. A crash
  between steps is repaired at startup by replaying the log tail past
  `:log/head`; the log wins on any disagreement.

### 5. Verification is a pure function

`cloud.itonami.app.log/verify` takes a sequence of entries and a resolver for
authorised author keys, and returns `{:ok head}` or `{:error {:seq n :why …}}`.
It checks gap-free `:log/seq`, `:log/prev` match, signature, that the author
was authorised **at that seq** (so a rotated-out key cannot sign later entries),
and that each `:log/authority` reference resolves to an entry or grant that is
valid at that point. It performs no I/O and is `.cljc`, so it can later run as a
WASM component on the lattice and on the mobile app without a second
implementation. A differential test against a fixed corpus (as
`policy/loopback-host?` already does) guards the two builds.

### 6. Export is a CID, not a service

`log/export` seals the head with `graph/seal` and returns the chain CID and raw
location CID. Phase 1 publishes nothing by default; the operator may put the
blocks to the user's own murakumo nodes (`block put`, as done for ADR-0092's
components). Publication is egress, so it goes through the existing egress
admission (ADR-2608130100): reviewed destination, encrypted transport,
credential present, deployment switch on. Default is off.

## Consequences

- A third party (a witness, an auditor, the user's phone, a standby resident)
  can verify the whole history from the genesis entry and the organization's
  published keys, without trusting the resident's disk.
- Authority can later move without moving trust: a successor resident that has
  the log and is authorised in the organization root can continue it. That is
  Phase 3 of the gap analysis, and it needs this log first.
- The approval-on-lattice step becomes small: the validation rule is `verify`
  plus a policy check, with no Bot and no `llm/infer`. ADR-0092's blocker does
  not apply to it.
- Cost: write amplification (one signed, fsynced entry per governed change),
  a new key to custody and rotate, and a migration. Estimated at tens of
  entries per Goal, not per message, because chat is out of scope.
- It does **not** by itself add replication, a second writer, DNS independence,
  or protection against a malicious resident that signs a false entry. It makes
  such an entry **attributable and detectable by a witness**, not impossible.

## Alternatives considered

- **Make the existing journal the log.** Rejected: it records ops for crash
  recovery, is rewritten by `fold-journal!` (compaction), and is intentionally
  not tied to an author. Making it immutable would defeat compaction.
- **Anchor each entry on-chain** (the ERC-4337 account already exists). Rejected
  for Phase 1: cost and latency per governed change, and it makes a public
  chain a dependency of internal state. An optional periodic head anchor can be
  added later without changing the entry shape.
- **Adopt Holochain/hApp directly.** Rejected for this repository: it would
  replace the kotoba/murakumo substrate and the existing identity stack rather
  than build on them. The properties wanted (source chain, deterministic
  validation, DHT-held entries) are reproduced here step by step.
- **CRDT for multi-writer state.** Deferred. Governed state has approvals and
  budgets that need a single linearisable writer per organization; CRDTs fit
  messages and notes better and can come later for those.

## Phases

1. **Log and verify, local only.** Entry format, `log/verify`, resident key,
   write path for the §2 kinds, projection from the log, startup repair, export
   to CID. No network.
2. **Witness.** The organization's `did:webvh` witnesses (ADR-0068's 3-of-5)
   co-sign periodic heads. A standby replica verifies and follows read-only.
3. **Second writer.** Lease-based hand-over of the writer role between
   authorised residents, recorded in the log itself.
4. **Lattice.** `verify` plus policy as a mesh component; the head and blocks
   held on murakumo nodes.

Phases 2–4 are out of scope here and each needs its own ADR.

## Open questions

1. Is the resident key one per deployment or one per organization? Per
   organization is a cleaner tenant boundary but multiplies custody.
2. Should `:run/receipt` be in Phase 1? It is the most useful for audit but the
   least cheaply verifiable (the verifier cannot re-run a Bot).
3. How is the pre-log history handled: a signed genesis entry that commits to
   the hash of the current `state.edn`, or a full back-fill? The first is
   cheaper and honest about what is *not* provable.
4. Retention and deletion. A hash-linked log conflicts with erasure of personal
   data. Proposed: personal data never enters `:log/body`, only hashes and
   DIDs; to be confirmed against the tenant model.
5. Does the existing `langchain.db` / Datascript double-execution cutover test
   (`deps.edn`) apply to the projection, i.e. must folding the log reproduce
   `state.edn` byte for byte?

## Verification (when implemented)

- Property tests: any reordering, gap, truncation, field edit or signature
  change in a generated log makes `verify` return `:error` at the right seq.
- Key rotation: an entry signed by a key after its rotation seq is rejected;
  before it, accepted.
- Crash tests at each step of §4's write order; restart reproduces the same
  head and projection.
- Differential: JVM `verify` and the portable `.cljc` build agree on a fixed
  corpus.
- An approval entry whose WebAuthn assertion is for a different operation hash
  is rejected.
