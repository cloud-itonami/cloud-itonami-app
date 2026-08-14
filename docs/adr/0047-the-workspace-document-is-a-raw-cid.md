# ADR-0047: the workspace document is a raw CID

**Status:** accepted — 2026-08-14
**Default branch:** landed (merge `5c196db84a4cd88645b63985c7a90469f3f57389`)
**West pin:** `c04a934a8d0a702c5a22f5ede4db4cc6ba960154` → `5c196db`

## Context

ADR-0045 made views `#/name` fragments of one document and left content
identity for a later slice. `kotoba.protocol.app` already names an app by
`:kotoba.app/bundle-cid` plus an `ipfs://` or `ipns://` embed-url. HTTPS is
Location (ADR-2608140500), not identity. Fragments on `ipfs://` are refused
as identity (ADR-2608145100).

The bytes that ship are `web/page-html`: one HTML document with inlined
script. A UnixFS directory would be a second identity for the same page.
`auth.itonami.cloud` cannot become a CID: WebAuthn RP ID is `itonami.cloud`,
and enrolment KEK lives on that Worker. Serving the ceremony from
`{cid}.ipfs.itonami.cloud` would change origin and break assertion.

## Decision

1. **The desktop app's content identity is the UTF-8 bytes of one
   unauthenticated `page-html` document**, addressed as CIDv1 raw sha2-256
   (`bafkrei…`). `:kotoba.app/bundle-cid` and `:kotoba.app/embed-url` are the
   same CID (`ipfs://{cid}`).
2. **Put those bytes at `PUT https://kotobase.net/ipfs/{cid}`.** GET is
   unauthenticated. The server recomputes the digest. This is Location, not
   a second identity.
3. **Do not move the auth host.** `https://auth.itonami.cloud` stays the
   ceremony Name. The CID is a snapshot of the client document.
4. **Publish IPNS `:kotoba.app/latest` only after the put has been
   GET-verified.** The name is this app's own Ed25519 key (`k51…`). The
   record's Value is `/ipfs/{cid}`. HTTPS Location stays
   `GET https://kotobase.net/ipfs/{cid}`. kotobase.net `GET /ipns/` is 410
   (ADR-2608130000); do not restore the P2P proxy. Resolve via delegated
   DHT routers (`kad.routing`).
5. **`resources/cloud/itonami/app/kotoba.app.edn` is the last computed
   identity.** Tests recompute the CID from source and fail if it drifted
   without updating the lock. The HTML blob itself is not committed.
   `:published` is filled only after a GET-verified put. `:kotoba.app/latest`
   is filled only after a DHT GET-verified publish. `:kotoba.app/embed-url`
   stays `ipfs://{cid}` (snapshot); `ipns://{k51}` is the update channel.
6. **L2 graph identity is `:kotoba.graph/cid`**, the dag-cbor CID from
   `chain.core/commit!` of one overlay edge (workspace `desktop` → the
   GET-verified bundle CID). Protocol does not hash (ADR-2608145400).
7. **Archive Location of that commit is the raw CID of the same bytes.**
   kotobase `PUT /ipfs/:cid` accepts only raw CIDv1. Do not PUT the
   identity CID string (400 `not-raw-sha256`). Do not put the raw CID on
   a `:kotoba.*` key (ADR-2608148200).
8. **Do not set `:kotoba.graph/head`.** That is naming (IPNS), not this
   snapshot. Session kgraph datoms stay local.

## Identity of the landed tree

Snapshot (GET-verified archive, after #85 titlebar):

```
ipfs://bafkreiesyfwcohnsr47o2eapym5ahwxzghpxpej253k6d4wygj4hrfnhp4
```

797,675 bytes. App id `cloud.itonami.app`, kind `appview`,
`appview-of` `{:workspace "desktop"}`. Previous snapshot
`ipfs://bafkreig4jyeaynm47icfmqj3m5ya7iep2o7c4vk34pwpafm7tnf4tfimny`
(806,249 bytes) remains GET-able; it is no longer the lock.

Update channel:

```
ipns://k51qzi5uqu5dj6z20sjzztyay81591voe6yofukl0ylsmug9euf934z1g04erd
```

Sequence 3 origin GET-verified 2026-08-14T13:39:27Z: origin at
`delegated-ipfs.dev` (Cloudflare `cf-cache-status: MISS`, cache-bust
query) returns value
`/ipfs/bafkreiesyfwcohnsr47o2eapym5ahwxzghpxpej253k6d4wygj4hrfnhp4`.
The default URL without a cache-bust query may still serve sequence 1
until ~13:47Z (`Cache-Control: max-age=3600` from 12:47Z). Later records
from this publisher use a 5-minute TTL.

Graph snapshot (GET-verified archive, 181 bytes):

```
ipfs://bafkreifwintjn6lddprilx7xpbxrtorzkuxhjwhs7c4x3c4aippelcipnu
```

`:kotoba.graph/name` `desktop`. `:kotoba.graph/cid`
`bafyreifwintjn6lddprilx7xpbxrtorzkuxhjwhs7c4x3c4aippelcipnu`
(dag-cbor chain commit of the overlay onto the current bundle CID).
Location is the raw CID of the same bytes. `:kotoba.graph/head` is not
set.

## Implemented

- `cloud.itonami.app.bundle` freezes `publication-config`, hashes UTF-8
  `page-html` as CIDv1 raw sha2-256, builds a `kotoba.protocol.app`
  manifest, and can PUT/GET `https://kotobase.net/ipfs/{cid}`.
- `cloud.itonami.app.latest` signs a real IPNS Record (`ipns.record`)
  over `/ipfs/{cid}` and publishes it through `kad.routing` to delegated
  DHT routers. Seed: env `CLOUD_ITONAMI_APP_IPNS_SEED` / kagi
  `cloud-itonami-app-latest`.
- `io.github.kotoba-lang/kotoba-protocol` git pin
  `ca72b830ec27a14e562be2a8dcf92f68901e486b`.
- `io.github.kotoba-lang/chain` git pin
  `9646b8b858085fdb1172482ce9ce77d6739c75de`. Hasher is
  `chain.core/commit!` (ADR-2608145400). Protocol does not hash.
- `cloud.itonami.app.graph` seals one overlay edge (workspace `desktop`
  → GET-verified bundle CID) and records `:kotoba.graph/cid` (dag-cbor
  chain CID). Archive Location is the raw CID of the same commit bytes.
  Session kgraph datoms stay local. `:kotoba.graph/head` is not set.
- Offline tests in `bundle_test.clj`, `latest_test.clj`, and
  `graph_test.clj`, registered in `test_runner.clj`.

## Not implemented

- Moving `auth.itonami.cloud` or enrolment at `itonami.cloud/signin/`.
- Storage-backed `GET /ipns/` on kotobase.net (410 until a signed-record
  archive plane exists). Identity of the channel is still `ipns://{k51}`.

## Verification

- `cloud.itonami.app.bundle-test`: 5 tests, 21 assertions, 0 failures, 0
  errors. No network.
- `cloud.itonami.app.latest-test`: 10 tests, 30 assertions, 0 failures, 0
  errors. Injected router; disposable seed `(byte-array (range 32))`.
- `cloud.itonami.app.graph-test`: 8 tests, 32 assertions, 0 failures, 0
  errors. Offline in-memory chain store plus lock ratchet. No live PUT
  in the suite.
- 2026-08-14 Location: `PUT` 201 then unauthenticated `GET` 200 of
  `https://kotobase.net/ipfs/bafkreiey52hai5obtqeg5w2ix63orset4o74kxljif2gwdkxt5upre2wsi`
  (801,104 bytes, Java HttpClient byte-equal to `page-html`). Worker
  `KOTOBASE_ARCHIVE_TOKEN_2` accepts the kagi `KOTOBASE_ARCHIVE_TOKEN`
  (rotation-by-addition; primary slot was left in place). Archive GET
  `Cache-Control` includes `no-transform` so Cloudflare Web Analytics
  cannot inject `beacon.min.js` into the HTML.
- 2026-08-14 IPNS: name
  `k51qzi5uqu5dj6z20sjzztyay81591voe6yofukl0ylsmug9euf934z1g04erd`
  (kagi item `cloud-itonami-app-latest`, compartment `personal`). Sequence 1
  value `/ipfs/bafkreiey52hai5obtqeg5w2ix63orset4o74kxljif2gwdkxt5upre2wsi`.
  `kad.routing/publish` accepted `https://delegated-ipfs.dev/routing/v1`.
  Independent GET of that router with `Accept: application/vnd.ipfs.ipns-record`
  returned 200 (331 bytes). Sequence 1 (12:47Z, CF HIT) value
  `/ipfs/bafkreiey52hai5obtqeg5w2ix63orset4o74kxljif2gwdkxt5upre2wsi`.
  Sequence 2 origin GET 200 at 13:02:53Z (`cf-cache-status: MISS`, cache-bust
  query) value `/ipfs/bafkreig4jyeaynm47icfmqj3m5ya7iep2o7c4vk34pwpafm7tnf4tfimny`
  (then-current bundle CID). Archive GET of that CID remains 200 / 806,249
  bytes. `GET https://kotobase.net/ipns/{k51}` remains 410 (ADR-2608130000).
- 2026-08-14 after #85: document CID
  `bafkreiesyfwcohnsr47o2eapym5ahwxzghpxpej253k6d4wygj4hrfnhp4`
  (797,675 bytes, PUT 201 / GET 200 byte-equal). L2 graph chain CID
  `bafyreifwintjn6lddprilx7xpbxrtorzkuxhjwhs7c4x3c4aippelcipnu`.
  Location PUT 201 then independent GET 200 of
  `https://kotobase.net/ipfs/bafkreifwintjn6lddprilx7xpbxrtorzkuxhjwhs7c4x3c4aippelcipnu`
  (181 bytes, byte-equal). IPNS sequence 3 origin GET 200 at 13:39:27Z
  (`cf-cache-status: MISS`) value `/ipfs/bafkreiesyfwcohnsr47o2eapym5ahwxzghpxpej253k6d4wygj4hrfnhp4`.
  Auth hosts were not moved. kotobase `/ipns/` remains 410.

## Resume

Do not move the auth host. Default delegated GET may still show sequence
1 until ~13:47Z; origin already serves sequence 3.

## Consequences

- Changing copy, CSS, or `interaction.js` changes the CID. Landing that
  change without updating the lock (and putting, when the archive token
  matches the live Worker) fails the suite.
- `{cid}.ipfs.itonami.cloud` may 404 if that origin worker does not read the
  same B2 archive. Retrieval this slice *would* prove is
  `https://kotobase.net/ipfs/{cid}`. Path-gateway HTML is served with CSP
  `sandbox allow-scripts` (opaque origin). Execution origin for HTML, when
  wired, is the subdomain gateway — not kotobase.net.

## 改訂履歴

- 2026-08-14: accepted. Compute + lock + tests landed on `main`.
- 2026-08-14: closing record — merge SHA, west pin, current CID, PUT 401,
  resume command.
- 2026-08-14: archive Location live (PUT 201 / GET 200 / byte-equal).
  Worker TOKEN_2 + no-transform. Next: IPNS latest.
- 2026-08-14: IPNS `:kotoba.app/latest` — real sequence-1 record on
  delegated DHT (previous snapshot). Sequence 2 PUT 200, GET still TTL-
  cached. kotobase `/ipns/` stays 410. Next: GET-verify onto current CID,
  then graph CID.
- 2026-08-14: `:kotoba.graph/cid` sealed via `chain.core/commit!` onto
  the post-#85 document CID. Location GET-verified (181 bytes). IPNS
  sequence 3 origin GET-verified onto the new bundle CID. Next: do not
  move the auth host.
- 2026-08-14: Decision 6–8. Identity vs archive Location is repo-wide
  ADR-2608148200.
