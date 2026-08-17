# ADR-0057: Drive bytes are UnixFS blocks in the kotobase archive

## Status

Accepted. 2026-08-17.

## Context

The Drive had two byte backends and neither was kotobase. `documents/default-store`
chose Storj when `STORJ_ACCESS_KEY` was set and a directory under the data dir
otherwise; `cloud.itonami.app.filecoin` was a third `IObjectStore` deliberately kept
out of that choice. kotobase.net was reached for two other things entirely — the
encrypted-graph head CAS behind `repository-storage` (ADR-0013), and the single
archived object that is this app's own document (ADR-0047).

References were inconsistent in a way that had stopped being visible. `upload!`
already named a **PieceCID v2** and therefore already deduplicated by content, while
`create!` and every save named `obj-<uuid>`. `filecoin`'s own docstring said "Nothing
else in this app has a content-derived reference; Drive currently uses relative
paths", which was true when it was written and had not been true since `upload!`
landed.

No part of this stored a CID any other IPFS implementation could resolve. UnixFS
existed in the workspace only in `net-kotobase/ipfs`, whose codec numbers `Links` as
field 1 and `Data` as field 2 — the transposition of the DAG-PB spec. Every fixture
in its suite came from its own encoder, so the suite passed and the codec could not
read a block written by kubo. Measured 2026-08-17 by decoding a real
`ipfs add --raw-leaves` root with both schemas: the spec's ordering yields two links
with 36-byte hashes and `{type File, filesize 262145, blocksizes [262144 1]}`; the
shipped ordering yields one link with an empty hash.

## Decision

1. **A Drive object reference is the UnixFS CIDv1 of its bytes** —
   `cloud.itonami.app.kotobase-objects`, the third `IObjectStore` and the first whose
   references are IPFS CIDs. The string is exactly what
   `ipfs add -Q --cid-version=1 --raw-leaves` prints. Files at or below 256 KiB are a
   bare raw block (`bafkrei…`); larger files get a balanced dag-pb tree at 174 links
   per node (`bafybei…`).

2. **The codec is not in this app.** `kotoba-lang/tech-ipfs-specs-unixfs` owns the
   chunker and the tree; `ipld.dag-pb` in `kotoba-lang/io-ipld` owns the node codec
   and the two framing rules a deterministic protobuf encoder cannot express (`Links`
   before `Data`; an empty `Name` still written). Both are checked against real kubo
   0.41 blocks, not against their own output.

3. **Identity and location are different strings and both are kept.**
   `kotobase.archive-put` accepts raw CIDv1 only. A dag-pb block is archived under the
   raw spelling of the same sha2-256 digest, which is one codec byte away and needs no
   table — `archive/location-cid`. `:drive/object-ref` records the identity; putting
   the location there would be a CID in the Drive that resolves to this file for
   nobody (ADR-2608148200).

4. **Blocks are written children first.** `unixfs/build` returns them in that order
   and the store keeps it, so a root is never reachable before what is under it.

5. **The store is selected explicitly and never inferred.**
   `CLOUD_ITONAMI_DRIVE_OBJECT_STORE=kotobase`. Not from the presence of
   `KOTOBASE_ARCHIVE_TOKEN`, which is already set wherever this app publishes its own
   document: inferring would have moved every existing Drive to a different backend on
   the next deploy. Selected-but-unusable throws rather than falling back — a Drive
   that quietly fell back would hold `obj-…` references an operator believes are CIDs.
   **Existing objects are not migrated.** References already in a workspace go on
   naming the store that holds them.

6. **Reads verify, and absence is not corruption.** Every block is checked against the
   CID it was asked for. A block the archive does not have returns nil, which
   `drive.object/read-item` reports as `:missing-object`; a block whose bytes do not
   hash to their CID throws. Collapsing the second into the first would file "this
   store cannot be trusted" as "this file is not here".

7. **Deletion is not implemented and says so.** The archive has no delete route, and a
   content-addressed store could not honour one while another holder points at the
   same bytes — the case `drive.workspace/forget-item`'s `:keep-ref?` exists for.
   `-delete-object` returns false.

8. **This store makes the bytes readable by CID to anyone who has the CID.**
   `GET https://kotobase.net/ipfs/{cid}` is unauthenticated. The Drive ACL still
   decides who may reach the reference through this application, but it does not and
   cannot decide who may fetch the bytes once a CID is known. That is what a public
   content-addressed archive is, and it is the reason this store is opt-in rather than
   the default: **anything whose confidentiality depends on the ACL does not belong in
   it.** Repository state stays where ADR-0013 put it — sealed by Kagi before it
   reaches any transport.

## Implemented

- `cloud.itonami.app.archive` — the archive transport, extracted from `bundle` when
  the Drive became its third caller. `origin`, `max-object-bytes`, `raw-cid`,
  `location-cid`, `put!`, `get-bytes`. `bundle` and `graph` keep their names as
  aliases.
- `cloud.itonami.app.kotobase-objects` — the store, plus `IContentAddressed`, the
  question `documents` asks before it invents a UUID.
- `documents/default-store` gained the explicit selector; `documents/object-ref` takes
  the store and the bytes; `create!`, `write-resource!` and `upload!` all ask the store
  first. `upload!` falls back to the PieceCID it already used.
- `io-ipld` and `io-multiformats` overrides in `:test` advanced. The old pins were held
  because `io-multiformats dc520032` has no `multiformats.base32` and `ipld.link`
  requires it; `io-multiformats e7ae323` has both that namespace and the `sha384` the
  hold was protecting, so the conflict was stale rather than structural.

## Not implemented

- Packed blocks. One request per block is the write cost: a 100 MiB upload is 400
  leaves plus 3 nodes. ADR-2608160100 prefers a CARv2 pack, which needs range reads,
  and the archive plane serves whole objects. Chunking itself is not the avoidable
  part — it is what makes the CID the real one.
- UnixFS directories. The Drive owns the tree, the ACL and the versions; a UnixFS
  directory beside it would be a second answer to where a file lives, which is the same
  objection ADR-0047 raised against a directory for the app document.
- Migration of objects already in the fs or Storj stores.
- ~~`net-kotobase/ipfs` still ships the transposed codec.~~ **Fixed and deployed
  2026-08-17.** Its node codec now delegates to `ipld.dag-pb`, its conformance fixtures
  were republished as spec-conformant blocks (every dag-pb CID changed, every raw CID
  unchanged), and the live gateway serves a multi-block UnixFS file assembled from them
  — 38/38 conformance probes passing against worker version
  `8503b135-965f-4910-880f-145d0c02a827`. The CIDs it published before that were hashes
  of bytes no IPFS implementation accepts as DAG-PB, so they were never resolvable by
  anything but that Worker; they are gone rather than migrated.

## Verification

- `unixfs.file-test`: 11 tests, 391 assertions, 0 failures. Nine CIDs from kubo 0.41
  across empty / 1 byte / exactly one chunk / one chunk plus one byte / four chunks,
  and at 1 KiB chunks a full 174-link node, 175, 175 plus a remainder, and 30,277
  chunks (three levels above the leaves). Verified to discriminate: 173 links instead
  of 174, and passing a lone remainder through unwrapped, each fail it.
- `ipld.dag-pb-test`: 9 tests, 17 assertions, 0 failures, against a real 104-byte kubo
  root block. Verified to discriminate: transposing `Links`/`Data` and omitting the
  empty `Name` each produce a different CID and fail.
- `cloud.itonami.app.kotobase-objects-test`: 11 tests, 41 assertions, offline, transport
  injected as a map that refuses anything but a raw CID — the constraint the real
  archive enforces. Verified to discriminate on all three properties this ADR claims:
  making `location-cid` return the identity unchanged fails it (1 failure, 2 errors),
  dropping the reference check fails it (2), and writing the root before its children
  fails it (4). The last of those was added *because* the first attempt at this suite
  did not catch it — the ordering was asserted one layer down in `unixfs.file-test` and
  the store's own preservation of it was documented and unchecked.
- Whole app suite with the wiring and the dependency bump: 1620 tests, 9591 assertions,
  0 failures, 0 errors (1610/9555 before).
- 2026-08-17 live, against `https://kotobase.net`. A 262,145-byte file — the smallest
  input with a dag-pb root, so identity and location differ:

  ```
  identity  ipfs://bafybeieeyoibpdmm7we6bkycxcqas7kqnumhp2pk5fdbozcq5ixjfqn4gm
  location        bafkreieeyoibpdmm7we6bkycxcqas7kqnumhp2pk5fdbozcq5ixjfqn4gm
  ```

  Three blocks PUT (201), read back through the store byte-identical at 262,145
  bytes, then each location fetched again by unauthenticated GET: 200 at 262,144 / 1
  / 104 bytes. The 104-byte root is the same shape as the `ipld.dag-pb-test` fixture.
- The identity is not this application's opinion. `ipfs add -Q --cid-version=1
  --raw-leaves` over the same bytes prints
  `bafybeieeyoibpdmm7we6bkycxcqas7kqnumhp2pk5fdbozcq5ixjfqn4gm` — the string that
  went into `:drive/object-ref`.

## Consequences

- A file in the Drive now has an identity that outlives this application. Two people
  uploading the same file store one object without anyone implementing deduplication,
  because `drive.object/write-item` already allows a reference in use when the bytes
  are the same bytes and checks that against the store rather than trusting the caller.
- The reference is stable across backends. Moving from Storj to the archive later is a
  copy, not a re-identification.
- Writes are chattier than a single PUT and that is the number to watch.
- A workspace that switches stores holds references from both. Nothing reads the wrong
  one — a reference names the store that made it — but nothing migrates either.
