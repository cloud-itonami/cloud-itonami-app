# Murakumo Cloud first migration cohort

Status date: 2026-10-17 JST. The user-selected pilot is `murakumo.cloud`.
This runbook uses the sanitized Cloudflare endpoint catalog and the current
read-only account recheck; it does not authorize a DNS, registrar, runtime, or
production chain change.

## Inventory closure

The current catalog maps 58 records to this root: 30 runtime endpoints and 28
DNS records. Those runtime endpoints comprise 14 Worker custom domains, 10
Worker routes, 4 Tunnel ingress rules, one stable `workers.dev` hostname, and
one enabled version-preview hostname. They cover 18 named services. All 58
rows remain migration-pending.

The current source map resolves fourteen services to a repository
configuration; four remain product-owner hints without a canonical service
configuration. A configuration match identifies the intended source owner; it
does not prove that the currently deployed bundle was built from that source.

| Service | Public surface | Current source evidence |
|---|---|---|
| `murakumo-cloud` | `murakumo.cloud`, `www.murakumo.cloud`, `workers.dev` | Resolved: `network-awai/cloud-murakumo` |
| `local-murakumo` | `api.murakumo.cloud`, `workers.dev` | Resolved: `network-awai/cloud-murakumo-api` |
| `murakumo-operator` | `console.murakumo.cloud`, `operator.murakumo.cloud` | Resolved config: `network-awai/cloud-murakumo/wrangler.operator.jsonc`; config declares `operator`, while provider inventory also maps `console` to the same Worker |
| `cloud-itonami-isic-6493-factoring` | `factoring.murakumo.cloud`, `workers.dev` | Resolved: `cloud-itonami/cloud-itonami-isic-6493` |
| `murakumo-generation-proxy` | `generation.murakumo.cloud`, `workers.dev` | Resolved: `network-awai/cloud-murakumo/wrangler.generation.jsonc` |
| `murakumo-ipfs-gateway-pilot` | `ipfs-pilot.murakumo.cloud`, `workers.dev` | Resolved: `network-awai/cloud-murakumo-ipfs-gateway` |
| `murakumo-client-static` | `local.murakumo.cloud`, `workers.dev` | Unresolved: product-owner hint only; no canonical source/config match is recorded |
| `murakumo-meter` | `meter.murakumo.cloud`, `workers.dev` | Unresolved: product-owner hint only; no canonical source/config match is recorded |
| `murakumo-setup` | `setup.murakumo.cloud`, stable and preview `workers.dev` | Resolved config: `network-awai/cloud-murakumo/wrangler.setup.jsonc` |
| `murakumo-status` | `status.murakumo.cloud`, `workers.dev` | Resolved: `network-awai/cloud-murakumo/wrangler.status.jsonc` |
| `murakumo-toshokan` | `toshokan.murakumo.cloud`, `workers.dev` | Resolved: `kotoba-lang/toshokan` |
| `murakumo-web` | `web.murakumo.cloud`, `workers.dev` | Resolved config: `network-awai/cloud-murakumo/wrangler.web.jsonc` |
| `comfyui-gad` | `gateway.murakumo.cloud`, `infer.murakumo.cloud` via Tunnel | Caller integration is resolved in `network-awai/cloud-murakumo-api`; Tunnel config routes `gateway` to `localhost:8189` and returns 404 for `infer`. Physical GAD/ComfyUI owner remains unresolved |
| `kekkai-control-plane` | `kekkai-control.murakumo.cloud` heartbeat and netmap routes via Tunnel | Tunnel config only: heartbeat/netmap target local ports 17892/17893. Source owner remains unresolved |
| `murakumo-ads-mcp` | `/advertise`, `/advertise/review*`, `/mcp`, `/mcp/` | Resolved: `network-awai/cloud-murakumo/wrangler.ads-mcp.jsonc` |
| `murakumo-node-registration` | `/api/devices/link/*` | Resolved route config: `network-awai/cloud-murakumo/wrangler.node-registration.jsonc`; deployed handler source remains unmatched |
| `kotoba-foundation-commons` | `/commons/*`, `/legal-node-participation*` | Resolved: `network-awai/cloud-murakumo/wrangler.commons.jsonc` |
| `murakumo-llms` | `/.well-known/llms.txt`, `/llm.txt`, `/llms.txt` | Resolved: `network-awai/cloud-murakumo/wrangler.llms.jsonc` |

The `workers.dev` hostname and preview pattern are independent public aliases.
The migration decision for each must be recorded as rehost, redirect, or
retire; a root-domain cutover alone does not remove them.

Four canonical Worker config matches were confirmed from GitHub `main` at
`52fd611ee16a3794692f0548353630d0c8a1014f`: `wrangler.setup.jsonc`
(`be93b7b200d1ee5d1664b051709059af0feaeb49`), `wrangler.web.jsonc`
(`05fdbca0c345be10696696173ffce5428f752f46`), and
`wrangler.node-registration.jsonc`
(`3b648c9927b84167a3371f223691a82962780273`), plus `wrangler.operator.jsonc`
(`c61bc05d596a55b1e484f7ebadd33161b304cbe2`). These are included among the
fourteen resolved services above; they are distinct from the four unresolved
product-owner hints (`murakumo-client-static`, `murakumo-meter`, `comfyui-gad`,
and `kekkai-control-plane`). The configs name the matching Workers and
domains/routes. The operator source describes a read-only supply network view;
its configured `operator` hostname does not explain the provider's additional
`console` alias. These configs do not qualify the Workers as Awai app releases
or establish deployed-source parity.
The commit and blob identities are retained in
[`awai-app-canonical-source-config-evidence-2026-10-17.csv`](../awai-app-canonical-source-config-evidence-2026-10-17.csv)
and are consumed by the service-owner map generator, so a stale local checkout
does not downgrade this verified source evidence.

### GAD caller and Tunnel wiring (2026-10-17 JST)

The current GitHub `main` source for
[`cloud-murakumo-api/src/gad_private_fetch.js`](https://github.com/network-awai/cloud-murakumo-api/blob/main/src/gad_private_fetch.js)
is blob `a7757f0471dd79c591e4b6997792f33a01fcdaa2`. The helper selects the
`GAD_LLAMA`, `GAD_NET`, or `MURAKUMO_GAD_PRIVATE` VPC binding and rewrites
requests to `http://127.0.0.1:8090`. Its current
[`wrangler.toml`](https://github.com/network-awai/cloud-murakumo-api/blob/main/wrangler.toml)
is blob `69e6bc46bbf93d0923ce5287818722d32834109b`; its GAD comments identify
the local `gad llama-server` and `comfyui-gad` Tunnel
`c75a3e83-6cef-4d9b-ba22-fbb459ee6fbe`. The `GAD_LLAMA` and
`MURAKUMO_GAD_PRIVATE` service bindings share service ID
`01a042a5-29f6-7ce2-9b7a-c64254bea3ef`; `GAD_NET` names the same Tunnel used by
the `gateway.murakumo.cloud` ingress snapshot.

This resolves the repository-side GAD caller and its Cloudflare routing
relationship. It does not identify the physical host operator, prove the
health or reproducible configuration of the `127.0.0.1:8090` process, or show
that a deployed bundle matches the fetched source. Preserve the existing
inference contract behind a Kotoba policy core and an independently operated
Awai node adapter; migrate request execution and signed bid/receipt evidence
before retiring the VPC service bindings and Tunnel. Keep
`infer.murakumo.cloud` separately classified as a configured 404 candidate,
not as a functioning public inference API. The source and ingress evidence
still leave the physical GAD/ComfyUI owner unresolved.

### Current unmatched-Worker state readback (2026-10-17 JST)

A separate read-only API check of `murakumo-client-static` and `murakumo-meter`
at `2026-10-16T19:33:05Z` confirms both are active 100%-traffic Workers, with
stable `workers.dev` and preview URLs disabled. The client-static Worker has
no provider bindings in its current settings, but its embedded asset/source
closure is not identified. The meter Worker has one D1 binding, two
`secret_text` bindings, and one `plain_text` binding; the secret values and D1
contents were not read or retained. The latest recorded deployments are
2026-10-16 and 2026-07-17 respectively. Neither provider response exposes a
canonical source commit, so the two repository ownership hints remain
unresolved. Before migration, recover the exact source/build for client-static;
for meter, define a data export/import reconciliation and rotate signing
material into the new operator custody without copying Cloudflare secrets.
Sanitized provider evidence is in
[`murakumo-worker-source-config-recheck-2026-10-17.csv`](murakumo-worker-source-config-recheck-2026-10-17.csv).

### Read-only deployed artifact recheck (2026-10-17 JST)

At `2026-10-16T19:57:15Z`, both Workers still had 100% traffic on the deployment
IDs recorded above. `murakumo-client-static` is an assets Worker with no
modules; its script-content endpoint returns HTTP 204 with zero bytes. The
current `network-awai/cloud-murakumo` `main` tree at
`52fd611ee16a3794692f0548353630d0c8a1014f` contains no dedicated local/client/
static Wrangler config path, so the asset bundle still has no canonical source
closure.

`murakumo-meter` is on version 5 with script ETag
`484fd45929925020eafe5184be83b592d2c3f70e2c52008c9d0ac547b3219e59`. Its
read-only source inspection shows `/health` and `/v1/receipts/witness` paths,
the `LEDGER` D1 binding, and references to `metering_receipts` and `node`.
Binding names/types are `LEDGER:d1`, `METERING_ISSUER_TOKEN:secret_text`,
`METERING_SIGNER_DID:plain_text`, and
`METERING_SIGNING_KEY_PKCS8:secret_text`. No secret values or D1 records were
read. Two same-version content downloads both contained 5,277 bytes but differed
on lines 1 and 150, while version metadata and ETag stayed fixed. Do not use
those downloaded bytes as a stable source archive; recover the canonical source
or a version-pinned artifact before rebuilding the meter service. The sanitized
readback is in
[`murakumo-worker-artifact-readback-2026-10-17.csv`](murakumo-worker-artifact-readback-2026-10-17.csv).

## Device-link deployed Worker recheck (2026-10-17 JST)

A read-only Cloudflare API read at `2026-10-16T17:05:00Z` confirms that the
configured route `murakumo.cloud/api/devices/link/*` (`route_id`
`aed79958fb324ab6aae12e6a54f24df5`) targets `murakumo-node-registration` in
`cloud-kotoba` account `4da88288dc30d9ee257f319d3c33ecf0` for zone
`9795417e38ef69e173fe37371f937f3e`. Downloading the deployed script returned
HTTP 200. Its API metadata
reports `modified_on=2026-10-09T12:47:46.959121Z` and ETag
`2414090a8bf12392df7db55846b4d06adfb54c7759d6cf035a5a5fee6f0b077b`. The
returned 333,469-byte source string has SHA-256
`9e6336ebda8d8488eeb45e03dc3e0709438e9cd35afe028b310269d084723f56`.

The downloaded bundle contains all three paths (`/api/devices/link/status`,
`/start`, and `/poll`), the matching `murakumo-device-link-{status,start,poll}-v1`
proof-domain strings, and the client contract field names `deviceDid`,
`deviceProof`, `pollToken`, `flowId`, `userCode`, `expiresIn`,
`verificationUriComplete`, `registered`, and `accountDid`; `/portal/#device-link`
is also present. Status-code literals 201, 202, and 410 occur in the bundle.
The deployment API reports latest deployment
`06265538-9a8f-46ba-aa13-bf892c702420`, 100% on version
`63085e1f-81a0-47f9-9e92-49c65d1e0b99`, uploaded through Wrangler at
`2026-10-09T12:47:44.89391Z`. That active version is version 3, created by
Wrangler at `2026-10-09T12:47:43.930314Z`. Its annotations identify a
deployment but do not identify a source commit. This proves route
configuration and source markers; it does not prove live handler status/body
or state-transition behavior. No registration flow was created: no link
`POST` was sent. The source body was not saved; only its hash and contract
markers are retained.

The canonical GitHub sources were fetched directly from their `main` refs:

- [`cloud-murakumo/src/cloud_murakumo/devices_http.cljk`](https://github.com/network-awai/cloud-murakumo/blob/main/src/cloud_murakumo/devices_http.cljk)
  is blob `db25f7889be8659a27a01e09223d908de5527afd` (689 lines). Its current
  dispatcher handles devices, snapshots, registration, claims, history,
  challenge, heartbeat, and attestation; it has no `link/status`, `link/start`,
  or `link/poll` handlers. The repository `main` head is
  `52fd611ee16a3794692f0548353630d0c8a1014f`; the local checkout and its
  `origin/main` are both stale at `71820aff...` and `c1fa6e2a...` respectively.
- [`cloud-murakumo-usb-installer/nixos/account-link.mjs`](https://github.com/network-awai/cloud-murakumo-usb-installer/blob/main/nixos/account-link.mjs)
  is blob `bdce97bfd595264d739f87b340cbb8a755647eba` at `main` head
  `5911a37a39f9088677f44d61e6ab81de0aaf65cb`. This canonical client requires
  a 43-character flow ID, a 10-character user code, `expiresIn=300`, and a
  bounded integer `expiresAt`; it validates the server's
  `${authority}/portal/#device-link?code=...` URI and then displays
  `https://setup.murakumo.cloud/#device-link?code=...`. It signs each request
  with the three versioned proof-domain strings and polls every two seconds.
  The local checkout at `cd9888c...` is stale at 131 commits behind its local
  `origin/main`.

The deployed Worker is therefore not source-reproducible from current
`cloud-murakumo/main`. The route's configuration owner is resolved to
`wrangler.node-registration.jsonc`, but the deployed handler's source file and
commit remain unresolved. The canonical installer client is in GitHub. Before
migrating this route, resolve that owner and source, establish a reproducible
build with contract checks matching the canonical client, and compare the
rebuilt source to the deployed bundle's provenance. Then verify a consented
registration using a disposable identity and retain the signed
receipt/readback. Do not infer production behavior from the stale local
checkout or the bundle's token markers alone.

## Proposed Awai app-cell mapping

This mapping is derived from the canonical installer client, not from a
verified live server transaction. It is the target contract to preserve while
the deployed server source is recovered:

| Client operation | Client-authenticated input and accepted result | Awai app implementation |
|---|---|---|
| `POST /api/devices/link/status` | Device DID, timestamp, and device-key signature over `murakumo-device-link-status-v1`, authority, DID, and timestamp. Client accepts HTTP 200 and checks `registered`, `deviceDid`, and `accountDid` against its saved receipt. | Read the finalized Murakumo device-link state through the configured Awai quorum; return the verified account/device binding or an explicit unregistered/revoked result. |
| `POST /api/devices/link/start` | Device DID, 256-bit challenge, model, 256-bit poll token, and signature over `murakumo-device-link-start-v1` plus those fields. Client requires HTTP 201, a 43-character `flowId`, 10-character `userCode`, `expiresIn=300`, bounded `expiresAt`, and `${authority}/portal/#device-link?code=...`. | Append a signed link-request event to the `net.kotoba.murakumo` app cell. Keep the poll token and device private key out of public chain data. Approval must require the account owner's Itonami Passkey session and produce owner-verifiable signed receipt material. |
| `POST /api/devices/link/poll` | Flow ID, poll token, device DID, challenge, and signature over `murakumo-device-link-poll-v1`, authority, flow ID, DID, and poll token. Client accepts 202 while waiting, 410 for expiry/revocation, or 200 with a receipt bound to flow ID, device DID, challenge, model, and account DID. It verifies `userRootReceipt` before saving only public receipt evidence. | Derive pending/expired/revoked/approved from finalized Awai events and quorum reads. Return the signed `userRootReceipt` and binding fields only after finality; make replay and revocation decisions deterministic. |

The app's `.kotoba` core should decide valid link-state transitions from
explicit facts. The host adapter should handle Ed25519 verification, Passkey
session checks, canonical request/receipt encoding, and Awai reads/writes; it
must not recreate the decision in host conditionals. Preserve the three
versioned proof domains and receipt verification. Never write the device
private key, Passkey secret, or poll token to the chain or saved receipt.

Replace the `setup.murakumo.cloud` approval UI with the installed
`net.kotoba.murakumo` app reached through `itonami.app`; preserve the human
approval path only after its final URL and browser/Passkey handoff are verified.
If old API paths need compatibility, route them by direct DNS/TLS to
independent Awai gateways backed by finalized app-cell reads and writes. The
Itonami bootstrap gateway must not proxy to Workers, Pages, D1, R2, or Tunnel.
The production chain profile is still uncreated, so this mapping is a build
contract, not a deployed or finality-qualified implementation.

## Existing Murakumo mobile consumer

The canonical `cloud-murakumo` GitHub `main` at
`52fd611ee16a3794692f0548353630d0c8a1014f` includes a Kotoba Shell iOS/Android
reader (`cloud.murakumo.app`). Its
[`mobile/README.md`](https://github.com/network-awai/cloud-murakumo/blob/52fd611ee16a3794692f0548353630d0c8a1014f/mobile/README.md)
records six public GET dependencies: `api.murakumo.cloud` paths
`/infer/nodes`, `/infer/runs`, `/infer/payout`, and `/v1/models`, plus
`murakumo.cloud` paths `/api/v1/generation/catalog` and
`/api/v1/compute-market`. The bundled client is offline-capable and sends no
writes; it does not perform inference. This is a native client, not an
Itonami app-cell release, and its API requests still depend on the current
public domains.

Before moving the Murakumo API routes, retain those six GET contracts and
their CORS behavior on independent Awai nodes, then verify reads from the
current mobile bundle against the migrated endpoints. If the target removes
or changes an endpoint, publish and verify a replacement client before
retiring the old route. The client has no sign-in or write path, so it is not
evidence for device-link, account, settlement, or inference readiness.

## DNS and non-app services

The 28 DNS records are: 14 proxied AAAA hosts (`api`, `console`, `factoring`,
`generation`, `ipfs-pilot`, `local`, `meter`, root, `operator`, `setup`,
`status`, `toshokan`, `web`, and `www`); four proxied CNAMEs (`gateway`,
`infer`, `kekkai-control`, and `origin-generation`); three MX records; and
seven TXT records for mail policy/verification and IPFS content addressing.
The exact TXT/MX targets were deliberately omitted from the catalog, so their
current destinations must be read from the authoritative provider before any
DNS move. Mail routing, domain verification, and `_dnslink` are separate
service dependencies, not app bundle assets.

The latest read-only API pass at `2026-10-16T17:43:46Z` reports 28 DNS records
(18 proxied), ten Worker routes, and DNSSEC disabled. Its full 30-zone
summary matches the preceding zone snapshot by record count, type counts,
proxy state, apex record class, and DNSSEC state. The Registrar read at the
same time reports this domain active, locked, and auto-renewing through
`2027-06-22T12:12:29Z`. A root-page probe returned content on 2026-10-17 but
did not establish HTTP status, origin identity, or the health of every
subdomain/API. These configuration and page observations do not prove current
public delegation or origin reachability. Row-level checks are in
[`awai-app-registrar-recheck-2026-10-17.csv`](../awai-app-registrar-recheck-2026-10-17.csv)
and [`awai-cloudflare-zone-recheck-2026-10-17.csv`](../awai-cloudflare-zone-recheck-2026-10-17.csv).

The current Tunnel config snapshot separates connection state from service
behavior. `gateway.murakumo.cloud` routes to `localhost:8189`; the separate
`infer.murakumo.cloud` rule returns HTTP 404, so it is not a working inference
API in this configuration. `kekkai-control.murakumo.cloud` has heartbeat and
netmap path rules to local ports 17892 and 17893. The corresponding Tunnels
report healthy, which proves Tunnel connectivity/configuration only; it does
not establish that those local applications are healthy or that the host
configuration is reproducible from a repository. The fourth CNAME,
`origin-generation.murakumo.cloud`, points to the down `yukkuri-gemma-gad`
Tunnel and has no matching hostname ingress. Keep all four aliases as separate
migration rows. The sanitized ingress details are in
[`awai-murakumo-tunnel-ingress-config-2026-10-17.csv`](../awai-murakumo-tunnel-ingress-config-2026-10-17.csv);
DNS and Tunnel status comparison is in
[`awai-murakumo-tunnel-dnssec-recheck-2026-10-17.csv`](../awai-murakumo-tunnel-dnssec-recheck-2026-10-17.csv).

The full connected-account API re-read at `2026-10-16T18:52:37Z` again found
28 records on this zone and 10 configured Worker routes. The normalized
hostname/type/proxy and route/service identity sets match the saved
three-account snapshots by SHA-256. No DNS, route, Tunnel, registrar, or
runtime configuration changed. This re-read did not add public-origin or
application-health evidence; use the separate ingress configuration snapshot
above for the current hostname/path and local-port mapping.

A later per-domain read at `2026-10-16T20:27:06Z` again found the zone active,
DNSSEC disabled, 28 records (18 proxied), and ten Worker routes. The individual
Registrar read at `2026-10-16T20:27:39Z` reports Cloudflare as registrar,
`locked=true`, registry status `clienttransferprohibited`, and expiry
`2027-06-22T12:12:29.654Z`. This confirms the current transfer lock; it does
not show the receiving registrar's acceptance or establish that an auth code
is available. The row is in
[`awai-app-first-cohort-live-config-recheck-2026-10-17.csv`](../awai-app-first-cohort-live-config-recheck-2026-10-17.csv).

## Itonami/Awai destination

`net.kotoba.murakumo` is the only evidenced app ID for the root product. Treat
the root and `www` as candidate aliases for the Murakumo product app, whose
contract includes compute inventory, bids, placement, execution, readiness,
and generation receipts. The app release must preserve its UI and compute
market together; a chat-screen-only package does not replace the compute
plane. Operator nodes participate through explicit signed provider bids and
receipts, while keeping execution and settlement authority in operator/host
custody. Keep the ComfyUI bridge, Kekkai control, factoring, Toshokan, and IPFS
gateway as separately governed service boundaries until their app identity,
publisher, state owner, and capability contract are evidenced. A domain suffix
does not make those services one app.

The Itonami host should resolve an exact signed domain binding to an immutable
release, fetch content by CID from independent origins, request local capability
consent, and route app effects to peer-backed Awai cell/service adapters. The
public `murakumo.cloud` entry alias must be served through independent DNS and
TLS to independent gateways; the gateway may bootstrap the app but may not
proxy back to Cloudflare Workers, Pages, R2, or Tunnel. Keep the original API
paths only where the packaged app or its explicit adapter implements the same
authenticated contract.

## Kotoba bid-admission pilot (2026-10-17 JST)

A local Murakumo source branch now routes the CLJS quote selector's final
admission decision through `compute_market_admission.kotoba`, with an
import-free `wasm32-browser` artifact. The source hash is
`81c98aa6cfa2d359b9708457084391fbce8d8fde1608159de9a5533bf94da87c`; the
artifact hash is
`eb8d66f3b6f3075b56debf1c8dd0990beb2076a8cabbc7afbf93dabae372aa05`. The
product build script pins AMU `c3b1ef6a` and NBB `829f0ba1`; provenance
sidecars bind source and output hashes. This is the local pilot code path. It
has not been built into a product release or deployed; the existing Worker
remains the transitional host.

The exact policy source has a qualified AMU native selfhost microbenchmark:
4.549667 ns/decision versus a warmed JavaScript reference at 3.546375
ns/decision (1.282906x native/JS, 1,000,000 decisions per sample, seven
alternating rounds). This is not whole-service performance. No fresh repeat was
taken on 2026-10-17 because host load was 154.75 on 10 logical CPUs. See
[shared AMU selfhost benchmark report](../benchmarks/awai-kotoba-amu-selfhost-refresh-2026-10-17.md)
for the exact scope and artifacts.

This change ports one deterministic decision boundary. It does not create the
missing production chain profile, immutable Awai app release, peer-backed
execution adapter, or Cloudflare-independent gateway.

## Migration stages and proof

1. **Confirm contracts and owners.** For all 18 services, resolve the 4 hinted
   owners to source/config evidence; inventory request/response, auth, write,
   rate, billing, storage, and retention behavior. Capture all 28 DNS targets
   through the authoritative provider and identify the mail and IPFS owners.
2. **Package the app.** Build the Murakumo UI and compute-market contract as an
   immutable app release and load it through the local Itonami app host. Verify
   its source hashes, assets, CSP/iframe isolation, requested capabilities,
   bid/quote and receipt compatibility, and rollback to the prior release. The
   current product benchmark covers the bid-admission decision core, not this
   full application build. The product core is `.kotoba` and has an AMU
   selfhost native benchmark, but its web callsite still uses a bootstrap-
   emitted WASM wrapper: the adopted selfhost seed refuses `wasm32-browser`
   with `E2 unsupported target (only aarch64-macos)`. Qualify a selfhost
   portable artifact or an explicitly local-host execution path before calling
   the packaged release selfhost-built.
3. **Preserve the compute contract.** Keep execution on independently operated
   Awai nodes. Migrate job identity, deterministic placement, signed bids,
   billing authorization, artifact CID, and completion/error receipts. Run the
   same request with one operator unavailable and prove the remaining eligible
   operators can complete or return an explicit bounded failure. Signing keys
   and settlement authority stay in host/operator custody.
4. **Migrate each endpoint group.** Test API, operator/setup/status, generation,
   ads/MCP, node registration, Commons, and model/content services against
   recorded contracts. Handle the ComfyUI and Kekkai Tunnel paths as separate
   peer protocols. Preserve factoring, Toshokan, and IPFS gateway ownership
   until each has a signed app or service release and an explicit import/read
   receipt.
5. **Prove state and recovery.** Import or rederive user, device, job, meter,
   billing, and content references with owner-verifiable receipts. Verify
   reads/writes across independent peers, restart recovery, duplicate replay,
   revocation, and rollback. Do not treat same-host fixture replication as
   independent-operator evidence.
6. **Cut over DNS/TLS.** First publish and independently read the DNSSEC-backed
   exact binding and direct-DNS HTTPS gateway endpoints. Preserve mail and
   verification records. Route the canary through the new gateways, then
   compare every public host/path row and check browser, API, streaming, and
   receipt behavior. Keep a tested DNS rollback until the whole cohort passes.
7. **Retire old aliases.** After public readback, assign each Worker custom
   domain, route, Tunnel rule, `workers.dev`, preview URL, and proxied DNS row
   a final keep/redirect/retire decision. Verify that no request still reaches
   a Cloudflare runtime and preserve the public URL only where required.

The cohort cutover remains blocked on the production Awai chain profile, signed
release/binding authority, independent operators/gateways, 8 service-source
confirmations, DNS target and mail-flow verification, and real external
DNS/TLS/API readback. The production chain profile was confirmed by the user as
not yet created. No DNS, registrar, Worker, Pages, Tunnel, or production chain
configuration was changed for this runbook.
