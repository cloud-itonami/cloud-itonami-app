# Awai app domain inventory and migration gates

Snapshot date: 2026-10-15. This is a read-only inventory from the connected
Cloudflare account API: zones, DNS records, Worker custom domains/routes and
`workers.dev` settings, Pages projects/domains/previews, and Tunnel hostnames.
No DNS, Worker, Pages, Tunnel, or deployment settings were changed. It does
not yet cover registrar state, public authoritative nameserver readback,
non-Cloudflare DNS providers, application-origin behavior, or mail delivery
tests.

## Common target

Each product remains its own application and service identity. `itonami.app`
provides the common launcher, Awai registry client, and host API. An app release
is immutable content identified by CIDs and a signed Awai `:app/release`; a
signed `:domain/bind` maps a hostname to its app, publisher, and update channel.
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
mail-flow readback. PR #428 merged the `cloud-itonami-app` host-neutral
lifecycle policy; this follow-up adds an authenticated marketplace view of
accepted Awai registry records. The Awai DNA source has a pure projection of
accepted public registry entries; it does not yet contain the local HTTP bridge
that Itonami expects at `127.0.0.1`. The Itonami reader fails closed when that
endpoint is unavailable and does not fall back to public or Cloudflare origins.
An experimental two-process loopback replication check is merged, but no
independent-host peer mesh or Itonami bridge acceptance result is established by
the current source. DNSSEC resolution, end-to-end
content retrieval, TLS serving, protected install records, app sandboxing, and
effect adapters are not yet deployed or qualified.

The app layer does not collapse distinct services into one identity. For
example, Murakumo inference remains an execution service that an app may call
through an explicit capability; Aozora keeps its AT Protocol PDS/AppView
contracts; and x402 keeps its payment verification and settlement boundary.
Shared launcher, registry, content verification, local installation, sandbox,
capability, and update contracts belong to the Itonami/Awai foundation; each
product app supplies its own UI, data schema, service adapters, and migration
receipts.

The shared runtime model and its readiness gates are maintained in
[`awai-app-runtime.md`](awai-app-runtime.md). Awai's signed predecessor chains
and replicated app DNA cells provide validation and replication; they do not
create one globally ordered ledger or imply payment finality. Any app that
requires global ordering must name and verify that service separately.

## Current Cloudflare exposure

| Domain | Current HTTP/API surface | Other records and migration constraints |
|---|---|---|
| `itonami.app` | Proxied wildcard `*.itonami.app`; direct A record and Worker route `kaisya.itonami.app/*`; Worker custom domain `arb.ossekai.itonami.app`. | 13 `_dnslink` hostnames: `animator`, `bim`, `cad`, `daw`, `genko`, `inkan`, `kaisya`, `modeler`, `nle`, `rirekisho`, `sculpt`, `shirohan`, and `tsukuru`. These records point to IPFS/IPNS CIDs but are not Awai `domain/bind` records. The wildcard currently terminates through Cloudflare. This is the common launcher domain, so its own bootstrap path must leave Cloudflare before other domains can rely on it. |
| `itonami.cloud` | Pages project `cloud-itonami` serves `itonami.cloud` and `cloud-itonami.itonami.cloud`; Worker domains include `app`, `auth`, `agent`, `bots`, `dougaka`, `hanmoto`, `hooks`, `kenbun`, `kouryaku`, `mangaka`, `mcp`, `office`, `relay`, and `treasury`. Four Worker routes cover app auth, bot status, and the EthGlobal demo. `gateway.itonami.cloud` points at a Cloudflare Tunnel; `*.ipfs.itonami.cloud` and `*.ipns.itonami.cloud` proxy the IPFS/IPNS gateway. | The apex is also the WebAuthn RP ID. Preserve sign-in, existing sessions during transition, host-only cookies, and mail records. Moving to `itonami.app` changes the origin and does not transfer a raw WebAuthn credential. |
| `murakumo.cloud` | Worker domains include the apex and `www`, plus `api`, `factoring`, `generation`, `ipfs-pilot`, `meter`, `operator`, `setup`, `status`, `toshokan`, and `web`. Ten Worker routes cover node registration, Commons/legal content, LLM documents, and ads/MCP. `gateway`, `infer`, and `origin-generation` use Tunnels. | The live app provides chat, inference and generation, alongside device registration, content, status, and advertising APIs. This is the selected first migration domain. Replace each hostname and route only after its service contract and independent-node read/write behavior are evidenced. Preserve mail records. |
| `aozora.app` | Worker domains: apex, `appview`, `auth`, `live`, `magatama`, `pds`, and `shinshi`. | Preserve AT Protocol PDS, AppView, auth, and live service behavior independently. Preserve mail records. |
| `isekai.network` | Pages project `network-isekai` (`network-isekai.pages.dev`, production branch `main`). | Preserve mail records. |
| `babiniku.net` | Pages project `net-babiniku` (`net-babiniku.pages.dev`, production branch `main`). | Preserve mail records. Current Pages production config binds R2 `GENERATED_ASSETS`; `BROADCAST_DB` is present in the preview config only, so its production ownership/use needs a source-level reconciliation. |
| `x402.nexus` | Worker custom domain at the apex (`nexus-x402`); `signer.x402.nexus` points at a Cloudflare Tunnel. | Preserve payment verification, settlement, idempotency, and signer separation; payment proof is not a migration or settlement receipt. Preserve mail records. |
| `shinshi.club` | Worker custom domain at the apex (`magatama-sh1n5h1x`) is enabled; proxied `www` record has a redirect comment but the actual redirect rule was not inspected. A second script, `shinshi-cljc`, has its own `workers.dev` route enabled. | Preserve mail records and both D1 stores; inspect the old/new worker split and verify the actual `www` response before cutover. |
| `awai.network` | Active Cloudflare zone with no A, AAAA, or CNAME records, Worker custom domain, route, or Pages custom domain in the queried accounts. | Has Google Workspace MX/DKIM and ownership-verification TXT records, including an Itonami domain-verification challenge. Treat it as an Awai identity/mail domain, not a confirmed web app origin. Preserve mail and verify public DNS/ownership separately. |
| `bots.kotoba.cloud` | Also attached to the `cloud-itonami` Pages project. | Cross-domain Itonami surface discovered through the Pages project inventory; include it in the migration cohort or explicitly retire it after a public readback. |
| `kotoba.cloud` | Worker domains include `kotoba.cloud`, `www`, `console`, `blog`, `apps`, `graph`, `support`, `app`, `storage`, `kyber`, `twin`, `yabai`, `knowledge`, `data`, and `docs`; `auth.kotoba.cloud` is attached to `kotobase-authn`; `kagitaba.kotoba.cloud` is attached to `kagitaba-kms`. `bots.kotoba.cloud` is a Pages custom domain. | Shared authentication, key management, and Itonami-adjacent Pages surfaces. Keep these in the dependency inventory; the exact boundary between Itonami/Awai and Kotoba services still needs owner/source classification. |
| `oppai.fans` | The Cloudflare zone is active but has no DNS records, Worker custom domains/routes, or Pages domain in the connected account. The `oppai-fans` Worker script exists, but its `workers.dev` route is disabled. The source repo still describes a Worker + SPA at `oppai.fans`. | This is a provider/source mismatch, not evidence that the app is dormant. Reconcile public delegation and the deployed origin before changing or replacing the domain. |

The queried Cloudflare DNS records for this cohort contain no `awai-app=` TXT
binding. Existing `_dnslink` records on `itonami.app` and `itonami.cloud` point
to IPFS/IPNS content but do not establish a signed Awai domain binding.

The account API currently reports 27 active zones and three moved-zone records
in `cloud-kotoba`, three active zones in `fund-mithril`, and no zones in
`com-junkawasaki`. A fresh read also found `awai.network`, `sculpt.itonami.app`,
`auth.kotoba.cloud`, and `kagitaba.kotoba.cloud`; the eight product domains
named by the user and `itonami.app` remain the initial migration cohort. The
account has other hosted domains and services that still need ownership and
product-scope classification before the full external inventory is complete.

## Product service contracts to carry through migration

The app ID and its service contract stay separate from a DNS alias. These
contracts come from the product repositories and current Cloudflare bindings;
they describe migration obligations, not proof that a replacement runtime is
ready.

| Migration unit | Current contract and dependency evidence | Required replacement proof |
|---|---|---|
| `itonami.app` launcher | Proxied wildcard, `kaisya.itonami.app/*`, `arb.ossekai.itonami.app`, and 13 legacy `_dnslink` aliases remain. No DNS record in this inventory has an Awai `domain/bind` proof. | Bootstrap the launcher from verified immutable content across independent origins; prove direct DNS/TLS and offline launch before it becomes another app's resolver. |
| `murakumo.cloud` compute service | Worker and Tunnel routes serve inference, generation, model hosting, device registration, status, and operations. The service is a compute plane with job identity, placement, billing, and receipts; it is not just the `murakumo.cloud` web app. | Run execution on Awai-connected operator nodes, preserve per-job authorization/idempotency/receipts, and prove generation and inference read/write behavior while one node is unavailable. Keep each app's Murakumo access behind a host capability. |
| `aozora.app` | The `app-aozora` source defines the canonical AT Protocol PDS, AppView, XRPC, feeds, search, and profile publication boundary. `etzhayyim.com` is a separate organization surface but still has `atproto` and `pds` Tunnel aliases. The current PDS has an R2 blob binding; live media has a separate R2 segment binding. | Preserve AT Protocol repo writes, blob reads, AppView queries, Lexicon compatibility, and live playback on independent nodes. Move the `etzhayyim.com` service aliases only after consumers use the canonical endpoint or have an explicit redirect plan. |
| `oppai.fans` | The product source describes a Worker + SPA and Murakumo generation/chat calls with consent and age gates. The current account has an `oppai-fans` script with an R2 binding but no public Worker route; `workers.dev` is disabled, and the zone has no DNS records. Source and deployed route state disagree. | Reconcile external delegation first. Preserve age/consent checks, private face enrollment, and the Murakumo capability boundary; prove the actual public origin and storage path before cutover. |
| `isekai.network` | The source documents a static snapshot already pinned on two independent IPFS nodes, but production Pages still binds D1 `SCORES_DB` and `SOCIAL_ROOMS_SERVICE`; generation jobs and game writes are separate runtime paths. | Verify the static CID from independent public clients, then migrate scores, rooms, generation, and fork writes independently with state import and write receipts. Static availability alone is not completion. |
| `itonami.cloud` | Pages is also the WebAuthn RP origin and binds `WORKSPACE_DB`, `ITONAMI_DATA`, `BOT_SANDBOX`, and `CAPITAL_API`. The auth Worker separately binds a Durable Object, `ITONAMI_DATA`, and `kotobase-authn`. | Keep account and session continuity, prove WebAuthn credential migration/renewal, and move D1/KV/DO and service calls with export/import receipts. A new origin cannot reuse the old RP ID credential as if it were the same origin. |
| `x402.nexus` | The apex Worker is the payment facilitator; it binds two R2 buckets and has a `signer.x402.nexus` Tunnel entry. The Tunnel currently reports down. The x402 source defines Base payment verification, settlement, seller-specific treasuries, and idempotency. | Keep verification, settlement receipts, seller isolation, and replay protection in a separate payment service app. Prove chain-backed results and signer recovery before moving traffic; a DNS response is not a payment receipt. |
| `shinshi.club` | The apex still maps to legacy `magatama-sh1n5h1x`; `shinshi-cljc` also has a public `workers.dev` route. Both scripts bind `SHINSHI_DB` and `RECORDLOG_DB`; the legacy worker has additional secret bindings whose values were not read. | Reconcile which worker is the intended app release, preserve both databases and moderation/consent behavior, then test a canary and immediate rollback before changing the apex. |
| `babiniku.net` | Production Pages binds R2 `GENERATED_ASSETS`; `BROADCAST_DB` is present only in preview configuration. The product source describes private sessions, a local browser broadcast path, optional Cloudflare Stream ingest, and an Aozora live playback route. | Determine whether production broadcast writes exist outside the Pages binding, migrate any live ingest and generated assets, and prove playback from independent origins without Stream or Worker dependencies. |

`awai.network` currently has mail/ownership records but no app binding or web
route in the connected account. Keep operator identity and mail separate from
the app registry unless a distinct Awai app identity is deliberately published.

## Cloudflare-owned public endpoints and state dependencies

The `cloud-kotoba` Workers account has the `04-feasts-minded.workers.dev`
subdomain. The 2026-10-15 account API refresh found 28 Itonami/Awai-related
Workers with their `workers.dev` hostname enabled; 26 also have preview URLs
enabled. These hostnames remain public Cloudflare runtime surfaces even when a
custom domain is moved. The refreshed Worker names are listed below in the
dated snapshot. Cloudflare's
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

## Live connected-account refresh (2026-10-15)

I re-read the connected Cloudflare API after the initial snapshot. The session
can see three accounts: `cloud-kotoba` (30 zones, including 27 active and 3
moved; 106 Workers, 73 Worker custom domains, 16 Pages projects, and 30
Tunnels), `fund-mithril` (3 active zones, 36 Workers, and 23 Worker custom
domains), and `com-junkawasaki` (no zones, Workers, Pages projects, or
Tunnels). The Itonami/Awai surfaces below are in `cloud-kotoba`; the `fund-
mithril` inventory remains a separate ownership cohort. This is a provider
account snapshot, not a registrar or public-DNS inventory.

The current `workers.dev` account prefix is `04-feasts-minded`. These 28
Itonami/Awai-adjacent Worker scripts currently have their `workers.dev` route
enabled; 26 also have preview URLs enabled. These independent provider routes
remain public surfaces even after a custom-domain DNS change:

`app-aozora-appview`, `app-aozora-auth`, `app-aozora-live`, `app-aozora-pds`,
`app-aozora-spa`, `cloud-itonami-app-classifieds-production`,
`cloud-itonami-app-edge`, `cloud-itonami-domain-reverify`,
`cloud-itonami-mail-inbound`, `cloud-itonami-marketplace-buyer`,
`cloud-itonami-marketplace-crossborder`,
`cloud-itonami-marketplace-fulfillment`, `cloud-itonami-marketplace-listing`,
`cloud-itonami-marketplace-onboarding`, `cloud-itonami-marketplace-order`,
`cloud-itonami-marketplace-returns`, `cloud-itonami-marketplace-settlement`,
`cloud-itonami-tsukuru`, `cloud-itonami-workspace-outbox`,
`itonami-pricing-resident`, `kenbun`, `kotoba-cloud-cljk-preview`,
`kotoba-cloud-pages-candidate`, `kotoba-foundation-commons`,
`kotoba-worker-demo`, `mangaka-reader`, `murakumo-setup`, and `shinshi-cljc`.
The two names `kotoba-cloud-cljk-preview` and
`kotoba-cloud-pages-candidate` have preview URLs disabled; the other 26 have
them enabled. `oppai-fans` exists as a Worker script but was not among the
scripts with a `workers.dev` route enabled, and no DNS, Worker custom domain,
route, or Pages domain for `oppai.fans` appeared in this account. Its public
status outside this account is still unknown.

The Pages API lists 16 projects, each with its `pages.dev` hostname. Seven
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

The DNS and Worker-domain refresh found additional cross-zone aliases outside
the product apex zones. Each needs an explicit keep-as-alias, migrate, or
retire decision with the product owner:

| Hosting zone | Public service aliases or routes observed | Migration note |
|---|---|---|
| `etzhayyim.com` | `agent-origin.itonami.cloud.etzhayyim.com`, `isic-5820.itonami.cloud.etzhayyim.com`, `itonami-5820.etzhayyim.com`, `itonami-agent.etzhayyim.com`, `itonami-mcp.etzhayyim.com`, `mcp.itonami.cloud.etzhayyim.com`, `origin-generation.murakumo.cloud.etzhayyim.com`, `atproto.etzhayyim.com`, and `pds.etzhayyim.com`. DNS CNAMEs point to Cloudflare Tunnels; the account also has root and `www` Worker routes. | These are direct alternate names for Itonami, Murakumo, and AT Protocol services. Moving the eight requested apex domains alone would leave these routes intact. |
| `gftd.ai` | Wildcard `*.gftd.ai`; Murakumo aliases include `comfyui-fleet`, `comfyui-gad`, `gemma-fleet`, `gemma-gad`, `generation.murakumo.cloud`, `keiei-llm` variants, `murakumo-dashboard`, `murakumo-serve`, `murakumo-voice`, `ollama`, `relay.murakumo.cloud`, and `shinshi-comfy`; `lg-shinshi.gftd.ai` also routes through a Tunnel. Worker routes include `genko`, `daw`, `nle`, `manga`, `mangaka`, and other GFTD app names. | The wildcard plus Tunnel hostnames expose more than the requested `murakumo.cloud` and `shinshi.club` names. Determine which are Murakumo execution adapters, app aliases, or separate GFTD services before cutover. |
| `itonami.app` | `arb.ossekai.itonami.app` is a Worker custom domain (`ossekai-arb-intake`); `kaisya.itonami.app/*` routes to `cloud-itonami-kaisya`. A proxied wildcard record also remains. | These are common-launcher-domain surfaces and must be included in the `itonami.app` bootstrap migration. |
| `kotoba.cloud` | The `cloud-itonami` Pages project also serves `bots.kotoba.cloud`; Worker domains expose `app`, `apps`, `auth`, `blog`, `console`, `data`, `docs`, `graph`, `knowledge`, `kyber`, `storage`, `support`, `twin`, `www`, and `yabai`. | Resolve service ownership and shared-auth/KMS dependencies; preserve them or explicitly retire them with public readback. |
| `gftd.ai` / Pages | `gorigori-ketsu-dasshu.gftd.ai` points to the `gorigori-ketsu-dasshu` Pages project and its `pages.dev` hostname. | Product classification is not yet established. |

Cloudflare Tunnel configuration currently reports `comfyui-gad` as healthy
for `gateway.murakumo.cloud` and `infer.murakumo.cloud`, and `gftd-llm` as
healthy for Murakumo voice/relay and model aliases. `itonami-5820`,
`itonami-agent`, `murakumo-fleet`, `yukkuri-gemma-gad`, and
`nexus-wallet-25mbair` report `down` in the control plane, but their DNS and
Tunnel configuration entries remain present. A `down` Tunnel status is not
evidence that a hostname is safe to omit or retire.

The Cloudflare API snapshot does not establish which non-Cloudflare providers,
registrars, public authoritative nameservers, unproxied origins, public HTTP
routes, or mail paths are active. It also does not settle the ownership of all
`gftd.ai`, `etzhayyim.com`, and remaining account projects. The external
inventory therefore remains open until those sources and public readbacks are
reconciled.

## Migration order and acceptance gates

1. **Finish the common host.** PR #428 merged the host-neutral lifecycle
   policy; this follow-up adds a loopback-only Itonami reader, a pure Awai
   accepted-state projection, deterministic domain-to-release chain resolution,
   and an authenticated marketplace view of candidates. The Awai HTTP bridge is
   still missing, and there is no marketplace install or launch action yet. Next implement and
   exercise that bridge, then add
   DNSSEC binding resolution, independent CID retrieval, protected installation
   records, app sandboxing, cell state migration, capability adapter wiring,
   and multi-node state reconciliation, following the shared acceptance gates
   in [`awai-app-runtime.md`](awai-app-runtime.md). Add export/import receipts for the
   D1/KV/R2 state and service bindings listed above before replacing those
   Pages applications. The read API is a local bridge, not independent-host
   qualification. Missing adapters fail closed; there is no Cloudflare
   fallback.
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
