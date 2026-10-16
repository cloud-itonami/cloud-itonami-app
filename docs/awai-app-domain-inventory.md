# Awai app domain inventory and migration gates

Snapshot date: 2026-10-16 (latest read-only account and Registrar API refresh:
2026-10-16; expanded host inventory read window completed by
2026-10-16T07:26:38Z; focused DNS/zone snapshot: 2026-10-16T07:00:48Z).
This is a read-only inventory from the connected
Cloudflare accounts API: zones, DNS records, Worker custom domains/routes and
`workers.dev` settings, Pages projects/domains/previews, Tunnel hostnames, and
the connected Registrar API. No DNS, Worker, Pages, Tunnel, registrar, or
deployment settings were changed. It does not yet cover registrar delegations
outside these accounts, direct authoritative-server queries, provider
configuration outside the connected Cloudflare accounts, application-origin
behavior, or mail delivery tests. The full account resource census is dated
2026-10-16; account-wide Worker, Pages, Registrar, and 27-zone DNSSEC readback
was refreshed again on
2026-10-16 JST. Public recursive DNS-over-HTTPS responses were also refreshed
on 2026-10-16 JST. Direct UDP DNS queries remain blocked in this environment;
the linked resolver responses are recursive observations, not direct queries
to the authoritative servers.

## Common target

Each product remains its own application and service identity. `itonami.app`
provides the common launcher, Awai registry client, and host API. An app release
is immutable content identified by CIDs and a signed Awai `:app/release`; a
signed `:domain/bind` maps a hostname to its app, publisher, update channel,
and exact release CID. A new release requires an owner-signed binding update,
so a domain does not silently follow a release channel tip.
Each app's UI and Awai
cells run under a local, fail-closed capability grant. The host resolves a fresh
DNSSEC TXT proof at `_itonami-app.<domain>` with value
`awai-app=<:domain/bind entry CID>`, fetches content from independent peers,
verifies every CID, and routes the host name to its app.

The public serving path must use independent nodes with direct A/AAAA records,
TLS certificates, and replicated Awai/content state. The final path must not
depend on Cloudflare proxying, Workers, Pages, R2, Tunnel, Load Balancing, or
authoritative DNS. Public authoritative DNS and standard TLS remain required
and must have independent operators. Mail MX, SPF, DKIM, and DMARC records are a
separate service plane and must be preserved or independently migrated with
mail-flow readback. The common Itonami app runtime and authenticated
marketplace view of accepted Awai registry records merged as PR #431 on
2026-10-16 (`e708d000e6ff634a9368fbb3a832867db1d7e6fe`). This is merged source,
not a deployed runtime; the v4 checkpoint/quorum and gateway follow-ups below
remain local and unmerged. The Awai DNA projects accepted entries with their canonical
signed envelopes in `awai.app-registry.read.v4`, including publisher revocations
and a checkpoint signed by each serving peer's persistent libp2p identity;
Itonami independently verifies
each envelope's CID, DNA, Ed25519 signature, signer binding, and body/projection
equality. A fixture registry replayed eight signed entries and returned the
`murakumo.cloud` candidate for `net.kotoba.murakumo`.

A separate two-process loopback integration smoke runs Itonami's real
install-bridge adapter against the Awai HTTP API. It transferred five
CID-verified fixture bundle blocks, installed the exact cell, submitted two
signed chained notes, rejected a malformed signature, and read both accepted
notes back. The smoke supplies a fixture install record directly to the
adapter. It verifies the Itonami-to-Awai bridge and local cell API, but does
not run the full DNSSEC/candidate/launcher/consent/protected-store flow or prove
agreement across independently operated hosts. No production DNA, release, or
peer service was used.

A separate Awai G14 integration gate now starts two node processes with
separate local stores and exchanges signed fixture app-cell entries over the
actual TCP/Noise/Yamux transport. A signed entry is accepted from node A by B,
a chained entry is accepted from B by A, both read back the same two accepted
CIDs, and B rejects a broken signature. This closes the local two-process
transport check only; the nodes share one host and fixture DNA, so independent
operators, public peer discovery, NAT traversal, release distribution, and
cross-network recovery remain unqualified. The same gate confirms that an
owner-only Ed25519 peer identity survives restart and that accepted log entries
replay under the stable peer ID.

DNSSEC validation, CID-verified content staging, manifest verification,
UnixFS bundle reads, protected install-record storage, capability consent, and
per-app local static launch have source implementations in the Itonami
worktree. The active Node/WASM guest exposes `/health`,
`GET /api/awai/app-registry`, `GET /api/awai/domain-proof`,
`POST /api/awai/install-plan`, `POST /api/awai/install-content`,
`POST /api/awai/install`, `/`, and `/awai-launcher.js`. Proof, plan, and
content-stage routes require an envelope-verified registry candidate;
content-stage also requires the exact loopback origin and Host and verifies
each block CID. Installation follows local consent and protected install-record
readback before it calls the token-protected Awai cell API. A separate local
route launches an already-installed bundle at an app-specific `.localhost`
origin inside an opaque-origin iframe with network access and host effects
disabled. Its local dummy-install smoke passed. The full launcher flow currently
stops at `dnssec-not-secure`: the ten migration zones have DNSSEC disabled and
no parent DS. Independently, the active install-scope resolver now requires a
host-owned Amano proof verifier. The shared Inga source now contains a
challenge-bound current-ref quorum reader, and the isolated Inga-node source
adds a signed `GET /current-ref` response from a node's local committed
projection. Their local suites pass 8 tests / 29 assertions and 15 tests / 78
assertions, respectively. The Inga core and Inga-node changes are pushed to
feature branches, not `main`, and are not yet wired into Itonami: the
install-scope route still supplies neither current-ref proofs nor a verifier,
so it returns `amano-current-ref-proof-unavailable` before content retrieval
even after DNSSEC is enabled. The lifecycle contract now keeps DNS freshness in
seconds and Amano current-ref freshness in milliseconds; its focused app suite
passes 14 tests / 39 assertions under Nbb. The node response is one witness
observation, not quorum or exact-event finality evidence. `finish-install`
repeats the verifier check before writing an install lock. Production content
delivery, TLS serving, sandbox qualification, updates, and capability-mediated
effects are not deployed or qualified. The registry reader fails closed and
has no public or Cloudflare fallback.

The app layer does not collapse distinct services into one identity. For
example, Murakumo inference remains an execution service that an app may call
through an explicit capability; Aozora keeps its AT Protocol PDS/AppView
contracts; and x402 keeps its payment verification and settlement boundary.
Shared launcher, registry, content verification, local installation, sandbox,
capability, and update contracts belong to the Itonami/Awai foundation; each
product app supplies its own UI, data schema, service adapters, and migration
receipts.

## Shared foundation PR readiness (2026-10-16)

The common host is assembled from merged protocol and Itonami runtime slices,
with the Awai registry integration and production serving still outstanding.
The merged source is not a deployed runtime. Current PR and CI metadata was
refreshed on 2026-10-16.

| Foundation layer | Current source and review state | Evidence available | Remaining qualification |
|---|---|---|---|
| Itonami app host | [`cloud-itonami-app#431`](https://github.com/cloud-itonami/cloud-itonami-app/pull/431) merged on 2026-10-16 as `e708d000e6ff634a9368fbb3a832867db1d7e6fe`. Its common-foundation follow-up is [PR #432](https://github.com/cloud-itonami/cloud-itonami-app/pull/432), still open as a draft. | Focused app lifecycle suite: 14 tests / 39 assertions passed under Nbb. A branch-range Gitleaks scan at head `9eea36d5` covered all seven PR commits and found no leaks. CI run 761 passed `amu-jvm-free-emit`, `awai-public-gateway`, and `windows-launch-smoke`; its repository-wide `secret-scan` failed on seven findings across 1,233 commits. | The current-ref host verifier is not connected to the install route. The broader JVM suite remains unverified. The historical secret findings need separate triage; they are outside the PR's branch range. |
| Bootstrap HTTPS ingress | [`deploy/awai-public-gateway`](deploy/awai-public-gateway), included in PR #432 | The gateway CI syntax check and all nine policy tests pass. A local HTTPS smoke with a disposable certificate and fake upstream passed launcher and plan preview, credential stripping, blocked install route, wrong SNI, foreign Origin, and fixed CSP despite a permissive upstream CSP. | Direct public DNS, production certificate, independent host operations, registry freshness, and external readback remain unverified. This bootstrap gateway does not yet route or execute product apps. |
| Awai registry and peer host | [`network-awai-apex#19`](https://github.com/network-awai/network-awai-apex/pull/19), open draft at head `95b7ae601e1665836e725f1e577e670930150be7`. | All 16 `app-registry --host` gates passed, including 32 vectors on three targets (96/96), peer-checkpoint replication, and G15. The peer gateway has six policy tests and a disposable-certificate loopback HTTPS smoke. Current Wasm SHA-256: `15692fa9f19cc322d8ee8b7294f822234db0794f25bcc9c1654da4310f2f2610`. | The PR remains draft and unmerged. G14 uses two processes on one host over loopback; independent operators, public peer discovery, NAT traversal, production DNA, and node-loss recovery remain unqualified. The HTTPS gateway has no public host, certificate, or DNS readback. |
| Amano lifecycle refs | Inga adds `current-ref-read/v1` at feature commit `7e368fbc0c3777dbb95f8b7153b47f18d174d68d` on the existing `e5948e1` dependency baseline for challenge-bound signed quorum observations (8 tests / 29 assertions). Inga-node adds `GET /current-ref` at `8429459fdf9b2e0dce4fb335e88dad8e254c754f` for one node-signed committed projection; `3707ce2` only pins that branch to the Inga baseline (15 tests / 78 assertions are on the endpoint branch). Both endpoint changes remain on pushed feature branches, not `main`. Itonami's focused lifecycle suite passes 14 tests / 39 assertions under Nbb and keeps DNS freshness in seconds separate from the millisecond current-ref clock. A host-owned `verify-amano-ref` verifier is still required at resolution and install time to cover exact refs, chain-profile identity, challenge, quorum-read/finality facts, and freshness. | The endpoint rejects malformed or duplicate query parameters and will not report a head unless its stored commitment proof is present. An earlier same-day read-only probe reached w4 and found no Murakumo domain/app heads. A fresh read-only check from this host could not connect to local Tailscale and reached 0/4 witness health endpoints. | The Inga pieces are not integrated into Itonami; no multi-witness response collection, exact-event finality adapter, reachable current-ref API, or verified production chain profile is connected. The fresh reachability failure describes this host's access only; it does not establish that remote witness processes are down. No app-domain binding or release is established on this chain. |
| App-to-cell capability contract | [`kotoba-protocol#16`](https://github.com/kotoba-lang/kotoba-protocol/pull/16), merged on 2026-10-16 as `3aadfbcb31c4e995276993af9c0d80520b33c1b8` | The focused `protocol_test` namespace passed 96 tests and 576 assertions; direct local checks confirmed read remains grantable and write is denied without a trusted label. | The full portable suite remains unverified because unchanged `credential_test.cljk` fails on both the merged-base predecessor and the earlier PR head. No public runtime uses this contract yet. |

The Itonami host consumes the merged protocol contract and Awai registry
projection. Awai integration follow-ups, independent peer operations,
production signed releases and content origins, public DNSSEC and TLS, and
external readback remain outstanding before `itonami.app` can serve as the
common public bootstrap. PR #432 contains the current source follow-up but is
still draft; the gateway has no production host or certificate. No migration
cohort hostname has an accepted Awai domain binding, and no public domain has
been switched to this runtime. `murakumo.cloud` stays the first product pilot
after these shared gates pass.

## Chain endpoint read-only reachability check (2026-10-16)

The checked-in `chain-amano-dns` probe configuration names four Tailscale
addresses. A fresh read-only check on 2026-10-16 found that the local CLI could
not connect to its Tailscale daemon and all four HTTP health requests from this
host failed. This proves only that this host currently has no route to those
endpoints; it does not prove that remote witness processes are stopped.

An earlier same-day probe reached w4 and reported `1/4` endpoints reachable
with quorum status `DEGRADED`. The direct w4 `/health` response identified its
chain as `inga-reservation-20260911-v1` (witness `w4`), at view `212206`, height
`6458`, committed height `6456`, with zero inbound/outbound peers and three
reported equivocations. That response does not establish that this is a
qualified Amano/Awai app-lifecycle chain; its chain profile and current quorum
could not be verified from this host.

Read-only `GET /committed` queries to w4 for
`awai/domain/murakumo.cloud` and
`awai/app/net.kotoba.murakumo/stable` both returned `ok=true`,
`source=filesystem`, `records=1`, and `head=null`. This endpoint response is a
single-node filesystem read, not a quorum-authenticated current-ref proof or
finality evidence. It does not establish that a Murakumo binding or release is
committed across the chain. The Itonami resolver must not treat this endpoint
or chain as an app trust anchor. No chain, DNS, or runtime settings were
changed.

The shared runtime model and its readiness gates are maintained in
[`awai-app-runtime.md`](awai-app-runtime.md). Awai's signed predecessor chains
and replicated app DNA cells provide validation and replication; they do not
create one globally ordered ledger or imply payment finality. Any app that
requires global ordering must name and verify that service separately.

## Current Cloudflare exposure

### Fresh account API readback (2026-10-16)

Read-only API queries currently return 27 active zones, 109 Worker scripts, 78
Worker custom domains, 16 Pages projects, 30 route patterns, 30 Tunnels, and
26 Registrar registrations in `cloud-kotoba`; `fund-mithril` has 3 active
zones, 36 Worker scripts, 23 Worker custom domains, 6 route patterns, and 3
Registrar registrations; `com-junkawasaki` has no zones, Workers, Pages
projects, Tunnels, or Registrar registrations.
The Pages API's paginated read returned 16 projects (10 on page 1 and 6 on
page 2). This inventory is account-scoped and is not a complete list of
registrar delegations or public origins.

All 27 `cloud-kotoba` zones below were individually rechecked and returned
`status: disabled` from their Cloudflare `/dnssec` API endpoint. The ten
migration cohort zones were individually rechecked at 07:00 UTC.
Consequently the proposed `_itonami-app.<domain>` DNSSEC proof cannot be
qualified against any of them in the current configuration. Every zone also
needs registrar/parent DS and independent authoritative-resolver readback; the
Cloudflare API setting alone is not public DNS proof. All resource counts in
the table below were re-read on 2026-10-16; per-domain public record and route
descriptions remain subject to independent DNS/HTTP readback.

| Zone | DNS records | Worker custom domains | Worker routes | Pages custom domains | Cloudflare DNSSEC |
|---|---:|---:|---:|---:|---|
| `itonami.app` | 16 | 1 | 1 | 0 | disabled |
| `murakumo.cloud` | 27 | 14 | 10 | 0 | disabled |
| `aozora.app` | 12 | 7 | 0 | 0 | disabled |
| `oppai.fans` | 0 | 0 | 0 | 0 | disabled |
| `isekai.network` | 6 | 0 | 0 | 1 | disabled |
| `itonami.cloud` | 32 | 15 | 4 | 2 | disabled |
| `x402.nexus` | 7 | 1 | 0 | 0 | disabled |
| `shinshi.club` | 10 | 1 | 0 | 0 | disabled |
| `babiniku.net` | 6 | 0 | 0 | 1 | disabled |
| `awai.network` | 9 | 2 | 0 | 0 | disabled |

The focused zone/DNS/Worker/Pages read at `2026-10-16T07:00:48Z` also
confirmed the current serving-resource mapping below. DNS record counts include
mail records; this is provider configuration evidence, not public HTTP or mail
flow verification.

| Zone | Current serving resources and unresolved route state |
|---|---|
| `itonami.app` | Worker `ossekai-arb-intake` at `arb.ossekai.itonami.app`; route `kaisya.itonami.app/*`; proxied wildcard; 13 `_dnslink` TXT records. No apex A/AAAA record. |
| `murakumo.cloud` | 14 Worker custom domains and 10 Worker routes; `gateway`/`infer` use healthy `comfyui-gad` Tunnel; `origin-generation` uses a down Tunnel. Three CNAMEs target Tunnel hosts. |
| `aozora.app` | Workers: apex SPA, `appview`, `auth`, `live`, `pds`, `shinshi`, and `magatama`; the last two share `app-aozora-pds`. Seven proxied AAAA records and separate mail records. |
| `oppai.fans` | Active zone but zero DNS records, Worker custom domains/routes, or Pages custom domains. Source still declares a Worker app, so source/provider drift remains unresolved. |
| `isekai.network` | Pages project `network-isekai` serves the custom domain; one CNAME targets its `pages.dev` hostname. |
| `itonami.cloud` | Pages project `cloud-itonami`; 15 Worker custom domains and four routes. `gateway` points to the down `itonami-5820` Tunnel; wildcard IPFS/IPNS gateway records remain proxied. |
| `x402.nexus` | Apex Worker `nexus-x402`; `signer` CNAME targets the down `nexus-wallet-25mbair` Tunnel. |
| `shinshi.club` | Apex Worker `magatama-sh1n5h1x`; proxied `www` AAAA exists but is not a Worker custom domain. |
| `babiniku.net` | Pages project `net-babiniku`; one CNAME targets its `pages.dev` hostname. |
| `awai.network` | Apex and `www` Worker custom domains both map to `network-awai-apex`; no zone routes or Pages custom domains. |

The `workers.dev` and `pages.dev` hostnames remain separate public routes even
when a custom domain is moved. The account-level project and script details
below therefore remain part of the migration inventory. The per-script
`workers.dev` enablement counts later in this document were last refreshed on
2026-10-15.

| Domain | Current HTTP/API surface | Other records and migration constraints |
|---|---|---|
| `itonami.app` | Proxied wildcard `*.itonami.app`; direct A record and Worker route `kaisya.itonami.app/*`; Worker custom domain `arb.ossekai.itonami.app`. | 13 `_dnslink` hostnames: `animator`, `bim`, `cad`, `daw`, `genko`, `inkan`, `kaisya`, `modeler`, `nle`, `rirekisho`, `sculpt`, `shirohan`, and `tsukuru`. These records point to IPFS/IPNS CIDs but are not Awai `domain/bind` records. The wildcard currently terminates through Cloudflare. This is the common launcher domain, so its own bootstrap path must leave Cloudflare before other domains can rely on it. |
| `itonami.cloud` | Pages project `cloud-itonami` serves `itonami.cloud` and `cloud-itonami.itonami.cloud`; 15 Worker domains include `app`, `auth`, `agent`, `bots`, `dougaka`, `hanmoto`, `hooks`, `kenbun`, `kouryaku`, `local`, `mangaka`, `mcp`, `office`, `relay`, and `treasury`. `local` maps to `itonami-client-static`. Four Worker routes cover app auth, bot status, and the EthGlobal demo. `gateway.itonami.cloud` points at a down Cloudflare Tunnel. The same down tunnel config also names `mcp.itonami.cloud`, while an enabled Worker custom domain serves `mcp.itonami.cloud`; reconcile this dual configuration. `*.ipfs.itonami.cloud` and `*.ipns.itonami.cloud` proxy the IPFS/IPNS gateway. | The apex is also the WebAuthn RP ID. Preserve sign-in, existing sessions during transition, host-only cookies, and mail records. Moving to `itonami.app` changes the origin and does not transfer a raw WebAuthn credential. |
| `murakumo.cloud` | 14 Worker domains include the apex and `www`, plus `api`, `console`, `factoring`, `generation`, `ipfs-pilot`, `local`, `meter`, `operator`, `setup`, `status`, `toshokan`, and `web`. `local` maps to `murakumo-client-static`; `ipfs-pilot` is served by `murakumo-ipfs-gateway-pilot`; its `_dnslink` TXT points to IPNS name `k51qzi5uqu5dlux8z4nuviwt8ww812ujkb4v1sxm91d630bjrbhsjeftp7t2iu`. Ten Worker routes cover node registration, Commons/legal content, LLM documents, and ads/MCP. `gateway` and `infer` point to the healthy `comfyui-gad` Tunnel; `origin-generation` points to a down Tunnel whose ingress config does not include that hostname. | The live app provides chat, inference and generation, alongside device registration, content, status, and advertising APIs. This is the selected first migration domain. Replace each hostname and route only after its service contract and independent-node read/write behavior are evidenced. Preserve mail records. |
| `aozora.app` | Worker domains: apex, `appview`, `auth`, `live`, `magatama`, `pds`, and `shinshi`. | Preserve AT Protocol PDS, AppView, auth, and live service behavior independently. Preserve mail records. |
| `isekai.network` | Pages project `network-isekai` (`network-isekai.pages.dev`, production branch `main`). | Preserve mail records. |
| `babiniku.net` | Pages project `net-babiniku` (`net-babiniku.pages.dev`, production branch `main`). | Preserve mail records. Current Pages production config binds R2 `GENERATED_ASSETS`; `BROADCAST_DB` is present in the preview config only, so its production ownership/use needs a source-level reconciliation. |
| `x402.nexus` | Worker custom domain at the apex (`nexus-x402`); `signer.x402.nexus` points at a Cloudflare Tunnel. | Preserve payment verification, settlement, idempotency, and signer separation; payment proof is not a migration or settlement receipt. Preserve mail records. |
| `shinshi.club` | Worker custom domain at the apex (`magatama-sh1n5h1x`) is enabled; proxied `www` record has a redirect comment but the actual redirect rule was not inspected. A second script, `shinshi-cljc`, has its own `workers.dev` route enabled. | Preserve mail records and both D1 stores; inspect the old/new worker split and verify the actual `www` response before cutover. |
| `awai.network` | Worker custom domains `awai.network` and `www.awai.network` serve `network-awai-apex`; proxied AAAA records are present. There are no zone Worker routes or Pages custom domains. The `network-awai-apex.workers.dev` subdomain is disabled. | The source is a chat/image/video generation app backed by Murakumo. Preserve mail and ownership records, then migrate both app hosts and their capability-gated generation calls. |
| `bots.kotoba.cloud` | Also attached to the `cloud-itonami` Pages project. | Cross-domain Itonami surface discovered through the Pages project inventory; include it in the migration cohort or explicitly retire it after a public readback. |
| `kotoba.cloud` | Worker domains include `kotoba.cloud`, `www`, `console`, `blog`, `apps`, `graph`, `support`, `app`, `storage`, `kyber`, `twin`, `yabai`, `knowledge`, `data`, and `docs`; `auth.kotoba.cloud` is attached to `kotobase-authn`; `kagitaba.kotoba.cloud` is attached to `kagitaba-kms`. `bots.kotoba.cloud` is a Pages custom domain. | Shared authentication, key management, and Itonami-adjacent Pages surfaces. Keep these in the dependency inventory; the exact boundary between Itonami/Awai and Kotoba services still needs owner/source classification. |
| `oppai.fans` | The Cloudflare zone is active but has no DNS records, Worker custom domains/routes, or Pages domain in the connected account. The `oppai-fans` Worker script exists, but its `workers.dev` route is disabled. The source repo still describes a Worker + SPA at `oppai.fans`. | This is a provider/source mismatch, not evidence that the app is dormant. Reconcile public delegation and the deployed origin before changing or replacing the domain. |

The queried Cloudflare DNS records for this cohort contain no `awai-app=` TXT
binding. Existing `_dnslink` records on `itonami.app` and `itonami.cloud` point
to IPFS/IPNS content but do not establish a signed Awai domain binding.

The connected account API reports 27 active zones and two moved-zone records
in `cloud-kotoba`, three active zones in `fund-mithril`, and no zones in
`com-junkawasaki`. The complete active-zone names were re-read on 2026-10-16.
The initial cohort is the eight domains named by the user plus `itonami.app`
and `awai.network`. All other routed names and provider hostnames in the
connected accounts are also in the common-app scope. A focused read-only
refresh on 2026-10-16 identified serving bindings on six of the eleven
previously unclassified `cloud-kotoba` zones and on `mithril.fund`; application
source ownership, external delegation, and production behavior still need
reconciliation before their app manifests or cutovers can be qualified.

| Scope classification | Active zones |
|---|---|
| Initial migration cohort | `aozora.app`, `awai.network`, `babiniku.net`, `isekai.network`, `itonami.app`, `itonami.cloud`, `murakumo.cloud`, `oppai.fans`, `shinshi.club`, `x402.nexus` |
| Related service/alias surfaces | `etzhayyim.com`, `gftd.ai`, `gftd.cloud`, `kotoba.cloud`, `kotobase.net`, `yataverse.com` |
| `cloud-kotoba`: serving bindings found; app/source mapping remains | `kotoba.foundation`, `kotobalabs.com`, `kotoba-lang.org`, `manimani.cloud`, `spirit-in-physics.org`, `yaoi.dev` |
| `cloud-kotoba`: no DNS/Worker/Pages/Tunnel binding found in connected account | `etzhadaat.org`, `kotoba.digital`, `kotoba-labs.com`, `nubatama.net`, `yatalake.com` |
| `fund-mithril`: serving bindings found | `mithril.fund` |
| `fund-mithril`: no DNS/Worker/Pages/Tunnel binding found in connected account | `mithril.love`, `mythril.build` |

Subdomain aliases remain separately inventoried even when their parent zone
appears above. Known examples include `sculpt.itonami.app`,
`auth.kotoba.cloud`, `kagitaba.kotoba.cloud`, `bots.kotoba.cloud`,
`comics.spirit-in-physics.org`, and service aliases on `etzhayyim.com` and
`gftd.ai`. The zone census closes the account-apex enumeration only; it does
not establish the full set of registrar delegations, third-party DNS zones,
mail domains, or independently hosted public names.

## Product service contracts to carry through migration

The app ID and its service contract stay separate from a DNS alias. These
contracts come from the product repositories and current Cloudflare bindings;
they describe migration obligations, not proof that a replacement runtime is
ready.

| Migration unit | Current contract and dependency evidence | Required replacement proof |
|---|---|---|
| `itonami.app` launcher | Proxied wildcard, `kaisya.itonami.app/*`, `arb.ossekai.itonami.app`, and 13 legacy `_dnslink` aliases remain. No DNS record in this inventory has an Awai `domain/bind` proof. | Bootstrap the launcher from verified immutable content across independent origins; prove direct DNS/TLS and offline launch before it becomes another app's resolver. |
| `murakumo.cloud` compute service | Worker and Tunnel routes serve inference, generation, model hosting, device registration, status, and operations. The service is a compute plane with job identity, placement, billing, and receipts; it is not just the `murakumo.cloud` web app. | Run execution on Awai-connected operator nodes, preserve per-job authorization/idempotency/receipts, and prove generation and inference read/write behavior while one node is unavailable. Keep each app's Murakumo access behind a host capability. |
| `awai.network` | `network-awai-apex` serves chat, image, and video generation from the apex and `www`. Chat and video proxy through the app Worker; image generation and the model map call Murakumo directly. Video uses a secret capability and Murakumo job/artifact APIs. | Package the three studios as an Awai app; retain the video capability outside the app bundle, enforce it at the host adapter, and prove streaming, long-running image jobs, job polling, and artifact reads through independent Murakumo nodes. |
| `aozora.app` | The `app-aozora` source defines the canonical AT Protocol PDS, AppView, XRPC, feeds, search, and profile publication boundary. `etzhayyim.com` is a separate organization surface but still has `atproto` and `pds` Tunnel aliases. The current PDS has an R2 blob binding; live media has a separate R2 segment binding. | Preserve AT Protocol repo writes, blob reads, AppView queries, Lexicon compatibility, and live playback on independent nodes. Move the `etzhayyim.com` service aliases only after consumers use the canonical endpoint or have an explicit redirect plan. |
| `oppai.fans` | The product source describes a Worker + SPA and Murakumo generation/chat calls with consent and age gates. The current account has an `oppai-fans` script with an R2 binding but no public Worker route; `workers.dev` is disabled, and the zone has no DNS records. Source and deployed route state disagree. | Reconcile external delegation first. Preserve age/consent checks, private face enrollment, and the Murakumo capability boundary; prove the actual public origin and storage path before cutover. |
| `isekai.network` | The source documents a static snapshot already pinned on two independent IPFS nodes, but production Pages still binds D1 `SCORES_DB` and `SOCIAL_ROOMS_SERVICE`; generation jobs and game writes are separate runtime paths. | Verify the static CID from independent public clients, then migrate scores, rooms, generation, and fork writes independently with state import and write receipts. Static availability alone is not completion. |
| `itonami.cloud` | Pages is also the WebAuthn RP origin and binds `WORKSPACE_DB`, `ITONAMI_DATA`, `BOT_SANDBOX`, and `CAPITAL_API`. The auth Worker separately binds a Durable Object, `ITONAMI_DATA`, and `kotobase-authn`. | Keep account and session continuity, prove WebAuthn credential migration/renewal, and move D1/KV/DO and service calls with export/import receipts. A new origin cannot reuse the old RP ID credential as if it were the same origin. |
| `x402.nexus` | The apex Worker is the payment facilitator; it binds two R2 buckets and has a `signer.x402.nexus` Tunnel entry. The Tunnel currently reports down. The x402 source defines Base payment verification, settlement, seller-specific treasuries, and idempotency. | Keep verification, settlement receipts, seller isolation, and replay protection in a separate payment service app. Prove chain-backed results and signer recovery before moving traffic; a DNS response is not a payment receipt. |
| `shinshi.club` | The apex still maps to legacy `magatama-sh1n5h1x`; `shinshi-cljc` also has a public `workers.dev` route. Both scripts bind `SHINSHI_DB` and `RECORDLOG_DB`; the legacy worker has additional secret bindings whose values were not read. | Reconcile which worker is the intended app release, preserve both databases and moderation/consent behavior, then test a canary and immediate rollback before changing the apex. |
| `babiniku.net` | Production Pages binds R2 `GENERATED_ASSETS`; `BROADCAST_DB` is present only in preview configuration. The product source describes private sessions, a local browser broadcast path, optional Cloudflare Stream ingest, and an Aozora live playback route. | Determine whether production broadcast writes exist outside the Pages binding, migrate any live ingest and generated assets, and prove playback from independent origins without Stream or Worker dependencies. |

`awai.network` has Google Workspace MX/DKIM and Itonami ownership-verification
records, alongside the two active `network-awai-apex` Worker custom domains.
Keep the Awai operator identity and mail service separate from the app registry;
the generation app still needs its own signed app identity and release.

## Cloudflare-owned public endpoints and state dependencies

The `cloud-kotoba` Workers account has the `04-feasts-minded.workers.dev`
subdomain. The latest 2026-10-16 API refresh lists 109 Worker scripts and 78
Worker custom domains. The earlier same-day per-script subdomain settings read
covered 107 scripts: 45 had
their `workers.dev` hostname enabled, and 43 of those also allow preview URLs.
These hostnames remain public Cloudflare runtime surfaces even when a custom
domain is moved. The earlier enabled-script read included the following account
routes; entries outside the product cohort still need an owner/scope decision:

`aiueos-updates`, `app-aozora-appview`, `app-aozora-auth`, `app-aozora-live`,
`app-aozora-pds`, `app-aozora-spa`, `app-otent`,
`cloud-itonami-app-classifieds-production`, `cloud-itonami-app-edge`,
`cloud-itonami-domain-reverify`, `cloud-itonami-mail-inbound`,
`cloud-itonami-marketplace-buyer`, `cloud-itonami-marketplace-crossborder`,
`cloud-itonami-marketplace-fulfillment`, `cloud-itonami-marketplace-listing`,
`cloud-itonami-marketplace-onboarding`, `cloud-itonami-marketplace-order`,
`cloud-itonami-marketplace-returns`, `cloud-itonami-marketplace-settlement`,
`cloud-itonami-tsukuru`, `cloud-itonami-workspace-outbox`, `engi-node`,
`gleif-lei-datasource`, `goriketsu-proxy`, `inga-replica-testnet`,
`itonami-pricing-resident`, `kaigi`, `kenbun`,
`kotoba-cloud-cljk-preview`, `kotoba-cloud-pages-candidate`,
`kotoba-foundation-commons`, `kotoba-worker-demo`, `kura-conformance`,
`mangaka-reader`, `murakumo-setup`, `ossekai-arb-intake`, `saisei-worker`,
`shinshi-cljc`, `social-rooms`, `spirit-in-physics-api`,
`spirit-in-physics-researcher`, `spirit-in-physics-web`, `tomoshibi-mail`,
`torihiki-node`, and `torihiki-validator-v3`.

Only `kotoba-cloud-cljk-preview` and `kotoba-cloud-pages-candidate` have
preview URLs disabled; the other 43 allow previews. The `oppai-fans` script
exists but has both its `workers.dev` hostname and previews disabled. Cloudflare's
documented hostname format is
`<worker>.<account-subdomain>.workers.dev`; disabling that route alone also
does not disable all version/deployment URLs. Preview and deployment URLs need
their own inventory and public readback before retirement. See the official
[Workers.dev routing documentation](https://developers.cloudflare.com/workers/configuration/routing/workers-dev/)
and [Preview URL documentation](https://developers.cloudflare.com/workers/previews/).

Cloudflare Pages also keeps each project's `pages.dev` hostname in addition to
custom domains. The Itonami/Awai-related projects are `cloud-itonami`,
`itonami-agent-treasury-tokyo2026`, `cloud-itonami-partners`,
`cloud-itonami-isic-6492`, `cloud-itonami-commitment-ledger`, `net-babiniku`,
and `network-isekai`. The account also has `cyber-drill-merble`,
`store-36b48539`, `torihiki`, `org-spirit-in-physics-comics`,
`gorigori-ketsu-dasshu`, `cyber-drill`, `spirit-in-physics-researcher`,
`spirit-in-physics-web`, and `deps-gftd-ai`; their product scope still needs
classification. Preview deployments are a separate hostname surface; the
Pages API read did not prove that access is restricted. Cloudflare documents
that preview deployments are public by default and that `pages.dev` access can
be disabled separately from custom domains; verify each project's current
setting and URLs. See [Pages preview deployments](https://developers.cloudflare.com/pages/configuration/preview-deployments/)
and [Pages custom domains](https://developers.cloudflare.com/pages/configuration/custom-domains/).

Several Pages projects own durable state or call other hosted services, so a
domain cutover alone is insufficient:

| Pages project | Observed state/service bindings to preserve |
|---|---|
| `cloud-itonami` | D1 `WORKSPACE_DB`, KV `ITONAMI_DATA`, service bindings `BOT_SANDBOX` → `murakumo-bot-workspaces` and `CAPITAL_API` → `cloud-itonami-api`; custom domains include `itonami.cloud`, `cloud-itonami.itonami.cloud`, and `bots.kotoba.cloud`. |
| `itonami-agent-treasury-tokyo2026` | D1 `WORKSPACE_DB`, KV `BOTS_STATUS` and `ITONAMI_DATA`, R2 backup/data/drive/mail buckets, and bindings to Murakumo Bot workspaces and the Itonami API. |
| `net-babiniku` | Production: R2 `GENERATED_ASSETS` and the Murakumo generation API. Preview only: D1 `BROADCAST_DB`. Reconcile the source's broadcast persistence before moving writes. |
| `network-isekai` | D1 `SCORES_DB` and the `SOCIAL_ROOMS_SERVICE` binding. |
| `cloud-itonami-partners` | Production KV `PARTNERS_KV`; no preview binding. |
| `cloud-itonami-isic-6492` | KV `ISIC6492_LOAN_KV`. |
| `cloud-itonami-commitment-ledger` | KV `COMMITMENT_LEDGER_KV` and a runtime dependency on `cloud-itonami-isic-6492.pages.dev`. |

These bindings and named stores came from the Pages project configuration API;
they identify state that needs an export, importer, or explicit retirement
decision before removing the serving runtime. Secret values were not recorded.

## Live connected-account refresh (2026-10-16)

The read-only API refresh confirms three accounts: `cloud-kotoba` (27 active
zones, 109 Worker scripts, 78 Worker custom domains, 16 Pages projects, and 30
Tunnels), `fund-mithril` (3 active zones, 36 Worker scripts, 23 Worker custom
domains, and no Pages projects or Tunnels), and `com-junkawasaki` (no zones,
Workers, Pages projects, or Tunnels). These provider accounts are all in the
external app census. Account separation does not exclude public hostnames from
the common Itonami/Awai lifecycle; each product's app identity, state, and
ownership remain independently scoped. This is a provider account snapshot,
not a registrar or public-DNS inventory.

For `cloud-kotoba`, the current `workers.dev` account prefix is
`04-feasts-minded`. A per-script settings read on 2026-10-16 across 107 scripts
found 45 enabled
`workers.dev` hostnames; 43 also allow preview URLs. The full account-wide enabled list is recorded
above. `oppai-fans` exists as a Worker script but has neither its `workers.dev`
hostname nor previews enabled; no DNS, Worker custom domain, zone route, or
Pages custom domain for `oppai.fans` appeared in this account. Its public
status outside this account is still unknown.

The 2026-10-16 source build of `server_main.kotoba` used the pinned Amu commit
`8435eafb7dada2d3a85cee2c278ca6d38deb7588` and its lockfile-matched Nbb
`1.5.211`. The emitted local WASM SHA-256 is
`c885fa5de378aafb7080da184ce22ded3918412dc1b385bdc80fc3613fd89de0`; Amu
`verify-output-set` accepted the artifact identity, sealed provenance, and
WebAssembly validation. A loopback-only host readback returned 200 for `/`,
`/awai-launcher.js`, and `/health`. `/api/awai/app-registry` returned 503
`unconfigured`, as expected without a configured local Awai peer. The local
artifact is not publisher-authenticated, deployed, or reachable through
`itonami.app`; this earlier source snapshot offered an install-plan preview
only. The latest local source adds a localhost-only consent/install action;
launch remains unavailable. The active host also has read-only
`GET /api/awai/domain-proof?domain=...`, gated on
an exact candidate from the envelope-verified local registry. A loopback
readback for `murakumo.cloud` returned 503 `registry-unavailable` with no peer
configured, so it did not begin DNS resolution. The active host also exposes a
same-origin `POST /api/awai/install-plan?domain=...` preview route. Without a
peer it stopped at 503 `registry-unavailable`. With an isolated fixture peer,
Itonami independently verified eight signed entries and returned the
`murakumo.cloud` candidate; proof and plan requests then stopped at 409
`dnssec-not-secure`, and an unlisted hostname stopped at 404 before DNS. No
manifest was fetched. This is fixture evidence, not a production release. The
native helper separately passed a signed public TXT validation smoke; no Awai
binding is published for the migration domains, and their DNSSEC delegation is
not enabled.

A subsequent local source pass added `/api/awai/install`, with unchecked capability
choices in the launcher and local install-record commit/readback before the
token-protected Awai cell bridge. Its guest WASM SHA-256 is
`baf6cb0c18311452f36505acc369a8b2e56bc46635eb63a7eca89c5c689eeaca`; Amu
`verify-output-set` passed. The active Node host loaded and returned `/health`
200. A valid install request with no peer returned 503 `registry-unavailable`,
and foreign Origin/Host requests returned 403; no install or cell transfer was
attempted. This is localhost source evidence, not a production install or
deployment. The active host now has a loopback-only static launch path for an
already installed bundle. A dummy local install verified app-origin routing and
CID-backed asset serving; no public release or Awai-installed app has passed
through it. The path has no host-effect bridge and is not public deployment
evidence.

The Pages API lists 16 projects, each with its `pages.dev` hostname. The
2026-10-16 paginated read returned 10 projects on page 1 and 6 on page 2; all
16 report a latest deployment record with a successful deploy stage. Seven
projects map to the named product or Itonami services: `cloud-itonami`
(`itonami.cloud`, `cloud-itonami.itonami.cloud`, and `bots.kotoba.cloud`),
`net-babiniku` (`babiniku.net`), `network-isekai` (`isekai.network`), plus
`itonami-agent-treasury-tokyo2026`, `cloud-itonami-partners`,
`cloud-itonami-isic-6492`, and `cloud-itonami-commitment-ledger` on their
project hostnames. Their latest production deployments reported `success` in
the API. The preview deployment API returned 102 preview records across those
seven projects (17, 37, 46, 0, 0, 1, and 1 respectively); several previews
have branch aliases such as `head.<project>.pages.dev`. This records the
provider's URLs and deployment state only. Public access controls and external
HTTP readback still need verification.

The account API's exact production hostnames are:

| Pages project | Provider hostname | Custom domains |
|---|---|---|
| `cloud-itonami` | `cloud-itonami.pages.dev` | `itonami.cloud`, `cloud-itonami.itonami.cloud`, `bots.kotoba.cloud` |
| `itonami-agent-treasury-tokyo2026` | `itonami-agent-treasury-tokyo2026.pages.dev` | — |
| `cloud-itonami-partners` | `cloud-itonami-partners.pages.dev` | — |
| `cloud-itonami-isic-6492` | `cloud-itonami-isic-6492.pages.dev` | — |
| `cloud-itonami-commitment-ledger` | `cloud-itonami-commitment-ledger.pages.dev` | — |
| `net-babiniku` | `net-babiniku.pages.dev` | `babiniku.net` |
| `network-isekai` | `network-isekai.pages.dev` | `isekai.network` |
| `torihiki` | `torihiki.pages.dev` | — |
| `cyber-drill-merble` | `cyber-drill-merble.pages.dev` | — |
| `store-36b48539` | `store-36b48539.pages.dev` | — |
| `org-spirit-in-physics-comics` | `org-spirit-in-physics-comics.pages.dev` | `comics.spirit-in-physics.org` |
| `gorigori-ketsu-dasshu` | `gorigori-ketsu-dasshu.pages.dev` | `gorigori-ketsu-dasshu.gftd.ai` |
| `cyber-drill` | `cyber-drill.pages.dev` | — |
| `spirit-in-physics-researcher` | `spirit-in-physics-researcher.pages.dev` | — |
| `spirit-in-physics-web` | `spirit-in-physics-web.pages.dev` | — |
| `deps-gftd-ai` | `deps-gftd-ai.pages.dev` | — |

The DNS and Worker-domain refresh found additional cross-zone aliases outside
the product apex zones. Each needs an explicit keep-as-alias, migrate, or
retire decision with the product owner:

| Hosting zone | Public service aliases or routes observed | Migration note |
|---|---|---|
| `etzhayyim.com` | `agent-origin.itonami.cloud.etzhayyim.com`, `isic-5820.itonami.cloud.etzhayyim.com`, `itonami-5820.etzhayyim.com`, `itonami-agent.etzhayyim.com`, `itonami-mcp.etzhayyim.com`, `mcp.itonami.cloud.etzhayyim.com`, `origin-generation.murakumo.cloud.etzhayyim.com`, `atproto.etzhayyim.com`, and `pds.etzhayyim.com`. DNS CNAMEs point to Tunnel targets; only some hostnames appear in current tunnel ingress configurations. The account also has root and `www` Worker routes. | These are alternate names for Itonami, Murakumo, and AT Protocol services. Moving the requested product apex domains alone would leave these DNS routes intact. Identify missing ingress mappings and preserve mail. |
| `gftd.ai` | Wildcard `*.gftd.ai`; Murakumo-related records include `comfyui-fleet.gftd.ai`, `comfyui-gad.gftd.ai`, `gemma-fleet.gftd.ai`, `gemma-gad.gftd.ai`, `generation.murakumo.cloud.gftd.ai`, `relay.murakumo.cloud.gftd.ai`, `voice.murakumo.cloud.gftd.ai`, `murakumo-dashboard.gftd.ai`, `murakumo-serve.gftd.ai`, `murakumo-levi.gftd.ai`, `murakumo-naphtali.gftd.ai`, `murakumo-simeon.gftd.ai`, and `shinshi-comfy.gftd.ai`. Worker routes include `genko`, `daw`, `nle`, `manga`, `mangaka`, and other GFTD app names. | The wildcard plus Tunnel hostnames expose more than the requested `murakumo.cloud` and `shinshi.club` names. Determine which are Murakumo execution adapters, app aliases, or separate GFTD services before cutover. |
| `itonami.app` | `arb.ossekai.itonami.app` is a Worker custom domain (`ossekai-arb-intake`); `kaisya.itonami.app/*` routes to `cloud-itonami-kaisya`. A proxied wildcard record also remains. | These are common-launcher-domain surfaces and must be included in the `itonami.app` bootstrap migration. |
| `kotoba.cloud` | The `cloud-itonami` Pages project also serves `bots.kotoba.cloud`; Worker domains expose `app`, `apps`, `auth`, `blog`, `console`, `data`, `docs`, `graph`, `knowledge`, `kyber`, `storage`, `support`, `twin`, `www`, and `yabai`. | Resolve service ownership and shared-auth/KMS dependencies; preserve them or explicitly retire them with public readback. |
| `gftd.ai` / Pages | `gorigori-ketsu-dasshu.gftd.ai` points to the `gorigori-ketsu-dasshu` Pages project and its `pages.dev` hostname. | Product classification is not yet established. |

The 2026-10-16 Tunnel configuration read covered all 30 tunnels. It reports
`comfyui-gad` healthy for `gateway.murakumo.cloud` and `infer.murakumo.cloud`,
and `gftd-llm` healthy for `ollama.gftd.ai`, `llm.gftd.ai`, `relay.gftd.ai`,
`murakumo-voice.gftd.ai`, and `gateway.gftd.ai`. `itonami-5820` is down but
still names `gateway.itonami.cloud`, `mcp.itonami.cloud`,
`itonami-mcp.etzhayyim.com`, `mcp.itonami.cloud.etzhayyim.com`, and
`itonami-5820.etzhayyim.com`; the last hostname is a nested alternate domain
that needs owner classification. `nexus-wallet-25mbair` is down with
`signer.x402.nexus`. `murakumo-fleet` and `yukkuri-gemma-gad` are also down
while their Murakumo/GFTD ingress names remain configured. A `down` Tunnel
status is not evidence that a hostname is safe to omit or retire.

The `etzhayyim.com` DNS zone still has proxied CNAMEs for
`agent-origin.itonami.cloud.etzhayyim.com`,
`isic-5820.itonami.cloud.etzhayyim.com`, and
`origin-generation.murakumo.cloud.etzhayyim.com`, but none appears in the
30 current Tunnel ingress configurations. Preserve these as unresolved DNS
routes until the target origin is identified. The `mcp.itonami.cloud` hostname
also appears both as an enabled Worker custom domain and in the down
`itonami-5820` Tunnel configuration.

The Cloudflare API snapshot does not establish which non-Cloudflare providers,
registrars, public authoritative nameservers, unproxied origins, public HTTP
routes, or mail paths are active. It also does not settle the ownership of all
`gftd.ai`, `etzhayyim.com`, and remaining account projects. The external
inventory therefore remains open until those sources and public readbacks are
reconciled.

### Additional external app surfaces found in the account refresh

The 2026-10-16 read-only refresh mapped these six additional `cloud-kotoba`
zones to serving resources. These are app/service candidates for the common
Itonami/Awai lifecycle; their local product contracts and owners still need
source review. The `fund-mithril` account is included in the same external app
scope, with its product-specific state and auth requirements kept inside the
Mithril app boundary.

| Zone | Serving resources observed | Other service state |
|---|---|---|
| `kotoba.foundation` | Worker custom domain `kotoba.foundation` → `kotoba-foundation-commons` (enabled, production). | DNSSEC disabled. Preserve Commons content and API behavior. |
| `kotobalabs.com` | Worker custom domains apex and `www` → `kotoba-labs-site` (enabled, production). | Google Workspace MX/SPF/DMARC records are separate mail service. |
| `kotoba-lang.org` | Worker custom domains apex and `www` → `kotoba-lang-org` (enabled, production). | Amazon SES inbound/outbound MX and SPF/DKIM/DMARC records are separate mail service. |
| `manimani.cloud` | Worker custom domain apex → `cloud-manimani` (enabled, production). | DNSSEC disabled. |
| `spirit-in-physics.org` | Worker custom domains apex and `www` → `spirit-in-physics-web`; `researcher` → `spirit-in-physics-researcher`; API routes on all three hosts → `spirit-in-physics-api`; `comics` → Pages project `org-spirit-in-physics-comics`. | Pages custom-domain API, DNS, Worker-domain API, and zone routes all agree on these aliases. DNSSEC disabled. |
| `yaoi.dev` | Worker custom domains apex and `www` → `dev-yaoi` (enabled, production). | DNSSEC disabled. |
| `mithril.fund` | 23 Worker custom domains: apex, `www`, `admin`, `analysis`, `api`, `app`, `auth`, `blog`, `bounty`, `code`, `console`, `docs`, `dojo`, `graph`, `hikari`, `knowledge`, `kyber`, `models`, `storage`, `support`, `talk`, `twin`, and `yabai`. | The account has 36 Worker scripts, 28 with `workers.dev` enabled under `cloud-kotoba.workers.dev` and 26 with preview URLs enabled. Preserve login/session handling, finance data and all bound stores as Mithril app contracts; do not interpret DNS migration as a data migration. |

The same refresh found five active `cloud-kotoba` zones with no DNS records,
Worker custom domains, zone routes, Pages custom domains, or Tunnel ingress
hostnames in the connected account: `etzhadaat.org`, `kotoba.digital`,
`kotoba-labs.com`, `nubatama.net`, and `yatalake.com`. `mithril.love` and
`mythril.build` have the same empty connected-account result. This does not
prove that these domains are undelegated or unused; registrar records,
external DNS providers, and direct public readbacks remain outstanding.

The 28 enabled Mithril `workers.dev` services use the account prefix
`cloud-kotoba`, so their hostnames are `<script>.cloud-kotoba.workers.dev`.
They are `fund-mithril-admin`,
`fund-mithril-apex`, `fund-mithril-api`, `fund-mithril-auth`,
`fund-mithril-blog`, `fund-mithril-docs`, `fund-mithril-graph`,
`fund-mithril-knowledge`, `fund-mithril-storage`, `fund-mithril-support`,
`fund-mithril-twin`, `fund-mithril-www`, `fund-mithril-yabai`,
`mithril-admin`, `mithril-admin-staging`, `mithril-api`,
`mithril-api-staging`, `mithril-auth`, `mithril-auth-staging`,
`mithril-hermes-gateway-trial`, `mithril-hermes-runtime`,
`mithril-knowledge-fund-preview`, `mithril-move-smoke-apex`,
`mithril-native-shared-trial`, `mithril-web`,
`mithril-web-release`, `mithril-web-staging`, and
`mithril-vault-training-20261011`. Preview URLs are enabled for 26 scripts:
the 13 `fund-mithril-*` names above, plus
`mithril-admin`, `mithril-admin-staging`, `mithril-api`,
`mithril-api-staging`, `mithril-auth`, `mithril-auth-staging`,
`mithril-hermes-gateway-trial`, `mithril-hermes-runtime`,
`mithril-move-smoke-apex`, `mithril-native-shared-trial`, `mithril-web`,
`mithril-web-release`, and `mithril-web-staging`. Preview deployment URLs
remain separate public hostnames and still need source mapping and readback.
The current per-script subdomain read confirms that
`mithril-knowledge-fund-preview` and `mithril-vault-training-20261011` have
`workers.dev` enabled and preview URLs disabled.

The Worker API also exposes `spirit-in-physics.com`,
`www.spirit-in-physics.com`, and `researcher.spirit-in-physics.com` without a
corresponding zone in the connected accounts; their registrar and authoritative
DNS are outside this snapshot. The five empty `cloud-kotoba` zones and two
empty `fund-mithril` zones stay in scope until external delegation and actual
serving state are checked. Zone/account membership does not establish product
ownership or public availability.

## Public recursive DNS and delegation refresh (2026-10-16)

On 2026-10-16 JST, Google Public DNS-over-HTTPS
returned Cloudflare nameservers `everton.ns.cloudflare.com` and
`vivienne.ns.cloudflare.com` for all ten migration-cohort domains. The parent
DS lookup returned no DS RRset for any of the ten. Its `AD` flag was true for
the authenticated negative DS answers for `itonami.app`, `aozora.app`, and
`x402.nexus`; it was false for the other seven. Separately, a fresh read-only
Cloudflare API query returned `DNSSEC: disabled` for all ten active zones.
There is therefore no signed delegation for the proposed Awai DNS proof.

The same recursive lookup returned Cloudflare anycast A and AAAA answers for
eight apexes. `itonami.app` and `oppai.fans` returned no apex A or AAAA answer.
These are public recursive results, not direct authoritative-server queries.
The linked DNS-over-HTTPS responses are the raw readback for each name.

| Domain | Parent NS | Parent DS | Apex A/AAAA | Readback |
|---|---|---|---|---|
| `itonami.app` | Cloudflare | none (`AD=true`) | none | [NS](https://dns.google/resolve?name=itonami.app&type=NS), [DS](https://dns.google/resolve?name=itonami.app&type=DS), [A](https://dns.google/resolve?name=itonami.app&type=A), [AAAA](https://dns.google/resolve?name=itonami.app&type=AAAA) |
| `murakumo.cloud` | Cloudflare | none (`AD=false`) | Cloudflare anycast | [NS](https://dns.google/resolve?name=murakumo.cloud&type=NS), [DS](https://dns.google/resolve?name=murakumo.cloud&type=DS), [A](https://dns.google/resolve?name=murakumo.cloud&type=A), [AAAA](https://dns.google/resolve?name=murakumo.cloud&type=AAAA) |
| `aozora.app` | Cloudflare | none (`AD=true`) | Cloudflare anycast | [NS](https://dns.google/resolve?name=aozora.app&type=NS), [DS](https://dns.google/resolve?name=aozora.app&type=DS), [A](https://dns.google/resolve?name=aozora.app&type=A), [AAAA](https://dns.google/resolve?name=aozora.app&type=AAAA) |
| `oppai.fans` | Cloudflare | none (`AD=false`) | none | [NS](https://dns.google/resolve?name=oppai.fans&type=NS), [DS](https://dns.google/resolve?name=oppai.fans&type=DS), [A](https://dns.google/resolve?name=oppai.fans&type=A), [AAAA](https://dns.google/resolve?name=oppai.fans&type=AAAA) |
| `isekai.network` | Cloudflare | none (`AD=false`) | Cloudflare anycast | [NS](https://dns.google/resolve?name=isekai.network&type=NS), [DS](https://dns.google/resolve?name=isekai.network&type=DS), [A](https://dns.google/resolve?name=isekai.network&type=A), [AAAA](https://dns.google/resolve?name=isekai.network&type=AAAA) |
| `itonami.cloud` | Cloudflare | none (`AD=false`) | Cloudflare anycast | [NS](https://dns.google/resolve?name=itonami.cloud&type=NS), [DS](https://dns.google/resolve?name=itonami.cloud&type=DS), [A](https://dns.google/resolve?name=itonami.cloud&type=A), [AAAA](https://dns.google/resolve?name=itonami.cloud&type=AAAA) |
| `x402.nexus` | Cloudflare | none (`AD=true`) | Cloudflare anycast | [NS](https://dns.google/resolve?name=x402.nexus&type=NS), [DS](https://dns.google/resolve?name=x402.nexus&type=DS), [A](https://dns.google/resolve?name=x402.nexus&type=A), [AAAA](https://dns.google/resolve?name=x402.nexus&type=AAAA) |
| `shinshi.club` | Cloudflare | none (`AD=false`) | Cloudflare anycast | [NS](https://dns.google/resolve?name=shinshi.club&type=NS), [DS](https://dns.google/resolve?name=shinshi.club&type=DS), [A](https://dns.google/resolve?name=shinshi.club&type=A), [AAAA](https://dns.google/resolve?name=shinshi.club&type=AAAA) |
| `babiniku.net` | Cloudflare | none (`AD=false`) | Cloudflare anycast | [NS](https://dns.google/resolve?name=babiniku.net&type=NS), [DS](https://dns.google/resolve?name=babiniku.net&type=DS), [A](https://dns.google/resolve?name=babiniku.net&type=A), [AAAA](https://dns.google/resolve?name=babiniku.net&type=AAAA) |
| `awai.network` | Cloudflare | none (`AD=false`) | Cloudflare anycast | [NS](https://dns.google/resolve?name=awai.network&type=NS), [DS](https://dns.google/resolve?name=awai.network&type=DS), [A](https://dns.google/resolve?name=awai.network&type=A), [AAAA](https://dns.google/resolve?name=awai.network&type=AAAA) |

### Registrar dependency and independent-DNS path

A read-only `cloud-kotoba` Registrar API query on 2026-10-16 found all ten
migration-cohort domains in that account's registrations. Every one is
currently registrar-locked, has auto-renew enabled, and uses
`everton.ns.cloudflare.com` / `vivienne.ns.cloudflare.com`. The API returned
the following expiry dates:

| Domain | Expires (UTC) | Auto-renew | Registrar lock |
|---|---|---:|---:|
| `murakumo.cloud` | 2027-06-22 | on | locked |
| `aozora.app` | 2027-06-20 | on | locked |
| `oppai.fans` | 2027-09-03 | on | locked |
| `isekai.network` | 2027-06-21 | on | locked |
| `itonami.cloud` | 2027-06-27 | on | locked |
| `x402.nexus` | 2027-07-09 | on | locked |
| `shinshi.club` | 2027-05-31 | on | locked |
| `babiniku.net` | 2027-06-21 | on | locked |
| `itonami.app` | 2027-08-14 | on | locked |
| `awai.network` | 2027-07-20 | on | locked |

Cloudflare Registrar does not allow third-party authoritative nameservers; its
registered domains must use Cloudflare nameservers. If the final requirement is
to remove Cloudflare DNS as well as Cloudflare application runtime, each domain
must be transferred to a registrar that permits external nameservers. Cloudflare
documents the transfer-out sequence as unlock the domain, obtain its EPP/auth
code, then initiate and approve the transfer at the gaining registrar. The
current `locked` state means no transfer has started. See the official
[Registrar FAQ](https://developers.cloudflare.com/registrar/faq/#can-i-use-my-own-third-party-nameservers)
and [transfer-out procedure](https://developers.cloudflare.com/registrar/account-options/transfer-out-from-cloudflare/).

Keep DNS/registrar movement staged separately from app-runtime replacement:

1. Reconcile every DNS record, mail flow, alias, and service dependency; publish
   the same zone at the chosen independent DNS provider and verify its answers
   before changing delegation.
2. For one approved pilot domain, arrange the gaining registrar, unlock and
   transfer the registration, then verify registrar status, public NS/DS/A/AAAA,
   HTTPS, and mail delivery from independent resolvers.
3. Remove Workers, Pages, Tunnel, and provider storage dependencies only after
   the replacement app has passed end-to-end reads/writes and rollback checks.
   Repeat by domain while preserving each product's state and service identity.

If the requirement is limited to **no Cloudflare application runtime**, the
registration and authoritative DNS can remain temporarily while DNS-only
records point to independently operated Awai/Itonami origins. That would remove
Workers/Pages/Tunnel from the serving path but would leave Cloudflare as the
registrar/DNS control plane; record it as such rather than calling it fully
provider-independent.

### Additional domain and route leads

`com-junkawasaki/root/manifest/decentralized-services.edn` contains older
2026-09-26–28 service observations outside the ten-domain cohort. Current
recursive DNS resolves `aozora.cloud` to four AWS Route 53 nameservers but no
apex A/AAAA; its relation to `aozora.app` remains unsettled. The same lookup
returns NXDOMAIN for `dns-lab.itonami.cloud`, so the older DNS-lab pilot is not
currently a public route. `yataverse.com` is delegated to Cloudflare and has no
apex A/AAAA answer, while the Cloudflare API still exposes
`wiki.yataverse.com` on Worker `app-hyakka`; classify it separately from the
eight requested product domains before claiming an exhaustive scope.

The older registry also names `main2.ezpdpz.net`, which currently resolves to
`109.150.215.145` with no AAAA answer. It is an external infrastructure host,
not a canonical product domain. `itonami-static.220-146-170-114.sslip.io` and
`isekai-static.220-146-170-114.sslip.io` currently resolve to
`220.146.170.114`, but HTTPS requests to ports 8443 and 8445 timed out for both
hostnames; neither is a currently verified serving fallback. These raw
addresses remain provider/operator leads rather than qualified replacement
gateways.

The service registry contains 14 unique Tor v3 `.onion` addresses for directory,
Itonami, Isekai, Murakumo, and Yataverse routes. They were recorded in
September; this refresh had no Tor transport, so all 14 remain unverified. The
list is: `24yqzkezkmhfphlqfj57mdwtzjz4k5yzyzeet2vgzbdzn5b2tbuyqpad.onion`,
`4gh64mr4aqkpypzglkcx4qf4sa2eblhv3gb3iahy3wwor3zm2y5rxgid.onion`,
`a6yr5wnn5ohuq2r3mueuk5fir3cyp5nkaglsxlofehrfbghyd6fljrad.onion`,
`chhaopxri2o6pa2luntofrvudowf327xhofpezuxlom6bapl6fbaobad.onion`,
`imscab6umbxgdppd3ykbk5gkplrtbymnb6psge2dxwhrxrnjdsgctsqd.onion`,
`kkswxfaqssibpqd2p4zw256zz6pcm3dgrbq67bkdk4ttlkodoppgavyd.onion`,
`luq6z3vnfpptryllve732ze64gkywj3xx2onrpm6zo22yso3ilsojrqd.onion`,
`moiisqmkkfkw32gtbleer2wms23v2bckbumfc7rdzmg3pi4y6zcvgtid.onion`,
`oe5if2x7wdgexm4ol7ksblnjxntgemob453pwklt3b2isnpw5au7p5yd.onion`,
`okm3eu6dxrztg7f3dy3scp3mxqb45k6k7wjpnab3bc4l7ejpjinyxnad.onion`,
`rywlb4tst5t66tfrqfogf4ix7ep7qtjez6jbtdrq33iqxl27uld4uoad.onion`,
`u2j4e4wrdzgnfmpwcegcsp5ibdt3lj5rjxp6yixicjt2afcexypivbid.onion`,
`vircr276k27dwxugqmj6ah6pk4uxu2rzawvrpaeilgzithixiak6cfyd.onion`, and
`ztypxopkm4ym7g4vk2gz5jba466z2t242d5lr5zef3z5fjzlwccdetid.onion`.
The source registry and its service mapping remain historical leads, not live
availability evidence.

## Registrar portability refresh (2026-10-16)

The connected `cloud-kotoba` Registrar API returned 26 registrations. All ten
initial-cohort domains are registered through Cloudflare, have `locked: true`
and `auto_renew: true`, and use `everton.ns.cloudflare.com` and
`vivienne.ns.cloudflare.com`. The API's transfer-condition fields currently
pass `supported_tld`, `not_secure`, `not_started`, and `not_premium` for all
ten. An earlier read reported `not_waiting=false` for `oppai.fans`; the latest
Registrar refresh did not revalidate that field, so transfer eligibility and
the waiting-period status remain unconfirmed. This is account-side metadata,
not authorization to unlock or transfer a domain. No auth code was requested
or read, and no transfer was started.

A 07:00 UTC read of all ten exact Registrar domain records re-confirmed
`current_registrar=Cloudflare`, `locked=true`, empty DS records, no pending
transfer, and the same Cloudflare nameservers. The latest account-level
Registrar refresh likewise returned all listed registrations locked with no
pending transfer and no DS records. Transfer-condition eligibility is not
established by those fields and must be checked again before any transfer. The
matching Cloudflare zone API read reports DNSSEC disabled on all ten zones. No
registrar, DNS, Worker, Pages, or Tunnel setting was changed.

Namecheap is one technically compatible gaining-registrar candidate: its
published transfer list includes all seven cohort TLDs (`.app`, `.cloud`,
`.fans`, `.network`, `.nexus`, `.club`, and `.net`), and it documents DS-record
management for domains using custom nameservers. This establishes a candidate,
not a selection or price approval; each exact domain, transfer price, renewal
price, and external DNS operator still needs a checkout-time check. See its
[transfer list](https://www.namecheap.com/domains/transfer/) and
[custom-DNS DNSSEC procedure](https://www.namecheap.com/support/knowledgebase/article.aspx/9722/2232/managing-dnssec-for-domains-pointed-to-custom-dns/).

IANA-bootstrap RDAP responses identify Cloudflare, Inc. as registrar for all
ten migration-cohort domains. Every response lists
`clientTransferProhibited`; `oppai.fans` also lists
`serverTransferProhibited`. The registrations use the Cloudflare nameservers
shown in the DNS readback above. Cloudflare Registrar does not allow
third-party authoritative nameservers; its documented path to a different DNS
provider is transfer to another registrar. See [Cloudflare's transfer-out
steps](https://developers.cloudflare.com/registrar/account-options/transfer-out-from-cloudflare/)
and [nameserver FAQ](https://developers.cloudflare.com/registrar/faq/).

| Domain | Registered (RDAP) | Last changed (RDAP) | Transfer lock | RDAP |
|---|---|---|---|---|
| `itonami.app` | 2026-08-14 | 2026-08-19 | client | [RDAP](https://pubapi.registry.google/rdap/domain/itonami.app) |
| `murakumo.cloud` | 2026-06-22 | 2026-06-27 | client | [RDAP](https://rdap.registry.cloud/rdap/domain/murakumo.cloud) |
| `aozora.app` | 2026-06-20 | 2026-06-25 | client | [RDAP](https://pubapi.registry.google/rdap/domain/aozora.app) |
| `oppai.fans` | 2026-09-03 | 2026-09-08 | server + client | [RDAP](https://rdap.centralnic.com/fans/domain/oppai.fans) |
| `isekai.network` | 2026-06-21 | 2026-08-11 | client | [RDAP](https://rdap.identitydigital.services/rdap/domain/isekai.network) |
| `itonami.cloud` | 2026-06-27 | 2026-07-02 | client | [RDAP](https://rdap.registry.cloud/rdap/domain/itonami.cloud) |
| `x402.nexus` | 2026-07-09 | 2026-07-14 | client | [RDAP](https://pubapi.registry.google/rdap/domain/x402.nexus) |
| `shinshi.club` | 2026-05-31 | 2026-07-30 | client | [RDAP](https://rdap.nic.club/domain/shinshi.club) |
| `babiniku.net` | 2026-06-21 | 2026-06-21 | client | [RDAP](https://rdap.verisign.com/net/v1/domain/babiniku.net) |
| `awai.network` | 2026-07-20 | 2026-08-12 | client | [RDAP](https://rdap.identitydigital.services/rdap/domain/awai.network) |

An earlier Registrar API response reported `not_waiting: true` for
`itonami.app` and `not_waiting: false` for `oppai.fans`. The latest account
refresh confirms registration and lock state but did not include a fresh
waiting-period eligibility result. Recheck the exact registrar eligibility
fields and registry rules at execution time. Before any transfer, stage the
full DNS and mail record set at the chosen independent DNS operator, verify TLS
and app routes there, then transfer and read back parent NS/DS, mail, HTTPS,
and application behavior. Registrar selection and the registrant's required
transfer approvals remain open.

## Historical public recursive DNS readback (2026-10-15)

Google Public DNS-over-HTTPS lookups returned these apex A answers with TTL
300. The seven resolved names also have proxied apex records in the Cloudflare
zone API, so the public answers currently terminate at Cloudflare anycast; they
are not direct Awai-node addresses.

| Domain | Public A answer |
|---|---|
| `murakumo.cloud` | `104.21.1.204`, `172.67.129.232` |
| `aozora.app` | `104.21.87.101`, `172.67.169.53` |
| `isekai.network` | `172.67.150.187`, `104.21.40.108` |
| `itonami.cloud` | `172.67.180.116`, `104.21.91.211` |
| `x402.nexus` | `104.21.2.241`, `172.67.129.212` |
| `shinshi.club` | `172.67.169.123`, `104.21.71.49` |
| `babiniku.net` | `104.21.82.240`, `172.67.209.75` |
| `awai.network` | `172.67.190.213`, `104.21.10.202` |

The apex A lookups for [`itonami.app`](https://dns.google/resolve?name=itonami.app&type=A&do=1)
and [`oppai.fans`](https://dns.google/resolve?name=oppai.fans&type=A&do=1)
returned no A answer (SOA only). A separate
[`oppai.fans` NS lookup](https://dns.google/resolve?name=oppai.fans&type=NS&do=1)
returned `everton.ns.cloudflare.com` and `vivienne.ns.cloudflare.com`. Thus
the connected account's active `oppai.fans` zone is delegated publicly but its
apex currently has no A answer; the `itonami.app` wildcard does not make the
apex resolvable. These Google resolver responses had `AD=false`; they do not
prove DNSSEC validation or reveal registrar ownership. The checks are recursive
public readbacks, not direct queries to the authoritative servers.

## Public browser readback (2026-10-16)

The in-app browser resolved and rendered the current sites at
`murakumo.cloud`, `aozora.app`, `isekai.network`, `itonami.cloud`,
`x402.nexus`, `shinshi.club`, `babiniku.net`, and `awai.network`. These are
current public-page observations through their existing serving paths; they do
not establish independent hosting or successful Awai app retrieval. A current
public HTTPS GET readback also returned HTTP 200 for all eight apexes; their
responses used Cloudflare anycast addresses.

The browser returned `ERR_NAME_NOT_RESOLVED` for both `https://itonami.app/`
and `https://oppai.fans/`. The first result means the intended app-plane
bootstrap domain cannot currently be reached through this browser's resolver;
the second matches the earlier empty-zone readback. These checks do not audit
registrar state or authoritative DNS. Keep both as bootstrap/publication gates
before any domain cutover.

## Historical public browser readback (2026-10-15)

The in-app browser loaded both `https://awai.network/` and
`https://www.awai.network/`; both rendered the same AWAI Network site. The
public `https://awai.network/api/health` response identified
`network-awai-apex`, routed chat and video requests through Murakumo, and
reported `douga.configured=false`. These are public HTTP observations at the
time of the check; they do not qualify independent hosting or app-runtime
readiness.

The browser returned `ERR_NAME_NOT_RESOLVED` for `https://oppai.fans/`. The
connected Cloudflare account also has no DNS records or public route for that
zone, while its source repository describes a Worker + SPA. This is evidence
that the browser could not resolve the hostname during this check, not a
registrar or authoritative-DNS audit. The external delegation and actual
serving origin still need to be identified before assigning a migration path.

The public `https://ipfs-pilot.murakumo.cloud/` page rendered the Murakumo chat
application and linked login to `auth.kotoba.cloud`. A separate public Google
DNS-over-HTTPS lookup returned the `_dnslink.ipfs-pilot.murakumo.cloud` TXT as
`dnslink=/ipns/k51qzi5uqu5dlux8z4nuviwt8ww812ujkb4v1sxm91d630bjrbhsjeftp7t2iu`
with TTL 300 and `AD=false`. The Cloudflare API confirms the same TXT and an
enabled Worker custom domain for `ipfs-pilot.murakumo.cloud`. This proves an
IPNS pointer exists, but does not prove that the chat application's delivered
bytes come from that pointer or that the public hostname works without
Cloudflare. The current `cloud-murakumo` source describes its IPNS/IPFS route
as resolution through `delegated-ipfs.dev` followed by retrieval from public
HTTP gateways, without Murakumo pinning or storing the object; that path also
is not the Awai app registry or a Cloudflare-independent domain deployment.

The 2026-10-16 DNSSEC endpoint refresh covered all 30 currently listed active
zones: all 27 zones in `cloud-kotoba` and all 3 zones in `fund-mithril` report
DNSSEC `disabled`. This includes every migration-cohort zone. This is
Cloudflare control-plane metadata. The current public recursive DNS-over-HTTPS
readback independently confirms that the ten migration domains still delegate
to Cloudflare and have no parent DS RRset; it is not a direct authoritative
query. As currently configured, the proposed `_itonami-app.<domain>` DNSSEC
proof cannot be accepted until signed delegation is established at the chosen
DNS operator.

## Migration order and acceptance gates

1. **Finish and qualify the common host.** The merged protocol and Itonami
   runtime provide signed app identity, DNSSEC candidate checks, CID-verified
   content staging, explicit local install consent, sandboxed launch, and
   capability-mediated Awai cell access. Follow-up source adds exact release
   pins and signed v4 registry checkpoints; Itonami requires a fresh
   `ceil(2N/3)` quorum across 3–12 configured peer IDs. Loopback HTTP is for
   local checks; remote peer endpoints use public-host HTTPS. The gateway
   package is still bootstrap-only and has no production host, certificate, or
   direct-DNS name.

   Local evidence includes all 16 Awai `app-registry --host` gates, 96/96
   validator vectors, two-process checkpoint replication, 20 Itonami tests / 77
   assertions, two checkpoint-verifier tests, and the gateway's nine policy
   tests plus a loopback TLS smoke. Those checks use fixtures and same-host
   processes. The JVM bridge has not been exercised end to end, and no
   independently operated Awai peer or signed production release is connected.

   Before any production hostname uses the host, deploy at least three Awai
   peers under distinct operators, expose their read-only registry API through
   direct HTTPS endpoints with no CDN/runtime proxy, and verify peer IDs,
   operator ownership, fresh quorum, one stale/unavailable peer, and recovery
   when a node is removed. Then run a non-fixture signed pilot release through
   a qualified Amano quorum with challenge-bound current-ref reads and exact
   event finality proofs, DNSSEC proof, three independent content origins, CID
   verification, consent, install, sandboxed launch, cell reads/writes,
   revocation, update, rollback, and offline recovery. The current operator
   host currently cannot reach any of the four witness endpoints and has no
   working local Tailscale daemon, so Amano quorum remains unverified and no
   production app may be anchored yet. Package the DNSSEC validator and reviewed root-anchor
   refresh for the deployment platform; add export/import receipts for D1/KV/R2
   state and service bindings before replacing those applications. Missing
   adapters fail closed; there is no Cloudflare fallback.
2. **Qualify `itonami.app` itself.** Publish the signed launcher and registry
   client to at least three independently operated content origins and verify
   state writes through at least two independent Awai nodes. Verify direct
   public DNS, TLS, bundle/CID retrieval, and continuity while one node is
   offline. Move its wildcard and DNS authority only after this succeeds.
3. **Run the `murakumo.cloud` pilot.** First stage on a dedicated hostname,
   compare the old and new service responses, then migrate web and API
   hostnames independently. Keep inference/generation, mail, and device
   registration contracts observable during the transition.
4. **Migrate the other product domains one at a time.** Bind each hostname to a
   signed release, verify its app-specific protocol and state from independent
   nodes, then switch direct DNS. Keep mail records and unrelated subdomains
   out of each app cutover.
5. **Retire Cloudflare serving only after proof.** For every hostname, verify
   external DNS answers, TLS, HTTP/API behavior, capabilities, mail flow,
   failover with a node removed, and rollback. Include custom domains, routes,
   `workers.dev`, `pages.dev`, Pages preview deployments, Worker preview and
   version URLs, Tunnel origins, and internal service bindings in the endpoint
   inventory. Then remove its Cloudflare runtime dependency and confirm that
   repeat reads and writes still succeed from public clients.

No domain in this inventory has been cut over. The snapshot is not proof that
all externally published Itonami/Awai services have been found; registrar,
public DNS, mail, non-Cloudflare origins, and the other zones remain to be
reconciled.

## Focused Worker public-route refresh (2026-10-16T03:18:34Z)

A fresh read-only Cloudflare API pass correlated app-named custom domains,
zone routes, and related service names across the connected account, then
checked each associated Worker's `workers.dev` settings. It found 41 correlated
Worker custom domains, 19 correlated route patterns, and 64 unique Worker
scripts. Of those scripts, 28 currently have `workers.dev` enabled; all 28
also have preview URLs enabled. The production URLs use the
`04-feasts-minded.workers.dev` account subdomain. This is a focused
product-and-route subset, not an ownership determination for every matching
service.

| Service group | `workers.dev` hostnames currently enabled |
|---|---|
| Aozora | `app-aozora-appview`, `app-aozora-auth`, `app-aozora-live`, `app-aozora-pds`, `app-aozora-spa` |
| Itonami app, marketplace, and pricing services | `cloud-itonami-app-classifieds-production`, `cloud-itonami-app-edge`, `cloud-itonami-domain-reverify`, `cloud-itonami-mail-inbound`, `cloud-itonami-marketplace-buyer`, `cloud-itonami-marketplace-crossborder`, `cloud-itonami-marketplace-fulfillment`, `cloud-itonami-marketplace-listing`, `cloud-itonami-marketplace-onboarding`, `cloud-itonami-marketplace-order`, `cloud-itonami-marketplace-returns`, `cloud-itonami-marketplace-settlement`, `cloud-itonami-tsukuru`, `cloud-itonami-workspace-outbox`, `itonami-pricing-resident` |
| Related product and route services | `kenbun`, `kotoba-foundation-commons`, `mangaka-reader`, `murakumo-setup`, `ossekai-arb-intake`, `shinshi-cljc`, `torihiki-node`, `torihiki-validator-v3` |

Append `.04-feasts-minded.workers.dev` to each name above for its production
hostname. The enabled preview setting is a separate public route surface and
must be inventoried and retired or replaced alongside custom domains. These
settings are control-plane evidence only; no new preview URL was opened or
tested in this refresh. No Cloudflare setting or public route was changed.

## Registrar dependency for independent authoritative DNS

Cloudflare's current Registrar documentation says domains registered there
must use Cloudflare nameservers; using another authoritative DNS provider
requires transferring the registration to another registrar ([nameserver
policy](https://developers.cloudflare.com/registrar/faq/#can-i-use-my-own-third-party-nameservers),
[transfer-out procedure](https://developers.cloudflare.com/registrar/account-options/transfer-out-from-cloudflare/)).
The connected Registrar API refresh lists all ten initial migration domains
among Cloudflare's 26 registrations. Therefore moving Workers and Pages alone
cannot remove Cloudflare authoritative DNS from this cohort.

The safe route is to select a receiving registrar and independent DNS operator
that supports signed DNSSEC; prepare and verify every web, mail, ownership,
CAA, and Awai-proof record before each transfer; retain the existing Cloudflare
zone during registrar transfer; then set the receiving registrar's delegation
to the independent DNS service and verify public NS, DS, A/AAAA, TLS, and
mail-flow readback before retiring the old zone. The existing Registrar
portability snapshot records transfer locks on the cohort, so unlock/transfer
eligibility must be re-read at execution time. No registrar, nameserver, DNSSEC,
or domain setting was changed in this audit.

### Registrar registration snapshot (2026-10-16)

A read-only `GET /accounts/{account_id}/registrar/registrations` refresh in
`cloud-kotoba` returned all ten initial migration domains as `active`, with
`auto_renew=true`, `locked=true`, and `privacy_mode=redaction`. Their current
registration expirations are:

| Domain | Expires (UTC) |
|---|---|
| `shinshi.club` | 2027-05-31 |
| `aozora.app` | 2027-06-20 |
| `babiniku.net` | 2027-06-21 |
| `isekai.network` | 2027-06-21 |
| `murakumo.cloud` | 2027-06-22 |
| `itonami.cloud` | 2027-06-27 |
| `x402.nexus` | 2027-07-09 |
| `awai.network` | 2027-07-20 |
| `itonami.app` | 2027-08-14 |
| `oppai.fans` | 2027-09-03 |

The expiration dates identify planning windows only; they do not authorize a
transfer or guarantee transfer eligibility. Keep auto-renew enabled until a
receiving registrar confirms the registration and renewal state. No transfer or
unlock operation was attempted.

## Account-wide public hostname and deployment refresh (2026-10-16)

A complete read-only pass across the three connected Cloudflare accounts
returned the following account-level surface. The `workers.dev` and preview
columns come from the per-script subdomain endpoint, not Worker settings that
contain environment bindings.

| Account | Active zones | Worker scripts | Worker custom domains | Zone routes | `workers.dev` / previews | Pages projects / preview deployments | Tunnels |
|---|---:|---:|---:|---:|---:|---:|---:|
| `cloud-kotoba` | 27 | 109 | 78 | 30 | 45 / 43 | 16 / 108 | 30 |
| `fund-mithril` | 3 | 36 | 23 | 6 | 28 / 26 | 0 / 0 | 0 |
| `com-junkawasaki` | 0 | 0 | 0 | 0 | 0 / 0 | 0 / 0 | 0 |
| **Total** | **30** | **145** | **101** | **36** | **73 / 69** | **16 / 108** | **30** |

The 30 `cloud-kotoba` tunnels have 5 healthy and 25 down statuses, with 60
configured ingress rules. The ingress count includes repeated hostnames for
separate rules. Down tunnels remain in the inventory while their DNS or ingress
names exist.

The active zones contain 358 DNS records across 21 zones, with 289 unique
record hostnames. The other nine active zones have no DNS records:
`etzhadaat.org`, `gftd.cloud`, `kotoba.digital`, `kotoba-labs.com`,
`nubatama.net`, `oppai.fans`, `yatalake.com`, `mithril.love`, and
`mythril.build`. The sanitized hostname snapshot is
[`awai-cloudflare-dns-hostnames-2026-10-16.csv`](awai-cloudflare-dns-hostnames-2026-10-16.csv).
It records account, zone, hostname, record type, and Cloudflare proxy setting;
it omits record contents and origin IP addresses. Provider records alone do not
prove that each name currently resolves from public DNS.

The companion
[`awai-cloudflare-runtime-endpoints-2026-10-16.csv`](awai-cloudflare-runtime-endpoints-2026-10-16.csv)
is the preceding export and contains 522 normalized rows: 99 Worker custom domains, 36 Worker route
patterns, 143 `workers.dev` script states, 23 Pages production hostnames, 161
Pages preview URLs and aliases, and 60 Tunnel ingress rules. The `workers.dev`
rows include disabled scripts to show the full checked set. These rows record
provider configuration and deployment presence; they do not prove external
HTTP access or identify an app owner for every service. The latest domain
readback increased the account-wide Worker totals by two scripts and two
custom domains; the expanded mapping below is the current hostname read.

The current Pages preview API returned 108 deployment records across all 16
projects. Each record exposes a hash-based `pages.dev` URL and may also include
a branch alias. The per-project counts are:

| Pages project | Preview deployment records |
|---|---:|
| `net-babiniku` | 37 |
| `network-isekai` | 46 |
| `cloud-itonami` | 17 |
| `itonami-agent-treasury-tokyo2026` | 0 |
| `cyber-drill-merble` | 0 |
| `store-36b48539` | 0 |
| `torihiki` | 4 |
| `cloud-itonami-partners` | 0 |
| `cloud-itonami-isic-6492` | 1 |
| `cloud-itonami-commitment-ledger` | 1 |
| `org-spirit-in-physics-comics` | 0 |
| `gorigori-ketsu-dasshu` | 0 |
| `cyber-drill` | 0 |
| `spirit-in-physics-researcher` | 0 |
| `spirit-in-physics-web` | 0 |
| `deps-gftd-ai` | 2 |

The 102 preview records across the seven previously identified Itonami/Awai
projects are still included in this 108-record total; `torihiki` contributes
four and `deps-gftd-ai` contributes two. This API snapshot inventories the
deployment URLs and aliases; per-URL external access protection and HTTP
readback remain unverified.

## Proposed non-Cloudflare registrar and DNS path

Namecheap's current transfer catalog lists all seven cohort extensions:
`.app`, `.cloud`, `.fans`, `.network`, `.nexus`, `.club`, and `.net` ([transfer
catalog](https://www.namecheap.com/domains/transfer/)). Its DNSSEC interface
supports DS records for domains using custom nameservers ([DNSSEC for custom
DNS](https://www.namecheap.com/support/knowledgebase/article.aspx/9722/2232/managing-dnssec-for-domains-pointed-to-custom-dns/)). Amazon Route 53 can
sign public hosted zones and supports publishing the resulting DS at another
registrar ([Route 53 DNSSEC setup](https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/dns-configuring-dnssec-enable-signing.html)).
Together, Namecheap registration and Route 53 authoritative DNS form a
technically viable candidate path away from Cloudflare Registrar and DNS.
Route 53 would remain a centralized DNS control plane; Awai peers and public
gateways would serve the app runtime directly.

This is a candidate, not a provider selection. The exact transfer price,
eligibility, DNS zone cost, access to the receiving accounts, and record-by-record
parity still need execution-time checks. An earlier read reported
`not_waiting=false` for `oppai.fans`, but the latest Registrar refresh did not
revalidate that condition. Recheck its registrar and registry transfer rules
before scheduling it. No hosted zone was created and no registrar, DNS, TLS,
or runtime setting was changed in this refresh.

## Expanded account-wide public-host inventory (2026-10-16)

The latest read-only account pass returned 109 Worker scripts, 78 Worker
custom domains, 30 Worker route patterns, 16 Pages projects, and 30 Tunnels in
`cloud-kotoba`; `fund-mithril` returned 36 Worker scripts, 23 Worker custom
domains, six routes, and no Pages projects or Tunnels. The exact custom-domain
read confirmed the two hosts absent from the earlier CSV: `local.itonami.cloud`
→ `itonami-client-static` and `local.murakumo.cloud` →
`murakumo-client-static`. All listed Worker custom domains were enabled in
production. Cloudflare configuration is a migration lead, not proof that a
hostname resolves publicly or that Cloudflare is the only serving path.

The DNS record counts and DNSSEC state below are current Cloudflare zone API
readback for all 27 `cloud-kotoba` zones. Every one reports DNSSEC disabled.
The configured Worker and Pages entries need owner, product, external HTTP,
mail, and data-contract reconciliation before they can be mapped to an Awai
app binding.

| Zone | DNS records | Worker custom domains and mapped services | Routes | Pages custom domains |
|---|---:|---|---:|---|
| `aozora.app` | 12 | 7: apex → `app-aozora-spa`; `appview` → `app-aozora-appview`; `auth` → `app-aozora-auth`; `live` → `app-aozora-live`; `magatama`, `pds`, `shinshi` → `app-aozora-pds` | 0 | 0 |
| `awai.network` | 9 | 2: apex and `www` → `network-awai-apex` | 0 | 0 |
| `babiniku.net` | 6 | 0 | 0 | `babiniku.net` → `net-babiniku` |
| `etzhadaat.org` | 0 | 0 | 0 | 0 |
| `etzhayyim.com` | 26 | 2: `authn` → `etzhayyim-xrpc-proxy`; `ossekai.arb` → `ossekai-arb-intake` | 3 | 0 |
| `gftd.ai` | 88 | 2: `engi` → `engi`; `kakure` → `magatama-hg7g75wa` | 8 | `gorigori-ketsu-dasshu.gftd.ai` → `gorigori-ketsu-dasshu` |
| `gftd.cloud` | 0 | 0 | 0 | 0 |
| `isekai.network` | 6 | 0 | 0 | `isekai.network` → `network-isekai` |
| `itonami.app` | 16 | 1: `arb.ossekai` → `ossekai-arb-intake` | 1 | 0 |
| `itonami.cloud` | 32 | 15: `agent` → `cloud-itonami-agent-edge`; `app` → `itonami-fleet-dispatch`; `auth` → `itonami-app-auth`; `bots` → `itonami-grok-bots`; `dougaka` → `itonami-dougaka`; `hanmoto` → `cloud-itonami-hanmoto`; `hooks` → `itonami-cloud-webhooks`; `kenbun` → `kenbun`; `kouryaku` → `itonami-media-kouryaku`; `local` → `itonami-client-static`; `mangaka` → `mangaka-reader`; `mcp` → `cloud-itonami-mcp-edge`; `office` → `itonami-office`; `relay` → `app-mail-relay`; `treasury` → `itonami-ethglobal-tokyo-demo` | 4 | `itonami.cloud` and `cloud-itonami.itonami.cloud` → `cloud-itonami` |
| `kotoba.cloud` | 27 | 17: apex, `app`, `apps`, `blog`, `console`, `data`, `graph`, `knowledge`, `kyber`, `storage`, `support`, `twin`, `www`, `yabai` → `kotoba-cloud-pages`; `auth` → `kotobase-authn`; `docs` → `kotoba-cloud-docs`; `kagitaba` → `kagitaba-kms` | 0 | `bots.kotoba.cloud` → `cloud-itonami` |
| `kotoba.digital` | 0 | 0 | 0 | 0 |
| `kotoba.foundation` | 1 | 1: apex → `kotoba-foundation-commons` | 0 | 0 |
| `kotoba-labs.com` | 0 | 0 | 0 | 0 |
| `kotobalabs.com` | 6 | 2: apex and `www` → `kotoba-labs-site` | 0 | 0 |
| `kotoba-lang.org` | 11 | 2: apex and `www` → `kotoba-lang-org` | 0 | 0 |
| `kotobase.net` | 23 | 1: `wiki` → `app-hyakka` | 0 | 0 |
| `manimani.cloud` | 1 | 1: apex → `cloud-manimani` | 0 | 0 |
| `murakumo.cloud` | 27 | 14: apex and `www` → `murakumo-cloud`; `api` → `local-murakumo`; `console` and `operator` → `murakumo-operator`; `factoring` → `cloud-itonami-isic-6493-factoring`; `generation` → `murakumo-generation-proxy`; `ipfs-pilot` → `murakumo-ipfs-gateway-pilot`; `local` → `murakumo-client-static`; `meter` → `murakumo-meter`; `setup` → `murakumo-setup`; `status` → `murakumo-status`; `toshokan` → `murakumo-toshokan`; `web` → `murakumo-web` | 10 | 0 |
| `nubatama.net` | 0 | 0 | 0 | 0 |
| `oppai.fans` | 0 | 0 | 0 | 0 |
| `shinshi.club` | 10 | 1: apex → `magatama-sh1n5h1x`; DNS has a `www` host, with no corresponding Worker custom domain | 0 | 0 |
| `spirit-in-physics.org` | 4 | 3: apex and `www` → `spirit-in-physics-web`; `researcher` → `spirit-in-physics-researcher` | 3 | `comics.spirit-in-physics.org` → `org-spirit-in-physics-comics` |
| `x402.nexus` | 7 | 1: apex → `nexus-x402` | 0 | 0 |
| `yaoi.dev` | 2 | 2: apex and `www` → `dev-yaoi` | 0 | 0 |
| `yatalake.com` | 0 | 0 | 0 | 0 |
| `yataverse.com` | 3 | 1: `wiki` → `app-hyakka` | 1 | 0 |

All 30 account `cloud-kotoba` route patterns are included here, even where a
custom domain also points at a Worker. The patterns are exact Cloudflare
configuration strings; they are not public HTTP verification.

| Route pattern | Worker |
|---|---|
| `app.itonami.cloud/auth*` | `itonami-app-auth` |
| `itonami.cloud/api/bots-status*` | `itonami-public-bots-status` |
| `itonami.cloud/ethglobal-tokyo-2026/*` | `itonami-ethglobal-tokyo-demo` |
| `itonami.cloud/ethglobal-tokyo-2026` | `itonami-ethglobal-tokyo-demo` |
| `kaisya.itonami.app/*` | `cloud-itonami-kaisya` |
| `murakumo.cloud/api/devices/link/*` | `murakumo-node-registration` |
| `murakumo.cloud/commons/*` | `kotoba-foundation-commons` |
| `murakumo.cloud/legal-node-participation*` | `kotoba-foundation-commons` |
| `murakumo.cloud/advertise` | `murakumo-ads-mcp` |
| `murakumo.cloud/advertise/review*` | `murakumo-ads-mcp` |
| `murakumo.cloud/mcp` | `murakumo-ads-mcp` |
| `murakumo.cloud/mcp/` | `murakumo-ads-mcp` |
| `murakumo.cloud/llm.txt` | `murakumo-llms` |
| `murakumo.cloud/llms.txt` | `murakumo-llms` |
| `murakumo.cloud/.well-known/llms.txt` | `murakumo-llms` |
| `genko.gftd.ai/*` | `genko-gftd` |
| `daw.gftd.ai/*` | `kami-app-daw` |
| `nle.gftd.ai/*` | `kami-app-nle` |
| `manga.gftd.ai/*` | `mangaka-reader` |
| `gorigori-ketsu-dasshu.gftd.ai/*` | `goriketsu-proxy` |
| `y5kk5r1x.gftd.ai/*` | `magatama-y5kk5r1x` |
| `yukkuri.gftd.ai/*` | `magatama-y5kk5r1x` |
| `mangaka.gftd.ai/*` | `mangaka-gftd-redirect` |
| `etzhayyim.com/*` | `etzhayyim-did-web` |
| `www.etzhayyim.com/*` | `etzhayyim-did-web` |
| `sip.etzhayyim.com/*` | `sip` |
| `spirit-in-physics.org/api/*` | `spirit-in-physics-api` |
| `www.spirit-in-physics.org/api/*` | `spirit-in-physics-api` |
| `researcher.spirit-in-physics.org/api/*` | `spirit-in-physics-api` |
| `wiki.yataverse.com/patents*` | `app-hyakka-patents` |

The account's seven Pages custom-domain attachments are the Babiniku and
Isekai apexes, the two Itonami Cloud aliases, `bots.kotoba.cloud`,
`comics.spirit-in-physics.org`, and `gorigori-ketsu-dasshu.gftd.ai`, mapped to
the projects shown in the zone table. The other nine Pages projects and all
`pages.dev` preview URLs remain separate deployment surfaces; see the
account-wide project and preview inventory above.

`fund-mithril` has 23 enabled production Worker custom domains on
`mithril.fund`: apex, `www`, `admin`, `analysis`, `api`, `app`, `auth`, `blog`,
`bounty`, `code`, `console`, `docs`, `dojo`, `graph`, `hikari`, `knowledge`,
`kyber`, `models`, `storage`, `support`, `talk`, `twin`, and `yabai`. Its six
routes are `console.mithril.fund/*`, `knowledge.mithril.fund/*`, and the
root/slash variants of the GitHub and Google sign-in routes on
`mithril.fund`. `mithril.love` and `mythril.build` had no connected-account
DNS/runtime resources in the preceding inventory; recheck their current
delegations before classifying them as unused. Their app ownership remains a
separate decision from the shared host implementation.

The Worker-domain API also returned `spirit-in-physics.com`,
`www.spirit-in-physics.com`, and `researcher.spirit-in-physics.com`, mapped to
`spirit-in-physics-web` and `spirit-in-physics-researcher`. That hostname is
not one of the 27 active `cloud-kotoba` zones. Its exact Registrar match,
authoritative DNS, and owner remain unresolved, so it stays in the external-
host reconciliation set.

The registrar API returned 26 Cloud-kotoba registrations and 3 Fund-Mithril
registrations for 30 active zones across those accounts. One zone does not
match a registration in those lists. Identify its registrar and owner before
selecting the DNS migration order. All 29 listed registrations were locked,
had no pending transfer, and returned no DS records in the latest account
read; these fields do not establish transfer eligibility. The seven Cloud-
Kotoba empty zones with no DNS, Worker, route, Pages, or Tunnel configuration
are `etzhadaat.org`, `gftd.cloud`, `kotoba.digital`, `kotoba-labs.com`,
`nubatama.net`, `oppai.fans`, and `yatalake.com`; the empty `mithril.love` and
`mythril.build` zones were previously observed in the Fund-Mithril account.
Account records, registrar records, parent DNS, and public service behavior
must be reconciled before any empty zone is retired.
