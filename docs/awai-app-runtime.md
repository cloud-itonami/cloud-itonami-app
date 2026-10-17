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

The first product policy cores are indexed in
[`product-policy-index.edn`](../resources/cloud/itonami/app/product-policy-index.edn),
registered in `kotoba_oracle`, and compiled to shipped KIR artifacts under
`resources/cloud/itonami/app/oracle/`. `product_policy/decision-code` is the
shared typed call seam: it maps the canonical domain to its indexed core,
converts scalar ABI values, constructs declared Kotoba records, and executes
the exported decision. There is no host-side policy fallback. Aozora Biscuit
authorization and x402 transfer preflight now take one typed record each,
which brings their entry boundaries within the compiler's supported arity.
The shared lifecycle's install-admission and Amano current-ref checks now use
the same indexed `product_policy/call-policy` marshalling path. The Nbb server
installs a bounded filesystem resource loader for the packaged index and KIR,
which a runtime without a JVM classpath needs. The index remains source and
benchmark provenance; it is not an app manifest, signed release, app ID, or
domain binding.

The standalone `clj -M:product-kir-gen` build emits those product KIR files
without loading the full Node/server test graph. The earlier NBB generator path
still hits a JVM-only Jackson import in `did.cljk`. AMU's adopted native
selfhost build also accepted the updated Aozora and x402 cores for
`aarch64-macos`. The eight product callsites are not yet routed through
`product_policy/decision-code`; existing product worktrees still contain
separate bootstrap-emitted WASM callsites, and AMU selfhost still cannot emit
their browser/Worker target. These KIR and native build artifacts do not
qualify app packaging, signed releases, or public deployment.

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
operator identities. The same provider-host policy rejects Cloudflare service
aliases in `domain/bind` records and persisted installation origins, so a
signed app binding cannot use `workers.dev`, `pages.dev`, or the other known
Cloudflare endpoint suffixes. The public gateway applies the same known-host
block to its configured TLS host allowlist and domain-proof/install-plan
queries. These hostname checks do not detect a custom-domain CNAME or proxy
path; authoritative DNS/TLS and gateway readback must still prove each custom
hostname reaches the independent serving nodes. The policy does not prove
independent operators. On 2026-10-17, the gateway Node suite passed 18 tests;
the focused Nbb app-binding and content-origin suites passed 20 tests and 78
assertions, including Cloudflare-root and subdomain rejection cases.
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
| Public serving | `deploy/awai-public-gateway` contains a Node HTTPS bootstrap gateway for `itonami.app`; it proxies only launcher HTML/JS, registry read, DNSSEC proof, and same-origin install-plan preview to the Itonami loopback host. The gateway source accepts an explicit static hostname allowlist, checks each request's TLS SNI against `Host`, and requires the certificate SANs to cover every configured hostname. The launcher filters aliases to one exact Awai domain and offers a user-initiated link to that domain on the user's default loopback Itonami host; the local launcher independently reloads its registry and rejects malformed or duplicate domain queries. The 18 Node tests cover alias routing, TLS/Host policy, Cloudflare suffix rejection, plan preview, alias filtering, candidate gating, and local handoff. This host had no listener on `127.0.0.1:1338`, so browser navigation to a running local host has not been verified. A local HTTPS smoke with a disposable multi-SAN certificate for `itonami.app` and `murakumo.cloud` passed alias TLS/SNI, cross-host rejection, and same-origin plan forwarding against a fake upstream. A previous inventory note referred to `deploy/awai-peer-registry-gateway` in an Awai worktree, but that path is absent from this checkout and the currently registered `awai-app-foundation-next` and `awai-app-libp2p-sync` worktrees. The package is therefore not verifiable as present or deployable from the current source. No public peer gateway, hostname alias list, certificate, or DNS readback is provisioned. | Verify browser navigation to the local host, prove that the local candidate's signed binding matches the entered alias, deploy three independently operated Awai peer gateways on direct-DNS hosts, and qualify public certificate/DNS plus a complete consented install before any product hostname is considered migrated. |
| Operations | Candidate registry records are visible in the authenticated app tab. | Add host health, publisher rotation, update consent, operation receipts, backup/restore, and a tested rollback path. |

The current app-plane source has follow-ups beyond merged PR #431: the app response CSP applies `sandbox allow-scripts` to direct document navigation, `domain/bind` pins an exact release CID, registry reads require a configured peer-checkpoint quorum, remote peer URLs require public-host HTTPS, and `deploy/awai-public-gateway` contains the launcher ingress package and systemd template. On 2026-10-17, the gateway Node suites passed 18 tests, including exact-domain local handoff, candidate gating, malformed-query fail-closed behavior, TLS/Host policy, plan preview, and refusal of Cloudflare-managed hosts. A local HTTPS smoke with a disposable multi-SAN certificate for `itonami.app` and `murakumo.cloud` previously passed alias TLS/SNI, cross-host rejection, and same-origin plan forwarding against a fake upstream. The Awai `app-registry --host` gate passed all 15 gates on the current source; the generated WASM artifact SHA-256 is `81932aa9f9b0ff109ffe20b5002728588ab817a85bce4b0e7f5bb8563f636f23`. G14 verified signed app-cell replication between two fixture processes on one host over loopback only. The prior claim of 16 gates and registry-checkpoint replication described a different intermediate source. The separate `deploy/awai-peer-registry-gateway` package is absent from the current checked-out worktrees; no public peer gateway, certificate, host, or DNS readback is provisioned. The Itonami JVM suite is unverified in this turn: `kbb -M:test` could not resolve its JVM classes, and `clojure -M:test` did not resolve the repository's `.cljk` test runner. These are local/source checks only; no public runtime has been provisioned.
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

## Shared Awai app admission core (2026-10-16)

`src/cloud/itonami/app/awai_current_ref_core.kotoba` owns the pure decision
that admits a challenge-bound current-ref proof. It binds the profile ID, chain
ID, genesis hash, validator-set ID, and quorum-policy ID to the requested ref,
head CID, challenge, proof freshness, and the host verifier result. The
signature/finality verifier and registry read remain host capabilities.

This is the shared foundation slice for the planned external-domain apps. It
does not claim that the full Itonami app lifecycle is already Kotoba: DNS,
content retrieval, registry traversal, local install storage, and UI/runtime
dispatch still have host adapters, and each product app still needs a complete
source-closure migration before it can be called Kotoba-native. The production
Awai chain profile is not created; test profile strings are fixtures only. The
quorum-policy ID must later resolve to the operator-authoritative policy and
the verifier must authenticate its quorum evidence.

`src/cloud/itonami/app/awai_amano_adapter.cljk` now binds the pinned Inga
challenge-bound `/current-ref` verifier and committed-head membership verifier
to the install policy's host port. It accepts only a quorum for the requested
ref, head, challenge, and freshness window, then checks the matching head
record's commitment proof against Inga's complete finalized prefix, verified
from the operator-trusted genesis with the three-chain rule. The caller must
provide the response from the node's immutable
`/finalized-prefix?checkpoint=<CID>` endpoint separately from current-ref
replies. Prefix verification is bounded but O(history); a checkpoint source
and refresh path are still needed. The Node host source now loads the operator
profile named by `ITONAMI_AWAI_AMANO_PROFILE`, collects signed reads for the
exact domain-binding and app-release refs, and passes the verified proofs into
the shared resolve/install policy. It repeats the reads after content staging
before persisting an install. Profile files must be regular, non-symlink files
owned by the current user, at most 256 KiB, and not writable by group or other
users. The transport accepts 3–12 public HTTPS witness roots and bounds current
reads to 1 MiB and finalized prefixes to 8 MiB. The checkpoint CID is an
operator-maintained profile value; automatic checkpoint discovery/refresh is
not implemented. The production profile is not created, and this source has
not been loaded against the full pinned dependency closure or deployed.

The Amu adopted native seed completed the official three-generation native
selfbuild on 2026-10-16. All three outputs had SHA-256
`47fba216e1a65b892458216575bbb70be228d86f4b7a6d73ebae84230279f20b`; the
source unity had SHA-256
`496b42f2d9f0c9d66455847f9bb2d5507d8cfa760387365093bf73fd5d514e92`. The
trusted C loader was used, with no JVM, Node, or Graal fallback. This establishes
a selfhost-built compiler image; it does not establish a selfhosted app runtime
or a production deployment.

The native diagnostic below compiled this shared decision core and its driver
with that compiler for `aarch64-macos`. The input used `--source-path --unpinned`,
so this is not a reproducible pinned application closure. The driver alternated
one million valid and quorum-policy-mismatch decisions. Seven paired rounds
were measured; the native figure subtracts the median empty-loader process cost
from the median decision-driver process cost, so per-decision native time is an
approximation. All decision and baseline checksums passed. Host load averages
were 32.034 / 92.834 / 100.851, making this a noisy diagnostic rather than a
qualified performance result.

| Metric | Result |
|---|---:|
| Amu selfhost native core | 236.56 ns/decision (adjusted median) |
| Warmed JavaScript predicate | 106.04 ns/decision (median) |
| Native / JavaScript | 2.23× |
| Native driver process median | 249.405 ms / 1,000,000 decisions |
| Empty-loader process median | 12.841 ms |
| Rounds / warmup | 7 / 1 per executable path |
| Host load average (1/5/15 min) | 32.034 / 92.834 / 100.851 |

This isolated core timing excludes signature/finality verification, registry
access, network and content retrieval, record conversion at the production
host boundary, and the full app lifecycle. It must not be read as a full-app
throughput result or as a production cutover qualification.

### Diagnostic benchmark

`bench/awai_current_ref_benchmark.cljk` compares the prior host-side map
predicate with the new KIR decision core. Both alternate valid and
quorum-policy-mismatch inputs. The KIR measurement includes host-to-record
conversion and KIR interpreter execution. It excludes registry access,
signature verification cost, network/content retrieval, and the full app.

Two small KIR runs on 2026-10-16 used 300 calls per round, three rounds, and
60 warmup calls per implementation. The runner was KBB SCI on Node; the KIR
was generated by the repository's pinned JVM generator. Observed results:

| Run | Host predicate | KIR via SCI | KIR / host | Host load average (1/5/15 min) |
|---|---:|---:|---:|---:|
| 1 | 4.59 µs/op | 5.43 ms/op | 1,182× | 22.59 / 28.93 / 46.75 |
| 2 | 8.73 µs/op | 7.80 ms/op | 893× | 25.26 / 28.86 / 44.88 |

The separate compiled-Wasm harness uses
`bench/awai_current_ref_wasm_driver.kotoba` to call the same admission core
inside a 300-decision loop. Amu's Node bootstrap compiled it for
`wasm32-browser`; the Amu browser host supplied its `kotoba:typed` imports.
That benchmark used seven alternating rounds and two warmups. A benchmark-only
fuel budget of 1,000,000 was needed for the 300-decision call; it is not a
production setting. The host comparison for this harness is a direct JavaScript
predicate, so these figures are not directly comparable to the SCI table.

| Run | JavaScript predicate | Amu Wasm via browser host | Wasm / JS | Host load average (1/5/15 min) |
|---|---:|---:|---:|---:|
| 1 | 0.312 µs/decision | 331.01 µs/decision | 1,060× | 33.08 / 30.48 / 46.19 |
| 2 | 0.319 µs/decision | 306.58 µs/decision | 961× | 33.07 / 30.56 / 46.03 |

The compiled-Wasm harness and output are a separate bootstrap diagnostic, not
production-qualified: the compile used `--unpinned` source-path resolution and
the Amu Node bootstrap, not the selfhost compiler. The artifact SHA-256 is
`e0e6d569ad82e49df731b74e0e08ce9389e0cfb6cf4a6e85c61c2f7acaf1b984`; the
5,413-byte Wasm and its Amu provenance/publication sidecars are in
`/Users/junkawasaki/github/workspaces/codex/itonami-app-plane-bench-20261016/`.
Both benchmark paths use fixture values and omit signature verification,
registry/network/content I/O, and the full app lifecycle. The results show
substantial interpreter and host-ABI overhead; they do not support a native
performance claim or a production cutover.

## Shared app resolve/install admission in Kotoba (2026-10-16)

`src/cloud/itonami/app/awai_app_admission_core.kotoba` now owns the shared
admission precedence for external Awai apps. `awai_apps.cljc` converts the
verified host-port results into bounded facts and calls this core for both
`resolve-domain` and `finish-install`. DNSSEC validation, Amano signatures and
quorum verification, registry reads, content CID checks, protected storage,
capability consent, and the resulting error/detail maps remain host duties.
All product apps can use this common rule. Product-specific Kotoba slices
cover Murakumo compute-bid admission, Isekai publish-tier, Aozora Biscuit
operation, Babiniku proposal-governor, x402 transfer limits, Shinshi video
black-frame threshold, Oppai challenge-window, and Awai Network chat
admission. Seven existing product source functions now call their decision
cores. These changes are still outside the shared host lifecycle and signed
app releases; the full product source closures are not yet migrated. The x402
slice does not authorize signing.

The KIR artifact was generated through the repository's compatibility
generator. The source was also compiled by the adopted Amu native selfhost
seed, whose compiler pin was verified. The selfhost build used a trusted C
loader, and compiler execution had no JVM, Node, or Graal fallback. The native
source closure was passed with `--source-path --unpinned`; it is not a pinned
app build or a production runtime.

The KIR diagnostic checked 11 finish-install cases and five resolve-domain
cases before timing. It then compared 300 alternating admitted and
capability-refused finish-install decisions over seven alternating rounds,
with two warmups per implementation. The direct host predicate omits record
construction; the KIR path includes record conversion and KIR interpretation.

| Metric | Result |
|---|---:|
| Host predicate median | 2.844 µs/decision |
| Kotoba KIR median | 2.163 ms/decision |
| KIR / host | 760.5× |
| Host load average (1/5/15 min) | 104.20 / 111.41 / 120.13 |

The separate native diagnostic ran one million alternating admitted and
capability-refused decisions per sample over seven alternating rounds, with
one warmup per executable path. It subtracts the median empty-loader process
time from the native driver's median wall time. The JavaScript reference runs
in-process, so the comparison and loader subtraction are approximate.

| Metric | Result |
|---|---:|
| Amu selfhost native core, adjusted median | 410.0 ns/decision |
| Native driver process median | 448.743 ms / 1,000,000 decisions |
| Empty-loader process median | 38.744 ms |
| JavaScript reference median | 12.174 ns/decision |
| Native / JavaScript | 33.68× |
| Host load average; logical CPUs | 120.80 / 111.92 / 119.09; 10 |

Both benchmark runs were heavily loaded and are diagnostic only. They show
that the current KIR interpreter path is costly and that this native microbench
did not beat its JavaScript predicate. Neither is a full-app performance
result. Both use fixtures and exclude signature verification, registry and
network I/O, content transfer, UI execution, and the complete product app.
The selfhost compiler, build commands, native images, and command logs are
preserved under
`/Users/junkawasaki/github/workspaces/codex/itonami-app-admission-selfhostbench-20261016-unsandboxed/`.

## External product endpoint migration catalog (2026-10-17)

[awai-app-public-endpoint-migration-2026-10-17.csv](awai-app-public-endpoint-migration-2026-10-17.csv)
tracks the corrected 523-row Cloudflare runtime snapshot, all 2,581 Pages
production deployment candidates, all 361 DNS control-plane records, one source/provider
mismatch, two root-only readbacks, and eight active zones with no configured
DNS records. DNS metadata records are separate from HTTP endpoints; record
contents and targets are omitted. The eight zone-only rows remain pending
external delegation and retention review. Of the 3,476 catalog rows, 3,104
are provider endpoint or deployment candidates; API presence alone does not
establish public reachability.
The sanitized DNS source snapshot is
[`awai-cloudflare-dns-hostnames-2026-10-17.csv`](awai-cloudflare-dns-hostnames-2026-10-17.csv).
The endpoint source is
[`awai-cloudflare-runtime-endpoints-2026-10-17.csv`](awai-cloudflare-runtime-endpoints-2026-10-17.csv),
and the full 145-script Workers.dev settings read is
[`awai-cloudflare-workers-dev-recheck-2026-10-17.csv`](awai-cloudflare-workers-dev-recheck-2026-10-17.csv).

The service map resolves 84 of 174 service names from canonical Wrangler
configs or pinned canonical GitHub config snapshots. Another 13 have only a
product-owner hint, 5 are repository-name candidates, 6 have ambiguous config
matches, and 66 need owner review. The
33-domain summary separates ten product-cohort domains, 13 other named
domains, two platform hostname families, and eight zone-only review domains.
Service-owner statuses do not assign an app ID or release. The files are
generated by scripts/generate_awai_app_migration_catalog.py from the checked-in
runtime snapshot, Pages deployment list, root readback, and zone recheck, and
by scripts/generate_awai_service_ownership_map.py and
scripts/generate_awai_app_domain_ownership_summary.py. Cloudflare configuration
does not establish public reachability.

The [`awai-app-service-ownership-2026-10-17.csv`](awai-app-service-ownership-2026-10-17.csv)
contains the service-level source evidence. The
[`awai-app-domain-ownership-summary-2026-10-17.csv`](awai-app-domain-ownership-summary-2026-10-17.csv)
groups that evidence by root domain. The remaining 13 non-cohort domains need
an owner decision and product/service contract before they can receive an
Awai app identity or domain binding; platform hostnames need an explicit
keep-as-alias or retire decision after their owning apps are identified.

| Product domain | Source repository | Current Kotoba / Amu state |
|---|---|---|
| `itonami.app`, `itonami.cloud` | `network-awai/cloud-itonami` | Shared resolve/install admission core is wired in the local app-plane worktree; it is not merged or in a signed release. Product application closures and the production Awai chain profile remain incomplete. |
| `murakumo.cloud` | `network-awai/cloud-murakumo` | Local pilot branch based on `origin/main` at `52fd611ee16a3794692f0548353630d0c8a1014f` routes `eligible-bid?` through the exact-source Kotoba core on the CLJS path; the pinned AMU bootstrap WASM artifact builds with zero imports. The 2026-10-16 AMU native selfhost benchmark matches the source; no comparable repeat was taken on 2026-10-17 under heavy host load. Worker bundle build, signed release, and domain binding remain unverified. App ID `net.kotoba.murakumo` is evidenced. |
| `aozora.app` | `network-awai/app-aozora-engine` | Local uncommitted PDS `authorize-op` change calls an AMU `wasm32-browser` module after host Biscuit verification; Worker build, app bundle, release, and domain binding are unverified. |
| `oppai.fans` | `network-awai/fans-oppai` | Local uncommitted `challenge-valid?` change calls an AMU `wasm32-browser` core for bounded timestamps and keeps the host fallback outside that range; app build and release are unverified. Nonce consumption, consent, and face-data handling stay host-owned. |
| `isekai.network` | `network-awai/network-isekai` | Local uncommitted `account-tier.mjs` maps host tier data to an AMU `wasm32-browser` admission core; Pages Function build, signed app bundle, and release binding are unverified. |
| `x402.nexus` | `network-awai/nexus-x402` | Local uncommitted `transfer-verdict` change calls an AMU `wasm32-browser` core for amount limits after host validation; app build and release are unverified. It does not authorize signing; owner-scoped storage and atomic budget/nonce reservation remain host capabilities. |
| `shinshi.club` | `network-awai/club-shinshi-app` | Local `video_black_frame_core.kotoba` is wired into the portable CLJS `black-frame?` path through a bootstrap WASM module. The offline `video-drain` SCI path still uses its host fallback, and this is not a public service callsite. Publication, app bundle, and release behavior are unverified. |
| `babiniku.net` | `network-awai/net-babiniku` | Local uncommitted `review-turn` change calls an AMU `wasm32-browser` core after host checks; app bundle build and release are unverified. Generation asset storage and preview-only broadcast persistence still need a host-port migration. |
| `awai.network` | `network-awai/network-awai-apex` | Local uncommitted `sanitize-chat` change calls an AMU `wasm32-browser` admission core after host message counting; Worker build and signed release are unverified. Image/video generation, streaming, and Murakumo capabilities still need app-host adapters. |


### Product WASM and shared KIR ABI boundary

The product-specific browser wrappers and the shared Itonami policy index are
separate artifacts today. The local Aozora, Shinshi, and x402 product
worktrees still use their measured bootstrap-WASM source variants; the shared
policy sources have since moved to record inputs for Aozora/x402 and an `f64`
threshold input for Shinshi. Their decision-kernel semantics are related, but
the source hashes and ABIs are not identical, so the shared-core benchmark rows
do not claim to time the current product wrapper binaries. An Aozora probe of
the shared record-input source compiled to a WASM module with 46
`kotoba:typed` imports, while the product compatibility wrapper instantiates a
zero-import module. It cannot be swapped in as a drop-in artifact. The intended
convergence point is the shared Itonami Kotoba/KIR call seam, or a separately
qualified typed-WASM host, before removing the product compatibility paths.

### Product policy KIR packaging and local readback (2026-10-17)

The KIR generator packages product decision cores and shared Awai install
admission as entryless libraries. Benchmark-only `main` exports stay in the
AMU native benchmark source closure and are omitted from the runtime export
surface. The source files and their AMU benchmark hashes are unchanged.

The shared policy table now has cases for all published decision exports in
the 34 shipped cores. `kbb --backend sci --classpath src:test
test/oracle_cases_nbb.cljk` passed 290 cases with no uncovered exports. The
focused `kbb --backend sci --classpath src:test
test/awai_app_plane_nbb.cljk` runner installs the packaged-resource loader and
passed 20 resolver/content tests with 78 assertions. The local public gateway
and launcher suites passed 18 Node tests. These checks cover local policy
artifacts and route decisions; they do not establish signed releases, Awai
production-chain finality, public DNS/TLS, migrated service state, or live
domain behavior.


These product source changes are local working-tree WIP in separate repositories; they have not changed canonical `main`, signed app releases, live routes, or DNS. The native AMU selfhost benchmarks qualify native decision-core builds only; browser modules use the separate bootstrap compiler path described below.

On 2026-10-17, all eight product Kotoba decision-core files were present in their product worktrees; each SHA-256 matches its source-matched AMU native selfhost benchmark input from 2026-10-16. Their eight benchmark drivers plus the shared install-admission core are recorded in [`awai-product-kotoba-selfhost-native-recheck-2026-10-16.md`](benchmarks/awai-product-kotoba-selfhost-native-recheck-2026-10-16.md). Seven product callsites use AMU bootstrap-emitted WASM wrappers with per-project build scripts; Shinshi's core remains an offline workflow helper, not a public service callsite. The measurement covers native decision kernels only. It does not qualify the WASM outputs, integrated product builds, or full app behavior.

`app_key` in the CSV is a product-family label, not a published Awai app ID.
`product_source_repository_hint` is a product-level lead, not proof that every
Worker/route in that domain is built from that repository. `mapping_basis`
states whether the row followed an exact hostname, an explicit Pages/service
mapping, or remains unmapped.
Only `net.kotoba.murakumo` currently has an evidenced app ID in this work.
No app IDs, release CIDs, domain bindings, validator profile, DNSSEC DS
records, or production deployments were created by this catalog pass.

## Selfhost decision-core benchmarks

An initial same-day measurement covered the shared app install gate,
current-ref acceptance core, Murakumo bid gate, Isekai publish-tier gate,
Aozora Biscuit operation gate, Babiniku proposal governor, x402 wallet
preflight, Shinshi's black-frame gate, Oppai's challenge window, and Awai
Network chat admission. A source-matched recheck of the shared install gate
and all eight product cores appears below and supersedes the earlier product
numbers for the current performance comparison.
The initial product-core results and target qualification are summarized in
[`awai-product-kotoba-selfhost-native-2026-10-16.md`](benchmarks/awai-product-kotoba-selfhost-native-2026-10-16.md).
The initial product measurements used source-matched native builds. Each
product core used one million decisions, seven alternating rounds, one warmup
per executable path, and an empty-loader-adjusted native median. Current-ref
reused its existing native artifact. No production deployment was part of
these measurements. The host was heavily loaded; the initial raw medians and
load averages are in the linked refresh output.

| Decision core | Amu selfhost median | JavaScript median | Native / JS | Integration state |
|---|---:|---:|---:|---|
| App install admission | 51.49 ns/decision | 8.62 ns/decision | 5.97× | Called by shared resolve/install host lifecycle; fresh diagnostic load average 14.23 |
| Awai current-ref acceptance | 1,402.46 ns/decision | 816.06 ns/decision | 1.72× | Historical initial core measurement, not part of the later source-matched nine-kernel recheck; production chain profile and quorum authority absent |
| Murakumo bid admission | 16.84 ns/decision | 10.05 ns/decision | 1.675× | Native diagnostic; production quote selector calls the separately measured WASM adapter |
| Isekai publish-tier admission | 7.08 ns/decision | 2.86 ns/decision | 2.471× | `account-tier.mjs` calls the WASM source module; Pages build unverified |
| Aozora Biscuit operation authorization | 48.24 ns/decision | 45.79 ns/decision | 1.053× | Native diagnostic; PDS source calls a separately measured WASM closure |
| Babiniku proposal governor | 33.97 ns/decision | 19.62 ns/decision | 1.732× | Native diagnostic; `review-turn` calls the separately measured WASM adapter |
| x402 wallet transfer preflight | 34.09 ns/decision | 30.82 ns/decision | 1.106× | Native diagnostic; `transfer-verdict` calls a WASM amount gate; no signature authority |
| Shinshi black-frame threshold | 21.25 ns/decision | 15.04 ns/decision | 1.413× | Shared CLJS helper calls the bootstrap Kotoba WASM module; offline `video-drain` SCI path uses its host fallback; no public service callsite |
| Oppai enrollment challenge window | 16.46 ns/decision | 21.69 ns/decision | 0.759× | Native diagnostic; `challenge-valid?` calls the WASM adapter for bounded timestamps |
| Awai Network chat admission | 20.27 ns/decision | 16.19 ns/decision | 1.252× | `sanitize-chat` source calls the WASM module; Worker build unverified |

| Decision core | Host load average (1/5/15 min) | Driver median | Empty-loader median |
|---|---|---:|---:|
| App install admission | 14.23 / 11.53 / 16.41 | 69.78 ms / 1M | 18.29 ms |
| Awai current-ref acceptance | 51.31 / 34.98 / 48.24 | 1,436.56 ms / 1M | 34.10 ms |
| Murakumo bid admission | 46.72 / 57.52 / 49.05 | 47.92 ms / 1M | 31.08 ms |
| Isekai publish-tier admission | 44.18 / 56.82 / 48.85 | 41.41 ms / 1M | 34.34 ms |
| Aozora Biscuit operation authorization | 44.18 / 56.82 / 48.85 | 79.32 ms / 1M | 31.08 ms |
| Babiniku proposal governor | 44.18 / 56.82 / 48.85 | 65.99 ms / 1M | 32.01 ms |
| x402 wallet transfer preflight | 44.18 / 56.82 / 48.85 | 62.56 ms / 1M | 28.47 ms |
| Shinshi black-frame threshold | 42.00 / 56.16 / 48.67 | 52.50 ms / 1M | 31.25 ms |
| Oppai enrollment challenge window | 42.00 / 56.16 / 48.67 | 56.26 ms / 1M | 39.80 ms |
| Awai Network chat admission | 42.00 / 56.16 / 48.67 | 52.51 ms / 1M | 32.24 ms |

Each driver alternated one million admitted/refused decisions over seven
rounds with one warmup. Native time is an approximate process-wall-time median
minus an empty KEXE-loader median; JavaScript runs in process. The initial
product batch had high load across all eight samples; the shared app-admission
refresh also ran under high load (14.23 / 11.53 / 16.41 on the 10-core host).
In the first product run, only Oppai's native core was faster than its direct
JavaScript reference. These measurements do not support a general native
performance claim. The microbenchmarks exclude
full request conversion, signatures, registry and peer reads, provider
selection/sorting, content I/O, and the full app lifecycle. The numbers are not
directly comparable across rows. The benchmark sources are in `bench/`; the
first aggregate stdout is in
[`awai-product-kotoba-selfhost-native-refresh-2026-10-16.txt`](benchmarks/awai-product-kotoba-selfhost-native-refresh-2026-10-16.txt).

The x402 initial native row was refreshed after adding the separate four-argument
`transfer-amount-limit-code` export; its full `transfer-verdict-code` decision
body remains unchanged. The benchmark used the current source closure and the
adopted AMU native selfhost compiler with verified pins. Its source-path
closure was `--unpinned`, so the result remains diagnostic rather than a pinned
application build.

### Source-matched selfhost recheck

On 2026-10-16, the eight product decision cores and shared install-admission
core were rerun from native images whose recorded Kotoba source closures match
the current feature worktrees. Each harness executed one million decisions per
sample over seven alternating rounds, with one warmup per executable path. The
native median subtracts the empty KEXE-loader median; JavaScript ran warmed in
process. The host had 10 logical CPUs. Load was lower than in the first run but
remained elevated at 6.24–6.43 / 8.38–8.46 / 12.36–12.41 (1/5/15 minute
averages).

| Decision core | AMU selfhost native | JavaScript reference | Native / JS |
|---|---:|---:|---:|
| Murakumo bid admission | 4.55 ns/decision | 3.55 ns/decision | 1.283× |
| Aozora Biscuit authorization | 15.67 ns/decision | 14.35 ns/decision | 1.092× |
| Isekai publish-tier admission | 2.65 ns/decision | 1.11 ns/decision | 2.393× |
| Babiniku proposal governor | 9.83 ns/decision | 5.87 ns/decision | 1.674× |
| x402 transfer preflight | 10.90 ns/decision | 10.50 ns/decision | 1.038× |
| Shinshi black-frame threshold | 6.19 ns/decision | 4.75 ns/decision | 1.303× |
| Oppai challenge window | 7.23 ns/decision | 6.51 ns/decision | 1.111× |
| Awai Network chat admission | 7.75 ns/decision | 5.01 ns/decision | 1.545× |
| Shared Itonami install admission | 28.13 ns/decision | 4.61 ns/decision | 6.102× |

The native path was slower in all nine rows in this lower-load recheck. A
second run completed all nine harnesses under very high host load and produced
materially different ratios; treat that run as a harness-completion check, not
a performance comparison. Both runs measure small decision kernels, not
application latency or deployment performance. See the benchmark report's
high-load repeat section for its results.
The build reports verified the adopted AMU seed pins and exact source hashes;
their source-path closures remain `--unpinned`. Full raw outputs and source
hashes are in
[`awai-product-kotoba-selfhost-native-recheck-2026-10-16.txt`](benchmarks/awai-product-kotoba-selfhost-native-recheck-2026-10-16.txt),
with the machine-readable run record at
`/Users/junkawasaki/github/workspaces/codex/itonami-app-plane-next-kotoba-selfhost-refresh-20261016/results.json`.

## AMU JavaScript artifact comparison

| Core | AMU JS artifact | Direct JS | Artifact / reference | Status |
|---|---:|---:|---:|---|
| Murakumo bid admission | 494.93 ns/decision | 11.86 ns/decision | 41.72× | Comparison only; current source calls the WASM target |
| Aozora Biscuit authorization | 1,413.28 ns/decision | 32.87 ns/decision | 42.99× | Comparison only; current source calls the WASM target |

These JS-backend measurements used one million mixed decisions, seven rounds,
and production-style guest rotation. They are retained as a compiler-path
comparison; the source callsites now use the portable WASM output below.

## AMU portable WASM decision cores

The eight product Kotoba cores were emitted as `wasm32-browser-kotoba-v1`
modules with `--jvm-free`, fuel 10,000,000, and no imports. With x402's
additional source-specific amount-gate export, the current modules total
4,605 bytes. The benchmark converts host Number inputs to WASM i64 BigInts and
rotates a guest every 10,000 calls. Most cores use one million alternating
allow/refuse decisions over seven rounds with one warmup; the updated x402
full-core run uses ten ordered outcomes. The initial shared run's host load was
28.83 / 31.79 / 32.09; the x402 remeasurement load was 32.41 / 30.85 / 33.84,
both on 10 logical CPUs.

| Product core | WASM bytes | WASM ns/decision | JS ns/decision | WASM / JS | Source integration |
|---|---:|---:|---:|---:|---|
| Murakumo bid admission | 531 | 173.42 | 36.24 | 4.79× | `eligible-bid?` calls wrapper; Worker build unverified |
| Aozora Biscuit core | 970 | 247.13 | 45.93 | 5.38× | Standalone core; PDS closure measured separately |
| Babiniku proposal governor | 549 | 184.92 | 37.73 | 4.90× | `review-turn` calls wrapper; app build unverified |
| x402 transfer preflight | 812 | 782.69 | 94.15 | 8.31× | `transfer-verdict` calls the four-argument amount gate; app build unverified |
| Shinshi black-frame threshold | 401 | 145.74 | 33.75 | 4.32× | Shared CLJS helper calls wrapper; offline SCI path uses host fallback; no public service callsite |
| Oppai challenge window | 503 | 153.38 | 34.38 | 4.46× | `challenge-valid?` calls wrapper for bounded timestamps; app build unverified |
| Isekai publish-tier admission | 393 | 104.19 | 26.91 | 3.87× | `account-tier.mjs` calls wrapper; Pages build unverified |
| Awai Network chat admission | 446 | 152.81 | 33.29 | 4.59× | `sanitize-chat` source calls wrapper; Worker build unverified |
| Aozora PDS closure | 1,036 | 343.45 | 28.00 | 12.26× | PDS source calls wrapper; Worker build unverified |

For these core-only kernels, WASM is slower than direct JavaScript, with
absolute times of 104–783 ns per decision. The x402 full-preflight run used ten
ordered outcomes; the other rows used their listed mixed vectors. These are
normalized-input microbenchmarks, not full request measurements. They exclude signatures,
parsing, storage, network, app resolution, and chain consensus. The portable
WASM target uses the JVM-free AMU backend and is separate from the native
`aarch64-macos` selfhost diagnostics above. A default-fuel probe trapped after
512 calls; the measured modules fix finite fuel at 10,000,000 and rotate guests
every 10,000 decisions. The initial core outputs and harness are in
`/Users/junkawasaki/github/workspaces/codex/awai-wasm-probe-20261016/`; the
current x402 full-core and amount-gate outputs are in
`network-awai/nexus-x402/bench/x402_transfer_preflight_wasm_result_2026-10-16.txt`.
The summary is in `docs/benchmarks/awai-product-kotoba-wasm-2026-10-16.md`.

### Source-adapter measurements

After wiring the generated wrappers into Babiniku, x402, and Oppai, separate
benchmarks measured their source-boundary adapters. They include host-side
normalization relevant to each gate, Number-to-i64 BigInt conversion, guest
rotation every 10,000 calls, and result conversion. These remain
microbenchmarks of normalized facts; they exclude each full service request.

| Source adapter | WASM ns/decision | Direct JavaScript ns/decision | WASM / JS | Measurement scope |
|---|---:|---:|---:|---|
| Babiniku `review-turn` decision | 168.34 | 10.36 | 16.24× | Eight mixed cases; host blocked-term search excluded |
| x402 transfer amount gate | 229.19 | 12.10 | 18.94× | Four amount cases after host authorization and request validation |
| Oppai challenge window | 109.92 | 22.73 | 4.84× | Eight boundary cases; host fallback retained outside WASM timestamp range |

These adapter results are slower than direct JavaScript because of the i64
BigInt call boundary. Absolute cost is 0.11–0.23 microseconds per call. Raw
results and harnesses are in each feature worktree's `bench/` directory.

## Kotoba static-module build check

On 2026-10-16, AMU emitted `js-kotoba-v1` modules for the eight product policy
slices plus the two shared app lifecycle cores. The build used
`bin/amu compile --target js --jvm-free --unpinned` from AMU checkout
`c3b1ef6a01ca5f0dd2357c89b92f6aef65b0479e`; this is the NBB-hosted JavaScript
backend, not the adopted native selfhost compiler measured above. The eight
single-export product modules loaded in Node and returned their expected
admit/refuse vectors. The shared resolve/install and current-ref modules
compiled successfully but were not exercised through the product vector
smoke. Compiler manifest/provenance files and emitted modules are preserved in
`/Users/junkawasaki/github/workspaces/codex/awai-kotoba-js-bundle-20261016/`;
the ten generated modules total 720,549 bytes, including each module's JS
guest runtime.

## AMU native selfhost target boundary

On 2026-10-16, the adopted AMU native selfhost compiler was invoked on the
shared app-admission Kotoba source with `--target wasm32-browser`. It returned
`E2 unsupported target (only aarch64-macos)`. The selfhost compiler used for
the native decision-core benchmarks therefore cannot emit the portable browser
modules used by the product callsites. Those wrappers come from the separate
JVM-free AMU/NBB bootstrap compilation path recorded above. The native
selfhost measurements are valid source-level diagnostics for the native
target; they do not establish a selfhost-built browser artifact or a deployed
selfhost app runtime.

## Pinned app-plane server build and local smoke (2026-10-16)

The local server guest was emitted with
`AMU=/Users/junkawasaki/github/wt/codex/awai-foundation-amu-8435/bin/amu`
and `KOTOBA_EMITTER=amu` using AMU commit
`8435eafb7dada2d3a85cee2c278ca6d38deb7588` and Kotoba language pin
`bfe618b3a3844d5563a943d63bdefcdc535af98c`. The resulting
`target/amu/server_main.wasm` is 3,432 bytes with SHA-256
`37cec21624af2ed4da24d9225b62efddc5c5c16faa5ff6b02fcb5f95ce7c5efa`;
the paired `browser-host.mjs` is 151 KiB with SHA-256
`a36fc69c83d248f24719b0365c22bb57533c680b4ab8e6073d85adc33081009a`. This
is the AMU bootstrap `wasm32` build path, not a browser module emitted by the
adopted native selfhost compiler.

After adding the matching pinned `osaho` runtime dependency to `nbb.edn` and
fixing the current-ref response-size constant reference, the Node host ran on
`127.0.0.1:1338` with an isolated scratch data directory. Direct HTTP readback
returned 200 for `/health`, `/`, and `/awai-launcher.js`. The local
`/api/awai/app-registry` returned 503 `{"status":"unavailable","reason":"unconfigured"}`;
the production Awai chain profile remains uncreated as confirmed by the user.
The host was stopped after the smoke check. No external DNS or deployment changed, and
the browser handoff itself has not been exercised in a browser.

The native selfhost performance evidence remains the diagnostic decision-core
benchmark linked above. It measures the app-install gate and product policy
cores, not HTTP latency, full app lifecycle, chain consensus, or deployed
performance.

This establishes portable static-module build paths the Itonami bundle reader
can serve (`.mjs` and `.wasm` are supported content types). The source callsites
listed above now import local WASM wrappers, but their Worker/Pages builds remain
unverified. The modules are not part of signed app bundles; product source
closures, release records, domain bindings, and the production chain profile
remain outstanding.

The Isekai source slice and its benchmark/build record are in the separate
`network-awai/network-isekai` worktree; `account-tier.mjs` calls the module,
but no Pages build or Awai app release is verified. Aozora's PDS source imports
its generated WASM wrapper, but the Worker build is unverified. Murakumo's
quote selector calls its generated wrapper, but the Worker build is
unverified. Babiniku's `review-turn` and x402's `transfer-verdict` source
functions call their respective Kotoba cores; their app builds and Awai
releases are unverified. Oppai's `challenge-valid?` calls its Kotoba core for
bounded timestamps while retaining the original fallback; its app build and
Awai release are unverified. Awai Network's `sanitize-chat` source calls its
generated wrapper, but its Worker build and app release are unverified.
Shinshi's offline `tools/video-drain.cljk` publication workflow calls
`video_publish/black-frame?`, now backed by the portable Kotoba module for
finite, safely representable CLJS values. The original predicate remains the
fallback for unusual values and the CLJ compatibility branch.

The app-plane source contains a shared Kotoba admission slice compiled to KIR
by the compatibility generator and called by the host lifecycle. AMU native
selfhost compilation is evidenced by the separate decision-core benchmarks;
the adopted selfhost compiler currently accepts only `aarch64-macos`. The
eight product decision functions, including Shinshi's video-drain threshold,
are now source-wired through portable wrappers emitted by the JVM-free AMU
bootstrap path. Their Worker/Pages builds and Awai releases remain unverified.
The broader product source closures are not yet refactored to Kotoba/AMU. The
171 other named-domain rows and 105 platform-hostname rows still need service
ownership review; the catalog does not treat them as unrelated or migrated.
