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

Each signed `domain/bind` now pins one exact release CID. Publishing a new
app release does not silently change the public domain; the domain owner must
sign a new binding and update its DNSSEC proof. This source change is not yet
merged or requalified.

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
2. The Awai adapter verifies the signed domain binding and the exact release it
   pins, checks that app, publisher, and channel match, then returns normalized
   binding/release facts. `resolve-domain` passes those facts to
   `kotoba.protocol.install` and returns a fetch-and-consent plan.
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

The active Node/WASM host binds only to `127.0.0.1` and rejects any configured
external bind address or malformed/out-of-range port. Public TLS and direct DNS
belong to an independently operated gateway in front of that local process; the
host itself does not terminate TLS or expose an unauthenticated plaintext
listener on a public interface.

## Current registry bridge

The Awai DNA host emits the bounded `awai.app-registry.read.v4` projection for
accepted public `app/release`, `app/revoke`, `domain/bind`, and
`content/origin` entries. Each response includes canonical signed envelopes
and a checkpoint signed by that node's persistent libp2p Ed25519 identity; the
signature covers the DNA CID, peer ID, issue time, and complete ordered
record-CID set. The Awai host gate starts two fixture peers and verifies each
checkpoint against its peer ID. Both peers ran on one machine, so this does not
qualify independent operators or public peer discovery.

Itonami accepts a configured set of 3–12 distinct peer IDs and operator-DID
labels through `ITONAMI_AWAI_REGISTRY_PEERS`. Local checks may use a specific
`http://127.0.0.1:<port>/v2/app-registry` endpoint; remote peers require a
public DNS hostname over HTTPS on port 443. Plain HTTP to remote hosts,
IP-literal HTTPS endpoints, local-only DNS suffixes, alternate paths,
credentials, query strings, fragments, and redirects are refused. The operator
must configure independent peer operators and direct, non-CDN origins; endpoint
syntax checks cannot prove DNS ownership or the absence of a proxy. The adapter
verifies Ed25519 checkpoint signatures, requires issue
times within a 120-second age and 30-second future-skew bound, and accepts only
one exact accepted-record CID set with at least `ceil(2N/3)` configured peer
attestations. Two current peers can therefore outvote one stale peer in a
three-peer set. Operator-DID uniqueness is a local configuration assertion;
it does not prove independent operation, and a matching quorum is not global
consensus or proof that no majority has omitted a record.

The Node/Nbb signature helper and quorum policy have focused checks (two crypto
tests plus quorum cases in the 19-test Itonami suite). The live Awai HTTP API
signature was checked on two fixture peer processes. The full Java server
bridge has not yet been exercised end to end against multiple peers; neither
production DNA nor independent peer operations are qualified. The authenticated
Itonami route returns a candidate only after its configured quorum succeeds
and does not fall back to a public or Cloudflare origin. Itonami derives one deterministic
domain-to-release-pin candidate per domain by following the accepted binding
chain and resolving each binding's exact `release-cid`. Release updates require
a new domain binding; candidates do not float to the channel tip. It fails closed on forks, gaps, duplicate CIDs,
disconnected history, and releases without live signed content origins.
The active local discovery response carries the release-selected public
content-origin records needed by the later host fetch adapter, while omitting
derived origin objects and unrelated candidate fields. A configured quorum is
discovery evidence, not install authorization; domain DNSSEC, bundle/CID
verification, and local capability consent remain separate checks.
The authenticated marketplace tab source shows those candidates and the local
install-record status. The active Node/WASM host serves a launcher at `GET /`
and `/awai-launcher.js`; it displays registry candidates and offers a
same-origin install-plan preview plus a loopback-only capability-consent and
install flow. It can also launch a locally installed bundle in a separate
per-app `.localhost` origin, served from CID-verified content blocks and
embedded with a browser sandbox. This launch path serves bundle assets; the
fixture smoke is not a successful production installation or an external app
deployment.
It also exposes read-only
`GET /api/awai/domain-proof?domain=...`. Before resolving DNS, this route reads
the envelope-verified local registry snapshot and requires an exact domain
candidate with `decision: candidate`; an unavailable peer returns 503 without
starting a DNS lookup. For an admitted candidate, the host returns the DNSSEC
proof and whether its binding CID equals the candidate's accepted binding CID.
The route is diagnostic evidence only and does not authorize installation. The
launcher also offers `POST /api/awai/install-plan?domain=...`, which requires a
same-origin request, repeats the accepted-candidate and DNS binding checks,
fetches and verifies the release manifest from its signed content origins, and
returns requested capabilities and content CIDs for review. It may cache the
verified manifest in the private host store; this preview route does not request
consent, write an install lock, install an Awai cell, or launch app code.
The request Origin must exactly equal `ITONAMI_APP_ORIGIN`; without that
setting, only the loopback launcher origin is accepted. An independent public
gateway must set its exact HTTPS launcher origin before exposing this action.
The current host and guest source adds read-only
`GET /api/awai/platform-status` for the active host's capability boundary and
whether its one configured local registry peer is reachable. It reports
`local-install-only`; the `awai-cell-install` field separately shows whether
the exact loopback API and private token file are configured, not whether the
peer is reachable. Consent and install routes accept only the matching
loopback Origin and Host. The Node/WASM host now serves a locally installed
bundle on its app-ID-specific `.localhost` origin and embeds it with
`sandbox="allow-scripts"`, leaving the app with an opaque origin and no access
to cookies or local storage. The app-origin CSP allows only static resources
from that app host; the asset route permits non-credentialed `Origin: null`
reads for those public bundle bytes. Network connections, nested frames,
workers, forms, and plugins are disabled; no host capability bridge is
connected. Against a local dummy install, the launch request and entry/JS asset
reads returned 200, `Origin: null` received the read-only CORS response, and a
foreign Origin returned 403. The sandbox policy tests passed 2 tests and 12
assertions. This smoke checked HTTP boundaries, not browser-rendered app
behavior. It is local static launch evidence, not a qualified public runtime
or a successful production Awai install. The
independent public gateway remains disconnected. This status
route is an operator diagnostic, not a production readiness claim.
On 2026-10-16, the Node-compatible focused Awai suite passed 8 namespaces,
26 tests, and 100 assertions using the installed Nbb 1.4.208. The project lock
pins Nbb 1.5.211, so that pinned runtime is not covered by this result. The
JVM-backed install-store adapter and full repository suite were not verified.
These are source tests only and do not qualify an external gateway. An earlier
guest Wasm build used AMU
`8435eafb7dada2d3a85cee2c278ca6d38deb7588`, kotoba-lang
`bfe618b3a3844d5563a943d63bdefcdc535af98c`, and Nbb `1.5.211`; its SHA-256 is
`c885fa5de378aafb7080da184ce22ded3918412dc1b385bdc80fc3613fd89de0`.
`amu verify-output-set` accepted the artifact identity, Wasm validation, and
sealed provenance; publisher authentication remains false. A loopback readback
returned 200 for `/health`, `/`, `/awai-launcher.js`, and
`/api/awai/platform-status`. At that earlier readback, status was
`discovery-only` with the registry reader unavailable. `/api/awai/app-registry` returned 503
`unconfigured` with no local peer configured. Neither route falls back to a
public or Cloudflare endpoint. A later local guest rebuild included the
`POST /api/awai/install` admission export and emitted WASM SHA-256
`baf6cb0c18311452f36505acc369a8b2e56bc46635eb63a7eca89c5c689eeaca`;
`amu verify-output-set` passed. The Node host loaded and returned `/health`
200. The install route returned 503 `registry-unavailable` for a valid local
request with no peer, 403 for a foreign Origin or Host, and 400 for
`application/jsonp`. This confirms guest route dispatch and local request
boundaries only; no install or Awai cell transfer occurred. The DNSSEC helper
is built locally with `bin/build-awai-dnssec-proof`. The current status readback
reports `local-install-only`, `registry-reader: unavailable`, and
`awai-cell-install: local-bridge-unconfigured`.
The helper uses libunbound and ldns to validate responses against packaged
IANA root DS anchors and extract the TXT RRset, TTL, and RRSIG expiry from the
validated answer. A signed public TXT smoke returned `verified` with
TTL and signature expiry. The helper was built on macOS; a production package still
needs a platform-matched libunbound/ldns runtime or a reviewed static helper, and
root-anchor refresh needs a reviewed release process. No migration hostname has a signed Awai binding. In a loopback integration
smoke, an isolated Awai peer replayed eight signed fixture records and Itonami
verified their envelopes and returned the `murakumo.cloud` candidate. The proof
route returned 409 `dnssec-not-secure`; the same-origin plan preview returned
the same refusal before fetching a manifest. An unlisted `example.com` request
returned 404 before DNS resolution. The fixture is not a production DNA, peer,
or app release, so the proof-to-manifest path remains unqualified against a
live candidate.
Local bundle bytes, install consent, and static sandbox launch now have source
implementations; the dummy local install passed the launch and asset smoke.
Production bundle delivery, revocation against an independently converged
registry, consented updates, and sandbox qualification against an Awai-backed
release remain outstanding. Candidate responses do not return local
capability grants.

Before publishing candidates, the Itonami reader verifies every registry
envelope itself: bounded hex decoding, canonical DAG-CBOR round-trip, entry CID,
registry DNA, Ed25519 signature, signer-to-publisher/owner/operator identity,
and exact equality between the signed body and its JSON projection. This
removes the need to trust the node for envelope authentication. The node's
claim that an entry belongs to accepted state is still a separate trust fact;
the loopback bridge does not establish multi-peer agreement or finality.

The Itonami host now also has a content-block and DAG traversal adapter in
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
so that still needs deployment evidence. A plan-aware batch entrypoint walks
each required root and every reachable DAG-CBOR, UnixFS DAG-PB, and raw block,
rechecking origin expiry before each fetch. The bounded walk stages blocks in
a content-addressed store under the private Itonami data directory, with a
4,096-block and 1 GiB aggregate ceiling; each block is capped at 16 MiB. Each
write uses an owner-only temporary file, file force, atomic rename, and CID
readback. It returns verified CIDs and storage receipts, never app-visible
bytes. The active Node host now has parallel owner-only CID storage in
[`awai_content_store_node.cljk`](../src/cloud/itonami/app/awai_content_store_node.cljk)
and a Node HTTPS/DAG fetch adapter in
[`awai_content_node.cljk`](../src/cloud/itonami/app/awai_content_node.cljk).
[`awai_manifest_node.cljk`](../src/cloud/itonami/app/awai_manifest_node.cljk)
composes those ports to retrieve and verify a candidate's immutable public
manifest before caching it.
The Node adapter applies the same signed-origin scope, three-distinct-origin,
expiry, CID, block-count, and aggregate-byte rules; it rejects redirects and
ambient proxy configuration and adds the accepted SPKI pin to Node's default
certificate-chain and hostname checks. Focused local smoke confirmed module
loading and fail-closed refusal for an invalid CID, an unprepared install, and
an install with no signed origins; the manifest adapter refused an empty signed
origin set before network access. The content store separately passed local
write/readback, idempotency, CID mismatch, symlink, and POSIX permission
checks. The active host exposes DNSSEC through candidate-gated proof and plan
routes. It also exposes `POST /api/awai/install-content?domain=...` only from
the exact `http://127.0.0.1:<port>` origin and matching Host header. That route
repeats registry, DNSSEC, binding-CID, and manifest checks before fetching all
required blocks from signed content origins and storing them in the private
CID store. It returns verified block CIDs and counts; it does not request
capability consent, write an install lock, install a DNA cell, or run app code.
The route has not yet completed a successful end-to-end content retrieval.
[`awai_bundle.cljk`](../src/cloud/itonami/app/awai_bundle.cljk) now
resolves a manifest entry through a normal
UnixFS directory and reassembles raw or DAG-PB file blocks from this store,
rechecking every CID and enforcing 64 MiB, 4,096-block, and depth limits. It
rejects HAMT-sharded directories and does not verify release signatures,
persist an installation, or serve bytes to an app. The bundle reader is not
connected to an install or launch action.

The canonical app definition remains `kotoba.protocol.app`: the publisher
authors `:kotoba.app/*` datoms in its L5 graph. At release time, the publisher
exports that entity as a canonical `kotoba.app.manifest.v1` DAG-CBOR block;
the signed Awai `app/release` binds the block CID to the publisher DID, app,
channel, and sequence. Public installation therefore reads the immutable
release block and does not need the publisher's graph decryption key. This
export is a release artifact, not a second authoring schema, and its CID binding
does not by itself prove the graph's complete authorization history.

Itonami retains the release-manifest verifier and draft
`/api/bots/awai-app-install-plan` logic in `server.cljk`; that namespace is the
legacy JVM server and is not loaded by the active Node/WASM host. The Node/WASM
host now has its own candidate-gated `/api/awai/install-plan` preview route.
With no peer configured, its loopback readback returned 503
`registry-unavailable`. Against the isolated fixture peer, Itonami verified
eight signed envelopes, returned the `murakumo.cloud` candidate, and stopped
proof and plan requests at 409 `dnssec-not-secure`; no manifest bytes were
fetched. The content-stage route returned the same 409 before manifest or app
content retrieval; its scratch content store remained empty. The fixture is
not a production DNA or app release. No route has yet completed a live content
fetch. The local guest
WASM was compiled with the pinned Amu/Nbb toolchain and passed
`verify-output-set`; the route remains a local build, not a deployed or
publisher-authenticated release. The
protected install store can now derive an exact Awai cell-authorization receipt
from the persisted L5 install lock, requiring the requested app-local cell ID
and DNA bundle CID to match `:cells`. This convenience function is not called
directly by the active host; the install bridge performs its own exact
installation-record and cell/bundle checks. A separate local adapter in
[`awai_install_bridge.cljk`](../src/cloud/itonami/app/awai_install_bridge.cljk)
can read CID-verified raw/DAG-CBOR blocks from the owner-only Itonami content
store, require an existing protected install record and its exact cell/bundle
authorization, then call the Awai loopback staging route for every block before
requesting DNA installation. Its deterministic fake-peer smoke verifies that
ordering and refuses an undeclared cell. The active Node/WASM host now exposes
`POST /api/awai/install`, accepts explicit per-capability choices only from the
exact `127.0.0.1` Origin and Host, rechecks the candidate, DNSSEC proof, and
release for that install request, stages verified content, commits and reads back the protected install record, then
uses the configured token-protected Awai loopback API for each declared cell.
On a cell failure it rolls back the local record and reports cells already
accepted by Awai. At the time of that install-route smoke, app launch was
disconnected. This is source/build evidence only: the fixture candidate still stops at
`dnssec-not-secure`, so the full launcher path has not completed a
DNSSEC-gated Itonami-to-Awai install.

The Itonami worktree now also contains a Node-compatible protected install
store port in [`awai_install_store_node.cljk`](../src/cloud/itonami/app/awai_install_store_node.cljk).
It uses the existing EDN record schema, app-keyed filenames, bounded readback,
owner-only files, atomic replacement, expected-release compare-and-swap, and
the same per-app guard directory as the JVM store. This closes the storage
format/runtime mismatch at source level; the new loopback consent route calls
the Node store for first-install commit, protected readback, and failure
rollback. A process crash can leave a guard directory behind, which blocks
writes until an operator confirms the owner stopped and removes that guard.

The sibling Awai worktree now contains optional `POST /v2/stage-dna-block` and
`POST /v2/install-dna` loopback APIs. The stage route accepts one bounded
raw/DAG-CBOR block, verifies its CID, and writes/readbacks the block in Awai's
owner-only CID store. The install route requires a POSIX owner-only 32-byte
token file, exact loopback `Host`, no `Origin`, and an exact
app/release/cell/bundle scope; it reads bundle blocks from Awai's configured
local CID store, verifies the DNA, persists the descriptor, and adds the cell
to the live node state. The localhost test covers unauthorized/browser/
malformed requests, CID mismatch, idempotent staging, and store readback. The
token is local caller authority, not proof of user consent. Itonami collects
consent in its launcher separately. A two-process loopback smoke on 2026-10-16
called the Itonami bridge against this live API: it staged all five fixture
bundle blocks, installed the exact cell, submitted two signed chained entries,
observed rejection of a broken signature, and read both accepted entries back.
The smoke supplied a fixture installation record directly to the adapter; it
does not qualify the full DNSSEC/candidate/launcher/consent/protected-store
path, production releases, or independent Awai nodes.

The v2 registry projection carries the complete signed Awai envelope, and
Itonami verifies its CID, DNA, signature, signer binding, and body/projection
equality. The remaining registry trust gap is accepted-state membership: one
loopback node's report does not prove that independent validators agree on the
same accepted chain. The Itonami `chain` pin includes
`chain.authorized/verify-authorized`, and
[`awai_graph_authority.cljc`](../src/cloud/itonami/app/awai_graph_authority.cljc)
requires a grant resolver to attest grant CID, signature, app scope, and
`kotoba://can/datom:transact` at an explicit time and revocation policy. That
adapter remains separate from public release-block verification; no production
graph grant resolver is connected. Publisher rotation still needs an explicit
accepted registry record. Publisher-signed release revocations are part of the
v4 read projection and block matching release candidates; this source path
still needs cross-node and production qualification.

The registry route connects the accepted registry read path and deterministic
registry-chain resolution to the active Itonami host. In a fixture-backed
integration smoke, the experimental peer-host replayed eight signed entries
on fixture DNA and Itonami returned the `murakumo.cloud` candidate for
`net.kotoba.murakumo` after envelope verification. This does not qualify a
production DNA, independent hosts, or multi-peer accepted-state agreement; no
real peer service is deployed. The peer-host projection includes the
operator-signed content origins that Itonami requires. The active Node host
resolves `_itonami-app.<domain>` through a native libunbound/ldns helper using
packaged IANA root DS anchors. It accepts only a locally DNSSEC-validated exact
TXT answer, rejects aliases and multiple answers, and bounds proof freshness
by the authenticated TTL and RRSIG expiry. The route first requires an
envelope-verified registry candidate and returns a separate comparison against
that candidate's accepted binding CID. A local positive smoke validated one
signed public TXT RRset; this validates the helper path, not an Awai app
binding. No migration domain has a live signed Awai TXT proof, and the last
provider snapshot recorded DNSSEC disabled for the ten inventoried zones. The
loopback install route now writes a protected install lock and can invoke the
token-protected Awai cell API after user consent; it does not launch app code.
No fixture or production run has passed the DNSSEC gate through to successful
installation.

This is a shared lifecycle contract, not evidence of a production Awai
transport, a qualified app sandbox, or a deployed `itonami.app` launcher. A
2026-10-16 integration gate now starts two separate Awai processes with
separate stores and verifies bidirectional signed-entry transfer over the
TCP/Noise/Yamux transport, identical accepted CIDs, and rejection of a broken
signature. It also verifies that an owner-only persisted Ed25519 peer identity
survives restart and that the accepted log replays. Both processes run on one
host with fixture DNA; independent operators, public reachability, and
cross-network convergence remain unqualified. Independent multi-origin
retrieval, cell migration, sandbox isolation, and state recovery after node
loss still need implementation and qualification before any external domain
moves.

The Awai peer-host worktree exposes token-protected local
`GET/POST /v2/cell-entries`: reads return accepted entries with bounded
CID-pagination, and writes pass signed-entry bytes through the DNA validator.
The refreshed loopback test passed 29 checks. It stages a five-block fixture
DNA bundle by CID, refuses installation before all blocks are staged, installs
the cell through the HTTP API, persists and re-verifies its validator and
descriptor, and replays its local entry log after restore. It then submits two
independently signed and correctly chained note entries, confirms duplicate
idempotency and rejection of a broken signature, and reads back both accepted
notes. This proves the Awai single-host install/API path only; it does not
prove independent-node replication. The deterministic fake-peer client test
remains adapter-only evidence; a separate fixture-backed integration runner now
calls the same Itonami adapter against the live local Awai API and verifies
bundle transfer, installation, signed writes, validator rejection, and accepted
readback. It does not qualify the DNSSEC-gated launcher path or independent
nodes.
Itonami connects both endpoints through `dispatch-operation` and a
`MessageChannel` to the opaque-origin iframe. The shared capability registry defines `awai.cell.read` and
`awai.cell.write`; each operation rechecks the protected install record,
app-declared cell scope, verified release content, and local grant. The iframe
never receives the loopback bearer token. Eleven client checks pass against a
deterministic fetch peer. The real-peer integration runner is an additional
two-process fixture smoke; it is not part of the fake-peer client test or the
full launcher install flow.

## Foundation readiness

| Shared component | Current state | Required evidence before a domain uses it |
|---|---|---|
| App identity and release history | `app.registry` accepts publisher-signed `app/revoke` records for exact accepted release CIDs. Each domain binding pins one accepted release; Itonami resolves that exact release and rejects revoked pins. Read v4 checkpoints bind each response to a libp2p peer identity; the adapter source requires a fresh two-thirds matching CID-set quorum across 3–12 configured peer IDs. The base runtime merged as Itonami PR #431; current v4/quorum and public-HTTPS endpoint follow-ups plus the 20-test focused run are on the unmerged branch. Operator independence, the JVM bridge end-to-end path, and resistance to a stale majority remain unqualified. | Verify operator identities and independent network paths; prove one stale peer cannot control routing while the remaining quorum is current; connect a production graph grant resolver if required; add publisher rotation before installation. |
| Domain resolution | The active Node host checks an exact DNSSEC TXT RRset, authenticated TTL/RRSIG lifetime, and equality with the accepted binding CID. A signed public TXT smoke validates the helper, and the current resolver suite verifies exact release pins; no Awai migration binding has passed. | Refresh root anchors through a reviewed release process; qualify secure, insecure, bogus, alias, mismatched-CID, and expiration cases; publish and independently read a real signed binding and matching DNS proof. |
| Content | The active Node/WASM host exposes a loopback-only content-stage route that revalidates the registry candidate, DNS proof, manifest, signed origins, and each CID before local storage. A host-only reader resolves normal UnixFS directories; HAMT sharding is unsupported. No live content origin has passed through the route yet. | Prove the origins have independent operators, support production directory layouts, complete a release fetch with one origin offline, and connect verified blocks to consented installation and sandboxed entry serving. |
| Local app host | The active Node/WASM host has loopback-only consent/install routes and a per-app `.localhost` static bundle origin. It rechecks installed content CIDs, serves assets from the verified bundle, and embeds the app in an opaque-origin iframe. Only capabilities approved in the protected install record are advertised; each cell operation is mediated by a `MessageChannel` and reauthorized by the host. A bridge-level real-peer smoke now passes, but the full launcher installation has not passed a live DNSSEC/candidate gate. | Qualify the full install flow against a signed pilot release and independent Awai nodes; complete updates and rollback; qualify revocation response and isolation against untrusted app code; and confirm every host effect stays within a declared, consented capability. |
| App state | The registry DNA replicates over the experimental peer host. An Awai loopback integration test stages a five-block DNA bundle by CID, installs it through the HTTP API, persists its validator and descriptor, restores the cell, and replays its event log. Two independently Ed25519-signed chained notes are accepted by the real validator; a duplicate is idempotent, a broken signature is rejected, and both accepted notes read back through the bounded cursor API. A second two-process smoke now uses Itonami's adapter against the running Awai API to transfer the fixture bundle, install the cell, submit both signed notes, reject the bad signature, and read the accepted notes back. These are single-host fixture results; full launcher installation, independent-host convergence, migration receipts, and recovery after node loss remain unverified. | Connect the adapter to the full consented launcher installation path and prove signed read/write plus convergence across independent nodes, migration receipts, and recovery after node loss. |
| Public serving | `deploy/awai-public-gateway` contains a Node HTTPS bootstrap gateway for `itonami.app`; it proxies only launcher HTML/JS, registry read, DNSSEC proof, and same-origin install-plan preview to the Itonami loopback host. The Awai worktree now also contains `deploy/awai-peer-registry-gateway`, which exposes only the signed read-only registry route over HTTPS to a loopback Awai API. The peer gateway has five policy tests, but no public host/certificate/DNS is provisioned. | Deploy three independent Awai peer gateways on direct-DNS hosts with independently issued TLS certificates; then implement signed host-to-app resolution, current multi-peer registry state, public app content delivery, and capability-mediated operations before any product hostname uses it. |
| Operations | Candidate registry records are visible in the authenticated app tab. | Add host health, publisher rotation, update consent, operation receipts, backup/restore, and a tested rollback path. |

The current app-plane branch has follow-ups beyond merged PR #431: the app response CSP applies `sandbox allow-scripts` to direct document navigation, `domain/bind` pins an exact release CID, registry reads require a configured peer-checkpoint quorum, remote peer URLs require public-host HTTPS, and `deploy/awai-public-gateway` contains the launcher ingress package and systemd template. The focused Itonami app, registry, and sandbox suites passed 20 tests and 77 assertions; the Node checkpoint verifier passed 2 tests. The Awai `app-registry --host` gate passed all 16 gates, including two-process registry-checkpoint replication and the new peer-gateway route/header policy checks, after allowing loopback binds. The Itonami gateway passed `node --check`, all 9 Node tests, and a loopback TLS smoke with a disposable certificate and fake upstream covering launcher and plan preview, credential stripping, blocked install route, SNI mismatch, foreign Origin, and fixed CSP despite a permissive upstream policy. The separate Awai peer gateway passed syntax checking and 5 policy tests but has no live TLS or public endpoint readback. The JVM server bridge and whole repository suite remain unverified; a prior wider run stopped before tests at the unchanged `Exception` reference in `awai_manifest.cljc`. These are local/source checks only. No external certificate, host, DNS record, or runtime has been provisioned.
These are shared platform gates. A product migration starts only after one
reference app passes them end to end and remains usable with the launcher and
one serving node unavailable. Then each product supplies a small app-specific
adapter/migration package and proves its own state and protocol behavior; it
does not replace the common host.

### Security review follow-ups (2026-10-16)

A read-only review of the app-plane source identified two items to close
before public app execution is qualified:

- The original single-peer snapshot finding now has a local source mitigation:
  v4 responses carry peer-signed freshness checkpoints, and Itonami requires a
  fresh two-thirds quorum of matching CID sets from configured peer IDs.
  Same-host fixtures pass, but independent operator/network identities,
  partition behavior, and stale-peer rejection have not been qualified across
  independently operated nodes. Exact release pins prevent a binding from
  silently following a channel tip; production routing still needs an
  independently verified revocation freshness policy.
- The launcher applies its iframe sandbox only when it creates the iframe.
  The host also serves the CID-verified bundle from a per-app `.localhost`
  origin, so direct navigation could execute the document without that
  iframe-provided sandbox. The response CSP now adds `sandbox allow-scripts`,
  and its policy test passes 14 assertions. The review did not exercise a
  browser, so direct navigation and embedded launch still need browser-level
  confirmation before app isolation is qualified.

These are review candidates, not production incidents. They do not change the
current status: the runtime remains loopback-bound, the public gateway is not
qualified, and no domain has been cut over.

### App-registry validator gate snapshots

An earlier 2026-10-16 pre-release-pin snapshot passed all 15 Awai
`app-registry --host` gates. Its 30 validator cases passed on three semantic
targets (90/90), the fixture host matched 28/28 entries, and two local peer
processes converged over TCP/Noise/Yamux. Its Wasm SHA-256 was
`81932aa9f9b0ff109ffe20b5002728588ab817a85bce4b0e7f5bb8563f636f23`. That is
historical baseline evidence for the earlier source, not the current artifact.

After adding exact release-pin validation, but before the v4 checkpoint/quorum
follow-up, the Awai `app-registry --host` worktree passed all 15 gates. The 32 validator vectors
passed on all three semantic targets (96/96), including fixture replay, local
install-API authorization, and a peer-host check using two processes on the
same machine over loopback. The current Wasm SHA-256 is
`15692fa9f19cc322d8ee8b7294f822234db0794f25bcc9c1654da4310f2f2610`.
Itonami's current focused app, registry, and sandbox suites passed 17 tests and
57 assertions. These changes remain unmerged and unpushed. They qualify local
validators and same-host fixtures only; independent operators, public peer
discovery, production DNA, DNSSEC, public content delivery, and app execution
remain unqualified. A prior wider Itonami run stopped before tests at the
unchanged `Exception` reference in `awai_manifest.cljc`; the full repository
suite remains unverified.
