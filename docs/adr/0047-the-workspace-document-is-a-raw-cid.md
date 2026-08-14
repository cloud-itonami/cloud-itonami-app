# ADR-0047: the workspace document is a raw CID

**Status:** accepted — 2026-08-14

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

## Consequences

- Changing copy, CSS, or `interaction.js` changes the CID. Landing that
  change without updating the lock (and putting, when the archive token
  matches the live Worker) fails the suite.

- **Put 2026-08-14:** `PUT https://kotobase.net/ipfs/{cid}` against the
  CID computed before a later `main` fast-forward
  (`bafkreicbanjf7iaojle7suieajvj2dkbz43udqonyoimldsvtau7wqkbiq`)
  returned **401** `{"error":"unauthorized"}`. kagi item
  `KOTOBASE_ARCHIVE_TOKEN` is present (compartment `net-kotobase`);
  `KOTOBASE_ARCHIVE_TOKEN_2` is not. The Worker rejected the presented
  Bearer. Unauthenticated GET of that CID returned **404**. The lock CID
  after landing is the current tree; Location is not live until the
  Worker secret and the kagi item are the same value again.
- `{cid}.ipfs.itonami.cloud` may 404 if that origin worker does not read the
  same B2 archive. Retrieval that this slice proves is
  `https://kotobase.net/ipfs/{cid}`. Path-gateway HTML is served with CSP
  `sandbox allow-scripts` (opaque origin). Execution origin for HTML, when
  wired, is the subdomain gateway — not kotobase.net.
- Graph snapshot CID (`:kotoba.graph/cid`) is still a later slice. Desktop
  kgraph currently asserts locally and does not publish a snapshot.
