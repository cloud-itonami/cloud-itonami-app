# Awai external app runtime boundary

`cloud.itonami.app.awai-apps` is the host lifecycle coordinator for externally
published apps. It is separate from the built-in Itonami workspace view picker:
an external app has a signed app identity, content-addressed release, Awai cell
references, and an explicit local capability grant.

## Shared foundation model

Treat each public product as its own Awai app, with `itonami.app` supplying the
common launcher and host runtime. A domain is an entry alias for an app release;
it is not the app's identity. A release pins an immutable manifest and bundle
content, declares its requested capabilities and Awai DNA/cell dependencies,
and names the state migration required by the next release. App-specific UI,
schemas, and service behavior stay with that app. The common host owns
resolution, content verification, installation, isolation, capability consent,
updates, and rollback.

The Awai `app.registry` DNA is a signed discovery and binding log replicated
between validating peers. Its per-publisher predecessor chains are not a single
globally ordered ledger and do not claim block finality. App state belongs in
the app's declared Awai DNA cells and is validated by each participating node.
If an app needs a globally ordered operation, such as payment settlement, its
manifest must depend on a protocol that provides that property; the shared
launcher does not imply it. This keeps app-registry discovery, app data, and
external service protocols as separate trust and availability boundaries.

The public route is also separate from the app runtime. Independent DNS
authority maps a hostname to independently operated HTTPS gateways; those
gateways obtain the signed binding and immutable content from Awai peers and
content origins. DNSSEC/TLS prove the route and transport, while the signed
binding and CID checks prove app identity and bytes. Neither proof substitutes
for the other. Email routing remains a separate service plane.

## Lifecycle

1. The DNS adapter canonicalizes a hostname, validates its DNSSEC TXT proof,
   and returns the exact `awai-app=<binding CID>` value with freshness metadata.
2. The Awai adapter verifies the signed domain binding and release chain and
   returns normalized binding/release facts. `resolve-domain` passes those facts
   to `kotoba.protocol.install` and returns a fetch-and-consent plan.
3. The content adapter retrieves every required CID from configured independent
   origins, recomputes each CID, and supplies only the verified CID set to
   `finish-install` or `finish-update`.
4. The host asks the user about every new capability. Cell changes require a
   runtime-issued migration receipt. The host persists the returned installation
   record atomically and retains its prior record until the new release starts.
5. `launch-decision` can admit a cached release without DNS, registry, or
   content-network access. The last domain proof is reported as origin status;
   it does not grant app capabilities. Each host-mediated effect goes through
   `dispatch-operation`, which rechecks the protected installation and current
   host capability set before selecting a host-owned adapter. App code receives
   neither the installation record nor the adapter map.

## Host-owned adapters

The module does not choose DNS resolvers, Awai peers, content origins, local
storage, or a JavaScript/WASM execution sandbox. Those are injected host ports.
Each port must return verified facts or a structured failure; no port may fall
back to a Cloudflare Worker, Pages project, R2 object, or Tunnel origin. Network
and effect adapters remain outside the app bundle. Runtime integration must
route every app request through `dispatch-operation`; adapters must additionally
validate request scope, redact returned data, and provide operation receipts or
idempotency for effects whose outcome may be unknown after a failure. The
dispatcher reports adapter exceptions as indeterminate and unsafe to retry.
In ClojureScript hosts it returns a promise that settles to the same result map;
the synchronous JVM path returns that map directly.

## Current registry bridge

The Awai DNA host now has a pure read projection for accepted public
`app/release`, `domain/bind`, and `content/origin` entries. It emits the bounded
`awai.app-registry.read.v1` shape and converts each binary SPKI pin to lowercase
hex. The checked-out Awai source does not yet contain the loopback HTTP handler
at `GET /v1/app-registry`; the Itonami adapter therefore remains unavailable
until that handler is implemented and deployed. When present, Itonami will read
it only from an exact loopback URL in `ITONAMI_AWAI_REGISTRY_URL`, for example
`http://127.0.0.1:43180/v1/app-registry`, and will not try a public or Cloudflare
origin if the local node is absent. Itonami derives one deterministic
domain-to-release-tip candidate per domain by following accepted binding and
release predecessor chains. It fails closed on forks, gaps, duplicate CIDs,
disconnected history, and releases without live signed content origins. The
authenticated marketplace tab shows those candidates and marks DNSSEC proof,
manifest and bundle bytes, and revocation checks as outstanding.

The Itonami host now also has a one-block content adapter in
[`awai_content.cljk`](../src/cloud/itonami/app/awai_content.cljk). It accepts
only the `content/origin` records referenced by an accepted Awai release. The
release publisher selects at least three records; each record is signed by its
operator key and carries the app/publisher scope, canonical host, SHA-256 SPKI
pin, and expiry. Itonami checks the reference set, scope, freshness, unique
hosts, pins, and operator DIDs before transport. There is no environment
variable override for these origins. The JVM transport trusts each pinned key
and checks the certificate validity window and HTTPS hostname. A Cloudflare TLS
edge cannot substitute its own key when the selected pin belongs to the
independent origin operator. The portable policy is in
[`awai_content_policy.cljc`](../src/cloud/itonami/app/awai_content_policy.cljc);
it rejects known Cloudflare endpoint roots and suffixes, local-only hostnames,
expired entries, scope mismatches, missing pins, and repeated hosts, pins, or
operator identities. The policy does not resolve DNS, inspect CNAMEs or network
ownership, or prove independent operators.
The transport disables ambient proxies and redirects, caps each block at 16
MiB, and returns bytes only after the SHA-256 digest matches a CIDv1/raw,
dag-pb, or dag-cbor CID. If one origin fails, it tries the others. Distinct
operator identities in Awai records do not prove distinct real-world operators,
so that still needs deployment evidence. The adapter is not yet wired into the
install flow; it fetches one block at a time and does not walk or assemble an
IPLD/UnixFS bundle.

This connects the accepted registry read path and deterministic registry-chain
resolution to the Itonami launcher. It does not yet resolve live DNSSEC proofs,
verify manifest signatures in Itonami, walk and assemble all release DAG bytes,
write protected install locks, or launch app code. The tab therefore offers no
install or launch action. The existing lifecycle contract remains the gate for
those operations.

This is a shared lifecycle contract, not evidence of a production Awai
transport, a qualified app sandbox, or a deployed `itonami.app` launcher. A
sibling Awai repository now passes a two-process loopback replication check,
but that transport is not qualified across independent hosts. Independent
multi-origin retrieval, cell migration, sandbox isolation, and cross-node state
read/write still need implementation and qualification before any external
domain moves.

## Foundation readiness

| Shared component | Current state | Required evidence before a domain uses it |
|---|---|---|
| App identity and release history | `app.registry` validation and deterministic chain-tip selection are implemented; registry read is local-only. | Publish a non-fixture DNA/release, verify publisher and owner signatures in the Itonami host, and exercise revocation/key rotation. |
| Domain resolution | The host lifecycle accepts adapter facts; current UI marks DNS proof unverified. | Independently validate DNSSEC for `_itonami-app.<domain>`, bind it to one accepted `domain/bind` CID, and enforce proof expiry and rollback. |
| Content | A bounded, one-block SHA-256/CID verifier tries three configured HTTPS hostnames and fails over when one is unavailable; no DAG walk or install wiring exists. | Prove the origins have independent operators, walk and persist every referenced block, recompute every CID locally, reject redirects to unapproved origins, and complete a release fetch with one origin offline. |
| Local app host | Install, update, launch, and capability decisions exist as host-neutral policy; no external app executor is connected. | Store install records outside app-readable state, isolate app code and storage, deny ambient network/filesystem access, and mediate every host effect through a declared capability. |
| App state | The registry DNA replicates over the experimental peer host; app cell creation and migration are not wired to installation. | Install a declared DNA cell on independent nodes, verify its validation rules and state migration receipt, then demonstrate convergence and recovery after node loss. |
| Public serving | Existing Itonami web server and local TLS pieces are not a qualified Awai app gateway. | Serve the same immutable release from independent gateways over direct DNS and TLS, route by verified host binding, and pass external read/write checks without Cloudflare. |
| Operations | Candidate registry records are visible in the authenticated app tab. | Add host health, release revocation, update consent, operation receipts, backup/restore, and a tested rollback path. |

These are shared platform gates. A product migration starts only after one
reference app passes them end to end and remains usable with the launcher and
one serving node unavailable. Then each product supplies a small app-specific
adapter/migration package and proves its own state and protocol behavior; it
does not replace the common host.
