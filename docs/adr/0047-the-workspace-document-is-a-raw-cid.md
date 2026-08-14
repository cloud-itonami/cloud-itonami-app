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
4. **Do not publish IPNS `:kotoba.app/latest` in this slice.** Point a name
   at a CID only after the put has been GET-verified.
5. **`resources/cloud/itonami/app/kotoba.app.edn` is the last computed
   identity.** Tests recompute the CID from source and fail if it drifted
   without updating the lock. The HTML blob itself is not committed.
   `:published` is filled only after a GET-verified put.

## Identity of the landed tree

```
ipfs://bafkreiey52hai5obtqeg5w2ix63orset4o74kxljif2gwdkxt5upre2wsi
```

801,104 bytes. App id `cloud.itonami.app`, kind `appview`,
`appview-of` `{:workspace "desktop"}`.

## Implemented

- `cloud.itonami.app.bundle` freezes `publication-config`, hashes UTF-8
  `page-html` as CIDv1 raw sha2-256, builds a `kotoba.protocol.app`
  manifest, and can PUT/GET `https://kotobase.net/ipfs/{cid}`.
- `io.github.kotoba-lang/kotoba-protocol` git pin
  `ca72b830ec27a14e562be2a8dcf92f68901e486b`.
- Offline tests in `bundle_test.clj`, registered in `test_runner.clj`.

## Not implemented

- IPNS `:kotoba.app/latest`.
- Graph snapshot CID (`:kotoba.graph/cid`). Desktop kgraph still asserts
  locally.
- Moving `auth.itonami.cloud` or enrolment at `itonami.cloud/signin/`.

## Verification

- `cloud.itonami.app.bundle-test`: 5 tests, 21 assertions, 0 failures, 0
  errors. No network.
- 2026-08-14 Location: `PUT` 201 then unauthenticated `GET` 200 of
  `https://kotobase.net/ipfs/bafkreiey52hai5obtqeg5w2ix63orset4o74kxljif2gwdkxt5upre2wsi`
  (801,104 bytes, Java HttpClient byte-equal to `page-html`). Worker
  `KOTOBASE_ARCHIVE_TOKEN_2` accepts the kagi `KOTOBASE_ARCHIVE_TOKEN`
  (rotation-by-addition; primary slot was left in place). Archive GET
  `Cache-Control` includes `no-transform` so Cloudflare Web Analytics
  cannot inject `beacon.min.js` into the HTML.

## Resume

IPNS `:kotoba.app/latest` pointing at this CID. Do not move the auth host.

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
