# ADR-0068: An organization root is `did:webvh` with a witness threshold

**Status:** accepted — 2026-08-20

## Context

An organization was named `did:web:<domain>`. Three properties came with that
name and none of them were chosen:

1. **Whoever holds the DNS zone and the TLS certificate is the controller.**
   There is nothing else to check. A `did:web` document is whatever answers at
   the name today.
2. **There is no history.** The document has no predecessor, so a reader
   cannot tell a rotation from a replacement.
3. **Proving a domain changed the identity.** `bind-verified-domain!` moves a
   tenant to the name it proved, and with `did:web` the DID moves with it. The
   organization got a new identifier as a reward for proving its own name.

Root ADR-2608200400 (2026-08-20) re-measured the DID ecosystem and kept
`did:key` as this workspace's *primitive* identity, because it is the only
method whose identifier IS the verification material and so resolves with no
I/O. That ADR is explicit that the argument is about the primitive, and that
rotation is deliberately placed outside the DID method. Root ADR-2608180200
went further and named `did:webvh` the default for the web-based rotation
plane, along with the repository that would carry it —
`foundation-identity-didwebvh`, which had zero west entries until today.

## Decision

**An organization's root identity is minted as `did:webvh`** once a deployment
publishes DIDs at all (`:publish-did-web? true`), with:

- `portable true` at genesis — it can only be set there, and it is what lets a
  later `bind-verified-domain!` move the DID without changing the SCID;
- a **pre-rotation commitment** (`nextKeyHashes`) naming the successor update
  key before it is needed;
- a **3-of-5 witness threshold** over `:security :legal :operations :auditor
  :recovery`;
- the previous `did:web:<domain>` kept as `alsoKnownAs`, so nothing that
  already resolved it goes dark;
- the log at `/.well-known/did.jsonl` and the proofs at
  `/.well-known/did-witness.json`, on the same Host→tenant resolution the
  `did:web` document uses (ADR-0025), admitted by the same decision core
  (ADR-0040).

`:root-did-method :web` remains for a deployment that cannot serve a log.
`did:key` remains what a *person* is named by here; nothing about passkey
identity changes.

### `updateKeys` is not the multi-party control, and the code says so

Several `updateKeys` may be listed and **any one of them signs a valid
entry** — the list is a set of equals, not a quorum. `m-of-n` lives in the
`witness` parameter, which resolvers enforce. The library carries a test whose
name is the claim (`any-single-update-key-signs-a-valid-entry`), so the
distinction is demonstrated rather than asserted in prose.

### The five witness keys are in this deployment, and that is a real limit

Owner decision, 2026-08-20, taken with the trade-off stated: the five keys are
generated here and this process signs all five proofs.

**The threshold is therefore real to a resolver and is not a defence against
this machine.** Five signatures under one custody is one point of failure
wearing five hats. What it does buy is not nothing — a verifier can name who
approved a version, a stolen *update* key alone still cannot publish, and the
published shape is byte-identical to the distributed one — but it is not
independence, and `co-located-custody?` returns true so the record says so
instead of leaving a reader to infer it from the shape.

The seam that fixes it is already in place and costs no rewrite: every signer
crossing into the library is `{:multikey :sign-fn}`, so moving `:security` to
the Security team's HSM replaces one map. `witness-signers` is the only
function that changes.

## Consequences

- New dependency: `io.github.kotoba-lang/foundation-identity-didwebvh`, pure
  `.cljc`, no HTTP and no clock. `org-w3-did` still answers for `did:key` and
  `did:web`.
- `cloud.itonami.app.org-root-did` owns minting, verification and the two
  published documents. It is `.cljc` (ADR-2608201300 forbids new production
  `.clj`); key custody and the clock are the only `#?(:clj)` parts.
- The organization record gains `:did-method`, `:did-log`, `:did-witness`,
  `:did-witness-threshold` and `:did-custody`. The log is stored rather than
  regenerated — a second `mint` would hash to a different SCID, so losing the
  log loses the ability to publish the next version at all.
- `did_web_core.kotoba` gains two exports. `did.json` and `did.jsonl` differ
  by one character, so both predicates compare for equality; a prefix test
  would answer a request for the log with the document.
- The dispatch `cond` gains ONE branch for two files. `send-html!`'s own
  comment records that adding two branches to `handler` once failed against
  the JVM's 64 KB method ceiling.
- A tenant minted before this ADR has a `did:web` document and no log. The
  routes 404 for it, deliberately: a log that does not exist must not be
  confused with one that failed to load.

## Verification

- `org-root-did-test` — 16 tests. Mint, resolve, below-threshold refusal, the
  document naming the credential key while the parameters name the update key,
  the portability move, the pre-rotation negative, the witness intake's three
  refusals, the disk round trip, external resolution, and the upgrade.
- `did-webvh-http-test` — fetches the bytes the server writes and runs the
  resolver over them, with a REAL tenant in the store rather than a stubbed
  lookup. In-memory tests prove the library works; only this proves what is
  *published* resolves. It also inverts the shipped decision core and requires
  the route to stop answering while `/health` keeps answering.
- The library's own suite runs on BOTH runtimes (25 tests / 59 assertions,
  identical), and is mutation-tested: disabling the witness threshold reddens
  only the witness tests, disabling the pre-rotation commitment only the
  pre-rotation test, disabling the portability check only the portability
  test.

## What the second pass added

The first pass minted a root and stopped. Everything that made the mint mean
something was missing, and this ADR listed it. Now:

- **A proved domain appends a version.** `bind-verified-domain!` builds the
  portability entry: same SCID, previous DID in `alsoKnownAs`, signed by the
  key the previous entry pre-committed to, with its own witness proofs. The
  pre-rotation commitment is REDEEMED rather than merely written, and a test
  asserts both halves — that version 2's update key hashes to version 1's
  commitment, and that version 1's own key cannot sign version 2.
- **The update key is a ladder.** Seeds are numbered; version `v` is signed by
  generation `v-1` and commits generation `v`. Pre-rotation forces this: a
  version that reused its predecessor's key would not hash-match.
- **A witness that signs elsewhere can file.** `accept-witness-proof` verifies
  against the declared witness set before storing, so `POST
  /.well-known/did-witness.json` needs no authentication to be safe — forging
  an approval takes a witness key, and filling the file takes one per witness.
  Nothing calls it in normal operation today, which is the point: moving a
  role to its own HSM should not also require building the path its proof
  arrives by.
- **The log survives `state.edn`.** `persist!` writes it to its own file in the
  form it is served, and `read-persisted` reads it back. A second `mint` hashes
  to a different SCID, so a lost log is not a slow rebuild.
- **This app can resolve somebody else's did:webvh.** `resolve-external` takes
  an injected `fetch`, so the resolver makes no request of its own;
  `credential-trust` supplies one carrying its transport policy and the same
  trusted-issuer gate did:web goes through. Issuing and verifying are different
  capabilities and this app now has both.
- **The did:web document points at the log** via `alsoKnownAs`, so a verifier
  arriving at the old name does not read one key and stop.
- **Existing tenants are upgraded.** `upgrade-organizations-to-webvh!` runs at
  startup, is idempotent, and carries the old `did:web` into `alsoKnownAs`. It
  is a no-op for every shipped profile.

## What is STILL not done

- **No organization has been minted in production.** Every shipped profile has
  `:publish-did-web? false`, so all of the above is reachable by configuration
  and has not been turned on. The upgrade at startup is a no-op there for the
  same reason.
- **Witness custody is co-located.** Five signatures, one machine. This is the
  largest gap between the shape and what it is supposed to mean, and it is the
  one the owner decided to accept.
- **No off-machine mirror.** `persist!` removes the single-FILE failure, not the
  single-machine one. The IPLD/IPFS mirror and the audit projection this ADR's
  first pass named are not built.
- **A move stops the OLD location serving.** The Host→tenant lookup keys on the
  tenant's current domain, so after a portability entry the previous host no
  longer answers with the log. The spec requires the complete log at the NEW
  location, which is satisfied; keeping the old one answering is a courtesy to
  holders of the old string, and it is not implemented.
- **No deactivation path.** `append` supports `deactivate?` and explains why it
  clears the pre-rotation commitment in the same entry, but no caller reaches
  it.
