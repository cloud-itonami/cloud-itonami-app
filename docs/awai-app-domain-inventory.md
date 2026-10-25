# Awai app domain inventory and migration gates

Current runtime endpoint inventory: 2026-10-17 JST. Connected account, zone,
and Registrar API state was rechecked through 2026-10-16T17:43:46Z. The
per-script Workers.dev settings and normalized endpoint hostnames were last
read earlier that day. Public root HTTP/DNS readback remains the separate,
method-specific observation below.
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

## Connected-zone configuration recheck (2026-10-17 JST)

A read-only API pass ending at 2026-10-16T15:18:46Z covered zones, DNS,
Worker domains/routes/scripts, Pages projects, and Tunnels across all three
connected accounts. It found 30 active zones, 361 DNS records (191 proxied),
36 Worker routes, 101 Worker custom domains, 145 Worker scripts, 16 Pages
projects, and 31 Tunnels (6 healthy, 25 down); DNSSEC is disabled on all 30
zones. Per-account counts are in
[awai-cloudflare-resource-recheck-2026-10-17.csv](awai-cloudflare-resource-recheck-2026-10-17.csv),
and per-zone DNS/DNSSEC counts are in
[awai-cloudflare-zone-recheck-2026-10-17.csv](awai-cloudflare-zone-recheck-2026-10-17.csv).
These are configuration reads only; they do not establish registrar
delegation, external authoritative DNS, recursive resolution, origin health,
or public HTTP behavior.

### Fresh API readback (2026-10-17 JST)

A second read-only read completed at `2026-10-16T16:24:59Z` across the same
three connected accounts. It returned 30 zones, 361 DNS records (191
proxied), 36 Worker routes, 101 Worker custom domains, 145 Worker scripts,
16 Pages projects, and 31 Tunnels (6 healthy, 25 down). Every request for the
listed surfaces succeeded. The 361 DNS records exactly match the prior
sanitized snapshot by account, zone, hostname, record type, and proxy state;
there were no additions or removals. Per-account counts are appended to
[`awai-cloudflare-resource-recheck-2026-10-17.csv`](awai-cloudflare-resource-recheck-2026-10-17.csv),
and the DNS comparison result is in
[`awai-cloudflare-dns-multiset-recheck-2026-10-17.csv`](awai-cloudflare-dns-multiset-recheck-2026-10-17.csv).
This API pass did not inspect DNS record contents or tunnel origin URLs and
does not prove live authoritative DNS, public HTTP behavior, app health, or
independent origin operation.

### Full connected-account surface re-read (2026-10-17 JST)

A read-only re-read completed at `2026-10-16T18:52:37Z` across all three
connected Cloudflare accounts. It returned 30 zones, 361 DNS records (191
proxied), 36 Worker routes, 101 Worker custom domains, 145 Workers.dev
settings (72 enabled, 68 preview-enabled), 16 Pages projects with 23 project
domains, 161 Pages preview host aliases across 108 deployment objects, and 31
Tunnel objects (6 healthy, 25 down). There were no API read errors.

SHA-256 hashes of the normalized row identities match the saved snapshot
exactly for zones, DNS hostname/type/proxy state, Worker routes, Worker custom
domains, Workers.dev settings, Pages project domains, and Pages preview host
aliases. Tunnel name/status/config-source rows were captured with a hash, but
the earlier snapshot has no corresponding full tunnel-object identity set, so
only its 31/6/25 count/status totals can be compared. The current per-surface
hashes and evidence limits are recorded in
[`awai-cloudflare-surface-fingerprint-recheck-2026-10-17.csv`](awai-cloudflare-surface-fingerprint-recheck-2026-10-17.csv).
The tunnel ingress catalog still combines provider configuration with
local-connector source evidence; this API reread did not prove origin
health or independent service operation.

### Targeted entry-zone and registrar recheck (2026-10-17 JST)

A fresh read-only check of `itonami.app`, `itonami.cloud`, and `murakumo.cloud`
found all three Cloudflare zones active and all three Cloudflare DNSSEC states
disabled. Their registrar records are active with transfer lock and automatic
renewal enabled. `itonami.app` has 16 DNS records, including a proxied wildcard
AAAA record, but no apex record; the current root therefore has no Cloudflare
DNS answer, while wildcard subdomains remain Cloudflare-proxied. `itonami.cloud`
has 32 records, including proxied app/service hosts and separate mail records.
`murakumo.cloud` has 28 records, with application AAAA hosts and the four
Tunnel CNAMEs proxied; mail and verification records remain separate. Record
values were not saved here. This confirms current account configuration only;
it does not prove registrar nameserver delegation, public resolution, origin
identity, or service health. Preserve this distinction before assigning an
independent gateway or changing any public record.

### Per-domain live configuration refresh (2026-10-17 JST)

A read-only refresh at `2026-10-16T20:27:06Z` enumerated both zone pages and
re-read all ten first-cohort zones, DNSSEC settings, DNS record sets, and Worker
routes. The aggregate remained 10 active zones, 126 DNS records (55 proxied),
15 Worker routes, and DNSSEC disabled on all ten. The per-zone values match
the earlier saved 2026-10-17 counts. Exact sanitized rows are in
[`awai-app-first-cohort-live-config-recheck-2026-10-17.csv`](awai-app-first-cohort-live-config-recheck-2026-10-17.csv).

At `2026-10-16T20:27:39Z`, individual Registrar `GET` requests succeeded for
all ten domains. All ten report Cloudflare as current registrar and `locked`
as true. `oppai.fans` is the exception in registry status: it reports both
`servertransferprohibited` and `clienttransferprohibited`; the other nine
report `clienttransferprohibited`. This makes `oppai.fans` a registrar-eligibility
review before any transfer can be scheduled. Its zero DNS records and zero
Worker routes also remain an app-publication gap, not an instruction to create
records during this inventory. The individual endpoint did not return
auto-renew state; retain the prior aggregate registrar read for that field.
The aggregate Registrar list endpoint returned an empty result array with
`total_count=26`, so these ten rows use the successful per-domain reads rather
than treating that inconsistent list response as zero registrations.

A hostname/type-only DNS read at `2026-10-16T20:29:31Z` found apex web
records on eight domains. `itonami.app` has no apex record and only a proxied
wildcard AAAA among its address records; `oppai.fans` has no records at all.
The other eight have an apex A/AAAA or proxied CNAME record. The same read
confirmed these two exceptions while withholding all record values; row-level
apex and wildcard RR shapes are in the linked CSV.

These reads are still Cloudflare control-plane state. They do not confirm
parent delegation, successful public transfer eligibility, independent DNSSEC,
gateway reachability, or application health.

### Current first-cohort route and DNSSEC confirmation (2026-10-17 JST)

A targeted Cloudflare API read at approximately `2026-10-16T21:32Z` confirmed
the same ten first-cohort zones remain active under the `cloud-kotoba` account
and list `everton.ns.cloudflare.com` and `vivienne.ns.cloudflare.com` as their
configured nameservers. DNSSEC is disabled on all ten zones. The read counted
126 DNS records, 15 Worker routes, 41 enabled Worker custom domains, and four
Pages custom-domain associations. The per-domain snapshot, including the
provider API evidence limit, is in
[`awai-cloudflare-first-cohort-domain-route-readback-2026-10-17.csv`](awai-cloudflare-first-cohort-domain-route-readback-2026-10-17.csv).

This confirms the migration source still has Cloudflare DNS/runtime
dependencies; it does not prove what nameservers the parent registry
currently delegates to or whether each origin is publicly reachable. In
particular, `oppai.fans` is an active Cloudflare zone with zero DNS records,
Worker routes, Worker custom domains, and Pages domains. Its publication
state must be resolved before assigning an Awai binding. None of these reads
changed DNS or provider configuration.

### Connected Registrar per-domain status refresh (2026-10-17 JST)

The aggregate Registrar list endpoint returned an empty result array despite
`total_count=26`, so the 29 names from the previous snapshot were checked by
individual `GET /accounts/{account_id}/registrar/domains/{domain_name}` calls
at `2026-10-16T20:31:40Z`. All 29 returned HTTP 200; all report Cloudflare as
current registrar and `locked=true`. Twenty-seven have
`clienttransferprohibited`; `oppai.fans` and `mythril.build` also have
`servertransferprohibited`. The complete status snapshot is
[`awai-cloudflare-registrar-status-recheck-2026-10-17.csv`](awai-cloudflare-registrar-status-recheck-2026-10-17.csv).
The auto-renew values remain from the earlier list response, because the
individual endpoint does not return that field. Both registry-blocked domains
need registrar/registry eligibility review before transfer planning; `oppai.fans`
also has no configured DNS records or Worker routes, and `mythril.build` is a
zero-record zone retention review.

## Current public endpoint reconciliation (2026-10-17 JST)

The current normalized endpoint snapshot is
[`awai-cloudflare-runtime-endpoints-2026-10-17.csv`](awai-cloudflare-runtime-endpoints-2026-10-17.csv).
Read-only API comparisons matched all 101 Worker custom domains, 36 Worker
routes, 23 Pages project hostnames, 161 Pages preview deployment/alias
hostnames, and 62 Tunnel hostname/path ingress rules in the earlier snapshot.
There were no additions, removals, or state changes in those surfaces.

The per-script Workers.dev settings read started at 2026-10-16T15:40:53Z and
covered all 145 scripts, resolving
an earlier inventory overcount: 72 scripts currently have an enabled stable
Workers.dev hostname, 73 have it disabled, and 68 have version-preview URLs
enabled. All 68 preview-enabled scripts are among the 72 enabled hosts; four
enabled hosts have previews disabled, while the 73 disabled hosts also have
previews disabled. The current runtime CSV includes 72 stable hosts and 68
preview URL patterns. All 145 individual setting results are retained in
[`awai-cloudflare-workers-dev-recheck-2026-10-17.csv`](awai-cloudflare-workers-dev-recheck-2026-10-17.csv).
Cloudflare documents preview URLs as
`<PREVIEW>-<WORKER_NAME>.<SUBDOMAIN>.workers.dev` in its
[version URL guide](https://developers.cloudflare.com/workers/versions-and-deployments/version-urls/).

A separate web retrieval on 2026-10-17 returned homepage content for
`murakumo.cloud`, `aozora.app`, `isekai.network`, and `itonami.cloud`. It did
not return page content for `oppai.fans`, `itonami.app`, `x402.nexus`,
`shinshi.club`, `babiniku.net`, or `awai.network`; those tool errors do not
establish DNS or HTTP unavailability. The dated readback records this limited
evidence for all ten roots in
[`awai-app-public-root-web-readback-2026-10-17.csv`](awai-app-public-root-web-readback-2026-10-17.csv).
Raw HTTP status, recursive DNS, JavaScript/API behavior, and origin identity
still need a direct network probe.

A hidden direct-browser probe then rendered `x402.nexus`, `shinshi.club`,
`babiniku.net`, and `awai.network`. It returned `net::ERR_NAME_NOT_RESOLVED`
for `oppai.fans` and `itonami.app`; this is browser-resolver evidence of current
user-facing DNS failure, not an authoritative global DNS proof. The combined
probes therefore returned homepage content for eight roots and could not
resolve two. The dated method-specific evidence is in
[`awai-app-public-root-browser-probe-2026-10-17.csv`](awai-app-public-root-browser-probe-2026-10-17.csv).

The 62 Tunnel rows include 14 path-conditioned rules; 30 catch-all ingress
rules have no public hostname and are not in the endpoint list. Tunnel origin
service URLs were not copied into the public inventory. These API reads
establish configured routes and flags, not DNS delegation, external HTTP
reachability, application health, or independent origin operation.

A fresh read-only DNS Records API snapshot at 2026-10-16T15:45:07Z contains all
361 records across 21 of the 30 zones, including 191 proxied records and 292
unique hostnames. Its zone/hostname/type/proxy multiset exactly matches the
2026-10-16 snapshot. The migration catalog carries one `dns-record` row per
record so mail, verification, and application DNS are accounted for separately
from serving endpoints. The sanitized current snapshot is
[`awai-cloudflare-dns-hostnames-2026-10-17.csv`](awai-cloudflare-dns-hostnames-2026-10-17.csv);
it omits record contents and target addresses.

Nine zones currently have zero configured DNS records. oppai.fans is already
included in the first product cohort because its source declares the app.
Eight other zones are explicit external-delegation and retention reviews:
etzhadaat.org, gftd.cloud, kotoba.digital, kotoba-labs.com, nubatama.net,
yatalake.com, mithril.love, and mythril.build. Their zero-record Cloudflare
zones do not prove that no external DNS or public service exists; each remains
unresolved until delegation and public reachability are checked.

The latest generated endpoint migration catalog has 3,476 rows: 523 runtime
provider rows, 361 DNS control-plane rows, 2,581 Pages production deployment
candidates, one source/provider mismatch, two root readbacks without exact
provider attachments, and eight zero-record zone review rows. DNS rows preserve
record type and proxy state while omitting contents and targets; they are not
HTTP endpoints. Its 33 domain groups include ten product
domains, 13 other named domains, two platform hostname families, and the
eight zone-only reviews. All 2,581 Pages deployment candidates remain
external_http_reachability=unverified.

The companion
[`awai-cloudflare-dns-dependency-classification-2026-10-17.csv`](awai-cloudflare-dns-dependency-classification-2026-10-17.csv)
classifies all 361 DNS records by role and provider hint without retaining
record contents, IP addresses, or DNS targets. Every `record_content` value is
`withheld`. The role counts are 115 proxied web addresses, 8 direct addresses,
78 hostname aliases, 52 mail-routing records, 25 DKIM keys, 24 DMARC policies,
22 sender policies, 15 content-addressing proofs, 7 domain-verification
records, 5 certificate-authority policies, 1 service-domain verification, and
9 unclassified TXT values. Provider hints are inferred from record metadata;
they do not prove which provider currently serves a hostname.

## Earlier account-wide API reconciliation (2026-10-16)

A second read-only pass checked all three connected Cloudflare accounts, all 30
zones, every Worker script subdomain setting, all Pages projects and deployment
metadata, and all 31 Tunnel configurations. The current account-wide counts are
30 zones, 361 DNS records, 36 Worker routes, 101 Worker custom domains, 145
Worker scripts, 16 Pages projects, and 31 Tunnels. DNSSEC is disabled on all 30
zones; a fresh DNSSEC API read returned successfully for all 30, including all
ten active initial-cohort zones. Every Tunnel configuration request succeeded;
the API reports 6 healthy and 25 down. These are provider configuration and
edge-state observations, not proof of origin health or public HTTP behavior.

All 145 Worker script subdomain settings returned successfully: `workers.dev`
is enabled for 72 scripts and disabled for 73; preview URLs are enabled for 68
and disabled for 77. The 2026-10-16 endpoint snapshot retained all 145 script
settings as rows; the current 2026-10-17 runtime snapshot lists enabled public
hostnames and separate preview URL patterns, while the per-script audit CSV
retains all 145 settings. Pages reports 108 preview deployment records
and 2,581 production deployment records across its 16 projects (production
counts refreshed at 2026-10-16T13:43:40.794Z; URL pagination completed by
2026-10-16T13:49:38Z). The runtime
CSV has 161 preview URL rows covering 108 deployment IDs; the extra 53 rows
are named aliases attached to those deployments. A paginated read of all 16
projects matched the full 161-hostname set in the CSV. Its 23 production rows
record project hostnames and custom-domain attachments, not the 2,581
production deployment-history URLs. These API records do not prove that every
old deployment URL remains publicly reachable. The DNS and Worker-route CSV
row counts match this API pass.

The companion
[`awai-pages-production-deployment-urls-2026-10-16.csv`](awai-pages-production-deployment-urls-2026-10-16.csv)
records all 2,581 production deployment-specific `pages.dev` URLs from the
same API read: 2,581 unique deployment IDs and URLs across 15 projects (the
sixteenth project had no production deployment records). The CSV includes the
provider stage status and creation time (2,567 success, 13 failure, 1 active).
`external_http_reachability` is
`unverified` on every row: API presence is not evidence that an old deployment
URL still resolves or is publicly reachable. This is a candidate exposure
inventory kept separate from the 2026-10-16 528-row historical runtime snapshot
and the current 2026-10-17 endpoint CSV.

The current service ownership map covers 174 service names across 523 runtime
endpoints. A unique canonical Wrangler-name match identifies the service source
repository; product-family hints remain separate because one product can
contain services from several repositories. The current map has 84 source
matches, including pinned GitHub config snapshots, 13 product-owner hints
without a Wrangler match, 5 repository-name candidates, 6 ambiguous config
matches, and 66 owner-review items. Wrangler files with per-service suffixes
(for example, `wrangler.generation.jsonc`) are included. This maps source
ownership evidence; it does not by itself prove
release provenance, classify every subdomain as a product app, or establish a
migration receipt.
The 2026-10-17 source scan recognizes per-service Wrangler filenames as well
as the unsuffixed default file. It found exact configuration matches for 13
services that the previous filename filter missed: five Murakumo services,
Itonami bots, Marketplace listing, five Mithril services, and Torihiki
validator-v3. The refreshed ownership CSV records each configuration path;
these matches identify source repositories, not app publishers or release
authority.
The 2026-10-16 migration snapshot carried these owner states across 25
root domains and 3,112 catalog rows. The first 2026-10-17 generated version
added eight active zero-record zones; its 3,120-row total was corrected after
the per-script Workers.dev reconciliation. The current catalog has 33 domain
groups and 3,476 rows, including 361 DNS metadata rows. Each group has a
migration stage and next action; empty zones use a separate
registrar-delegation and retention gate. The
production rows are joined to their Pages project owner and known product
domain where evidence supports that mapping; 27 remain classified under
`pages.dev` for owner/domain review. All 2,581 carry
`external_http_reachability=unverified`. This incorporates provider-listed
production history without treating it as confirmed live traffic. Exact
attached root rows now carry the direct HTTPS readback: eight returned HTTP
200 through Cloudflare anycast; the `itonami.app` and `oppai.fans` roots have
DNS NODATA and no HTTPS resolution. This does not establish independent
origin reachability. The 2026-10-17 stage counts are 10 foundation/product
qualification domains, 8 external-delegation and retention reviews, 9
owner/boundary reviews, 4 app-identity/service reviews, and 2 platform-alias
decisions. For the first product cohort, seven public
service callsites use portable AMU WASM wrappers in local uncommitted product
worktrees; none is merged or released. Shinshi's measured Kotoba threshold is
wired only into the offline video-drain workflow, not a public app runtime.
Native AMU selfhost microbenchmarks cover eight product cores plus the
shared install gate, but the adopted selfhost compiler accepts only
`aarch64-macos`; browser/Worker WASM artifacts come from the separate
JVM-free bootstrap path. The latest source-matched recheck found native slower
than the warmed JavaScript reference in all nine diagnostic kernels; details
and target limits are in
[`awai-product-kotoba-selfhost-native-recheck-2026-10-16.md`](benchmarks/awai-product-kotoba-selfhost-native-recheck-2026-10-16.md).

No DNS, Worker, Pages, Tunnel, registrar, or deployment settings were changed.
No external domain has been switched. The production Awai/Amano chain profile
remains uncreated, so signed production app releases and domain bindings cannot
yet be established.

## Focused public-cohort API recheck (2026-10-16T13:28:10Z)

The Cloudflare API was read again for all ten initial migration domains. All
ten zones are active and DNSSEC is disabled. Their Registrar registration
lookups are active and `locked: true`. Account-wide totals rechecked in the
same pass were 27 zones, 78 Worker custom domains, 109 Worker scripts, and 31
Tunnels in `cloud-kotoba`; `fund-mithril` has 3 zones, 23 Worker custom
domains, 36 scripts, and no Tunnels. `cloud-kotoba` still has 16 Pages
projects. These were GET-only reads.

| Domain | DNS records / proxied | Worker custom domains | Worker routes | Pages custom-domain mapping |
|---|---:|---:|---:|---|
| `murakumo.cloud` | 28 / 18 | 14 | 10 | — |
| `aozora.app` | 12 / 7 | 7 | 0 | — |
| `oppai.fans` | 0 / 0 | 0 | 0 | — |
| `isekai.network` | 6 / 1 | 0 | 0 | `network-isekai` active |
| `itonami.cloud` | 32 / 20 | 15 | 4 | `cloud-itonami` active |
| `x402.nexus` | 7 / 2 | 1 | 0 | — |
| `shinshi.club` | 10 / 2 | 1 | 0 | — |
| `babiniku.net` | 6 / 1 | 0 | 0 | `net-babiniku` active |
| `awai.network` | 9 / 2 | 2 | 0 | — |
| `itonami.app` | 16 / 2 | 1 | 1 | — |

Worker custom-domain counts include the apex and service subdomains. The
nonzero route sets are ten `murakumo.cloud` paths, four `itonami.cloud` paths,
and `kaisya.itonami.app/*`. Pages also reports `cloud-itonami.itonami.cloud`
and `itonami.cloud` active on `cloud-itonami`. `oppai.fans` still has an active
zone and locked registration but no DNS records, Worker custom domain, or Pages
custom domain. `itonami.app` still has a proxied `*.itonami.app` AAAA record;
the listed Worker custom domain is `arb.ossekai.itonami.app`. The independent
public app gateway remains unconnected in the local Itonami host. No DNS,
Worker, Pages, Tunnel, Registrar, or deployment setting changed during this
recheck.

## Earlier read-only public-surface refresh (2026-10-16T10:04:45Z)

The Cloudflare API was re-read for all ten migration-cohort zones. All ten
remain active with DNSSEC disabled; their DNS record counts are unchanged from
the table below. The connected Registrar API lists all ten as Cloudflare
registrations with `locked: true` and empty DS-record sets. The account still
has 27 active `cloud-kotoba` zones, 78 Worker custom domains, 16 Pages
projects, and 31 non-deleted Tunnels (6 healthy, 25 down). No DNS, Registrar,
Worker, Pages, or Tunnel setting was changed.

The exact Worker-domain read still shows production custom domains for
`murakumo.cloud` and `www.murakumo.cloud` → `murakumo-cloud`, `aozora.app` →
`app-aozora-spa`, `x402.nexus` → `nexus-x402`, `shinshi.club` →
`magatama-sh1n5h1x`, and `awai.network` → `network-awai-apex`. Pages project
records still assign `babiniku.net` to `net-babiniku`, `isekai.network` to
`network-isekai`, and `itonami.cloud` to `cloud-itonami`. `oppai.fans` has no
DNS records or Worker/Pages custom domain in the connected account. These are
provider mappings, not proof that Cloudflare is the only public origin.

A public-page reader returned crawl content for `murakumo.cloud`,
`aozora.app`, `isekai.network`, and `itonami.cloud`. It could not retrieve
`itonami.app`, `oppai.fans`, `x402.nexus`, `shinshi.club`, or `babiniku.net`.
Those reader failures do not establish that the domains are down. The reader
does not expose a trustworthy HTTP status, response headers, direct DNS
answers, or origin identity; independent public-client checks are still
required.

The refreshed state leaves two independent blockers before any public
cutover: there is no authoritative production Awai/Amano app-chain profile,
and the cohort registrations remain locked at Cloudflare with no signed DNSSEC
delegation. The local app host and selfhost benchmark do not change either
condition. No product-domain release or binding has been published.

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
projection. Inga PR #30 merged to `main` as
`cb87cdb38d73e24e9ec0c454360c3fa80a323cc5` with 8 tests / 29 assertions;
Inga-node PR #14 merged as `125d80e062f3aee80c730fe24d170fb3fc55f775` with
54 tests / 224 assertions, including the focused endpoint suite at 15 tests /
78 assertions. The current worktree now contains an Inga-backed profile loader
and bounded multi-witness transport wired into install-scope resolution; it
requests the exact domain-binding and app-release refs, verifies signed quorum
reads and three-chain event finality, then repeats proof collection after
content staging before writing an install lock. This source has not been loaded
against its full pinned dependency closure or deployed. The lifecycle contract
keeps DNS freshness in seconds and Amano current-ref freshness in milliseconds;
the prior focused app suite passed 14 tests / 39 assertions under Nbb. A node
response alone is one witness observation, not quorum or exact-event finality
evidence. Production content
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
The merged source is not a deployed runtime. GitHub metadata was read back on
2026-10-16 after the draft follow-up PRs had been closed without merging.

| Foundation layer | Current source and review state | Evidence available | Remaining qualification |
|---|---|---|---|
| Itonami app host | [`cloud-itonami-app#431`](https://github.com/cloud-itonami/cloud-itonami-app/pull/431) merged on 2026-10-16 as `e708d000e6ff634a9368fbb3a832867db1d7e6fe`. Common-foundation follow-up [PR #432](https://github.com/cloud-itonami/cloud-itonami-app/pull/432) is **closed, unmerged, and still marked draft**. Its remote head is `f4f30f33fdb7bba8a943d5408405740891ce70d5`; this checkout has additional uncommitted source, inventory, and benchmark changes. CI and branch-scan evidence below refer to the earlier head `2a3c45c3`. | Focused app lifecycle suite: 14 tests / 39 assertions passed under Nbb. On 2026-10-16 the current host source dependency-loaded and served `/health`, `/`, and `/awai-launcher.js` with HTTP 200 on loopback; `/api/awai/app-registry` returned 503 `unconfigured`. A branch-range Gitleaks scan at head `2a3c45c3` covered all eight PR commits and found no leaks. CI run 763 passed `amu-jvm-free-emit`, `awai-public-gateway`, and `windows-launch-smoke`; the repository-wide `secret-scan` failed. Run 762 reported seven history findings across 1,233 commits. | Current-ref transport is source-wired, but a production chain profile is uncreated and peer quorum verification was not exercised. The broader JVM suite remains unverified. The historical secret findings need separate triage; they are outside the PR's branch range. |
| Bootstrap HTTPS ingress | [`deploy/awai-public-gateway`](deploy/awai-public-gateway), included in closed-unmerged PR #432; this checkout adds explicit hostname allowlisting and alias-focused launch behavior. | `npm test` passes 18 Node tests, including Cloudflare-host rejection. A local HTTPS smoke with a disposable SAN certificate for `itonami.app` and `murakumo.cloud` passed alias SNI, cross-host rejection, and same-origin install-plan forwarding against a fake upstream. The gateway exposes only launcher, registry read, DNS proof, and plan preview. | Direct public DNS, a public certificate, independent host operations, registry freshness, and external readback remain unverified. Public app content delivery and capability operations are not implemented at the gateway. |
| Awai registry and peer host | [`network-awai-apex#19`](https://github.com/network-awai/network-awai-apex/pull/19), **closed, unmerged, and still marked draft** at head `95b7ae601e1665836e725f1e577e670930150be7`. | All 16 `app-registry --host` gates passed, including 32 vectors on three targets (96/96), peer-checkpoint replication, and G15. The peer gateway has six policy tests and a disposable-certificate loopback HTTPS smoke. Current Wasm SHA-256: `15692fa9f19cc322d8ee8b7294f822234db0794f25bcc9c1654da4310f2f2610`. | G14 uses two processes on one host over loopback; independent operators, public peer discovery, NAT traversal, production DNA, and node-loss recovery remain unqualified. The HTTPS gateway has no public host, certificate, or DNS readback. |
| Amano lifecycle refs | Inga [PR #30](https://github.com/kotoba-lang/inga/pull/30) merged to `main` as `cb87cdb38d73e24e9ec0c454360c3fa80a323cc5`; its challenge-bound current-ref quorum reader passes 8 tests / 29 assertions. Inga-node [PR #14](https://github.com/kotoba-lang/inga-node/pull/14) merged as `125d80e062f3aee80c730fe24d170fb3fc55f775`; `GET /current-ref` returns one node-signed committed projection and its full suite passes 54 tests / 224 assertions, including 15 tests / 78 focused endpoint assertions. The node also serves an immutable finalized-prefix checkpoint when the caller supplies its CID. Itonami's current worktree adds the profile loader, transport, and route connection for signed quorum reads, commitment membership, and the complete three-chain prefix from trusted genesis; the source now dependency-loads in the local Node host. | The endpoint rejects malformed or duplicate query parameters and will not report a head unless its stored commitment proof is present. The local app-registry route returns 503 `unconfigured`, so signed quorum and finalized-prefix verification were not exercised. An earlier same-day read-only probe reached w4 and found no Murakumo domain/app heads. A fresh read-only check from this host could not connect to local Tailscale and reached 0/4 witness health endpoints. | The user confirmed on 2026-10-16 that the authoritative production app chain profile has not been created. The host accepts a profile via `ITONAMI_AWAI_AMANO_PROFILE` and performs bounded multi-witness reads, but checkpoint-CID discovery/refresh, reachable witness APIs, and a verified production chain profile remain outstanding. The fresh reachability failure describes this host's access only; it does not establish that remote witness processes are down. No app-domain binding or release is established on this chain. |
| App-to-cell capability contract | [`kotoba-protocol#16`](https://github.com/kotoba-lang/kotoba-protocol/pull/16), merged on 2026-10-16 as `3aadfbcb31c4e995276993af9c0d80520b33c1b8` | The focused `protocol_test` namespace passed 96 tests and 576 assertions; direct local checks confirmed read remains grantable and write is denied without a trusted label. | The full portable suite remains unverified because unchanged `credential_test.cljk` fails on both the merged-base predecessor and the earlier PR head. No public runtime uses this contract yet. |

The Itonami host consumes the merged protocol contract and Awai registry
projection. Awai integration follow-ups, independent peer operations,
production signed releases and content origins, public DNSSEC and TLS, and
external readback remain outstanding before `itonami.app` can serve as the
common public bootstrap. PR #432 is closed without merging; its gateway has no
production host or certificate. The source follow-up and product Kotoba/AMU
changes remain in local worktrees and are not merged or deployed. No migration
cohort hostname has an accepted Awai domain binding, and no public domain has
been switched to this runtime. `murakumo.cloud` stays the first product pilot
after these shared gates pass.

The 2026-10-17 Kotoba/AMU refresh confirms the eight product decision cores
and shared install-admission core from source-matched native benchmarks. The
native selfhost target is currently `aarch64-macos`; seven product callsites
still use bootstrap-emitted WASM wrappers, so this does not qualify complete
product builds or production deployment. The current-ref core also rebuilds
with AMU selfhost, but its fresh timing run was host-contended and is excluded
from the usable comparison. See
[`awai-kotoba-amu-selfhost-refresh-2026-10-17.md`](benchmarks/awai-kotoba-amu-selfhost-refresh-2026-10-17.md).

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
Worker custom domains, 16 Pages projects, 30 route patterns, 31 Tunnels, and
26 Registrar registrations in `cloud-kotoba`; `fund-mithril` has 3 active
zones, 36 Worker scripts, 23 Worker custom domains, 6 route patterns, and 3
Registrar registrations; `com-junkawasaki` has no zones, Workers, Pages
projects, Tunnels, or Registrar registrations.
The Pages API's paginated read returned 16 projects (10 on page 1 and 6 on
page 2). This inventory is account-scoped and is not a complete list of
registrar delegations or public origins.

All 27 `cloud-kotoba` zones below were individually rechecked and returned
`status: disabled` from their Cloudflare `/dnssec` API endpoint. The ten
migration cohort zones were individually rechecked at 10:04:45 UTC.
Consequently the proposed `_itonami-app.<domain>` DNSSEC proof cannot be
qualified against any of them in the current configuration. Every zone also
needs registrar/parent DS and independent authoritative-resolver readback; the
Cloudflare API setting alone is not public DNS proof. All resource counts in
the table below were re-read on 2026-10-16; per-domain public record and route
descriptions remain subject to independent DNS/HTTP readback.

| Zone | DNS records | Worker custom domains | Worker routes | Pages custom domains | Cloudflare DNSSEC |
|---|---:|---:|---:|---:|---|
| `itonami.app` | 16 | 1 | 1 | 0 | disabled |
| `murakumo.cloud` | 28 | 14 | 10 | 0 | disabled |
| `aozora.app` | 12 | 7 | 0 | 0 | disabled |
| `oppai.fans` | 0 | 0 | 0 | 0 | disabled |
| `isekai.network` | 6 | 0 | 0 | 1 | disabled |
| `itonami.cloud` | 32 | 15 | 4 | 2 | disabled |
| `x402.nexus` | 7 | 1 | 0 | 0 | disabled |
| `shinshi.club` | 10 | 1 | 0 | 0 | disabled |
| `babiniku.net` | 6 | 0 | 0 | 1 | disabled |
| `awai.network` | 9 | 2 | 0 | 0 | disabled |

The focused zone/DNS/Worker/Pages read at `2026-10-16T07:00:48Z`, followed by
the `kekkai-control` Tunnel ingress read completed at `2026-10-16T07:32:15Z`,
confirmed the serving-resource mapping below. DNS record counts include mail
records; this is provider configuration evidence, not public HTTP or mail-flow
verification.

| Zone | Current serving resources and unresolved route state |
|---|---|
| `itonami.app` | Worker `ossekai-arb-intake` at `arb.ossekai.itonami.app`; route `kaisya.itonami.app/*`; proxied wildcard; 13 `_dnslink` TXT records. No apex A/AAAA record. |
| `murakumo.cloud` | 14 Worker custom domains and 10 Worker routes; `gateway`/`infer` use healthy `comfyui-gad` Tunnel; `origin-generation` uses a down Tunnel. Three CNAMEs target Tunnel hosts. `kekkai-control.murakumo.cloud` is a proxied CNAME for the healthy `kekkai-control-plane` Tunnel, which routes heartbeat and signed netmap paths to local services. Public HTTP behavior and Access enforcement were not verified. |
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
| `murakumo.cloud` | 14 Worker domains include the apex and `www`, plus `api`, `console`, `factoring`, `generation`, `ipfs-pilot`, `local`, `meter`, `operator`, `setup`, `status`, `toshokan`, and `web`. `local` maps to `murakumo-client-static`; `ipfs-pilot` is served by `murakumo-ipfs-gateway-pilot`; its `_dnslink` TXT points to IPNS name `k51qzi5uqu5dlux8z4nuviwt8ww812ujkb4v1sxm91d630bjrbhsjeftp7t2iu`. Ten Worker routes cover node registration, Commons/legal content, LLM documents, and ads/MCP. `gateway` and `infer` point to the healthy `comfyui-gad` Tunnel; `origin-generation` points to a down Tunnel whose ingress config does not include that hostname. The separate healthy `kekkai-control-plane` Tunnel has `kekkai-control.murakumo.cloud` ingress patterns for `/v1/nodes/<id>/heartbeat` and `/v1/nodes/<id>/netmap`, backed by local ports 17892 and 17893. Its public HTTP and Access behavior were not verified. | The live app provides chat, inference and generation, alongside device registration, content, status, and advertising APIs. This is the selected first migration domain. Replace each hostname and route only after its service contract and independent-node read/write behavior are evidenced. Preserve mail records. |
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

The account-level `04-feasts-minded.workers.dev` suffix is used by
`cloud-kotoba`; `fund-mithril` uses `cloud-kotoba.workers.dev`. The 10/17
settings read covers all 145 scripts: 44 of 109 `cloud-kotoba` scripts and 28
of 36 `fund-mithril` scripts have stable `workers.dev` hosts. Preview URLs are
enabled for 42 and 26 scripts in those accounts, respectively. The 72 stable
hosts and 68 preview URL patterns are listed separately in the current runtime
snapshot; the companion settings CSV records all enabled and disabled scripts.
These names remain Cloudflare runtime surfaces even after a custom domain is
moved, and services outside the product cohort still need an owner/scope
decision. The `oppai-fans` script has both its stable `workers.dev` host and
preview URLs disabled. See the official
[Workers.dev routing documentation](https://developers.cloudflare.com/workers/configuration/routing/workers-dev/)
and [version URL guide](https://developers.cloudflare.com/workers/versions-and-deployments/version-urls/).

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

The 2026-10-16 Tunnel configuration read covered all 31 tunnels. It reports
`comfyui-gad` healthy for `gateway.murakumo.cloud` and `infer.murakumo.cloud`,
`gftd-llm` healthy for `ollama.gftd.ai`, `llm.gftd.ai`, `relay.gftd.ai`,
`murakumo-voice.gftd.ai`, and `gateway.gftd.ai`, and `kekkai-control-plane`
healthy for the heartbeat and netmap path patterns at
`kekkai-control.murakumo.cloud`. The latter is Tunnel configuration status;
public HTTP response and Access enforcement were not checked. `itonami-5820` is down but
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
31 current Tunnel configurations. Preserve these as unresolved DNS
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

## Murakumo pilot current readback (2026-10-16)

A fresh read-only API check confirms `murakumo.cloud` is still an active
Cloudflare zone on Cloudflare nameservers. It has 28 DNS records: 14 proxied
AAAA records, four proxied Tunnel CNAMEs, three MX records, and seven TXT
records. The TXT set includes the `_dnslink.ipfs-pilot` record and mail
authentication records. The account also has 14 enabled production Worker
custom domains and 10 Worker routes for this zone. These records are separate
service surfaces; an apex app alias cannot replace the whole zone safely.

The four Tunnel CNAME hosts are `gateway`, `infer`, `kekkai-control`, and
`origin-generation`. The 14 Worker-domain hosts are the apex, `www`, `api`,
`console`, `factoring`, `generation`, `ipfs-pilot`, `local`, `meter`,
`operator`, `setup`, `status`, `toshokan`, and `web`. Ten path routes cover
device-link registration, Commons and legal-node content, three LLM documents,
and four advertising/MCP paths. The apex and `www` currently map to
`murakumo-cloud`; the root page reader returned the live Murakumo chat UI on
this check. That read confirms page content only, not HTTP status, TLS
identity, API behavior, or the underlying origin. The local `dig` query could
not bind a DNS socket in this environment, so public parent delegation and
direct A/AAAA answers remain unverified.

The first safe stage is a dedicated direct-host pilot with independent DNS and
TLS, followed by comparison of the existing chat UI and its write paths. Keep
the current apex, `www`, API, operator, generation, IPFS, Tunnel, route, and
mail surfaces in service until their replacements pass per-host read/write,
authorization, and rollback checks. The common launcher now gives a public
candidate a domain-scoped link to the user's local Itonami host; local
installation independently reads its Awai peer and requires its own chain
profile. The handoff tests are local DOM/query tests only: this workspace had
no listener on `127.0.0.1:1338` and the loopback request was refused, so browser
navigation to a running local host remains unverified. The production profile,
public gateway host, certificate, and DNS are still absent. No provider
settings changed during the readback.

## Murakumo Tunnel and DNSSEC recheck (2026-10-17 JST)

A read-only API pass ending at `2026-10-16T16:24:59Z` read the current
`murakumo.cloud` DNSSEC status and each of its four CNAME-referenced Tunnel
configurations without retaining DNS targets or Tunnel service URLs. DNSSEC
remains disabled. `gateway` and `infer` match ingress on healthy
`comfyui-gad`; `kekkai-control` matches the two heartbeat/netmap routes on
healthy `kekkai-control-plane`. `origin-generation` references the down
`yukkuri-gemma-gad` Tunnel, whose current ingress has no matching hostname.
This is an unresolved alias/service-owner state, not proof of external HTTP
failure. Sanitized row evidence is in
[`awai-murakumo-tunnel-dnssec-recheck-2026-10-17.csv`](awai-murakumo-tunnel-dnssec-recheck-2026-10-17.csv).

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

### Registrar registration snapshot (2026-10-17 JST)

A read-only Cloudflare API refresh at 2026-10-16T15:56:27Z returned all ten
initial migration domains as `active`, with
`auto_renew=true`, `locked=true`, and `privacy_mode=redaction`. Their current
registration expirations by UTC date are:

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
The same read found all ten Cloudflare zones `active` with DNSSEC `disabled`.
Exact expiration timestamps and registrar/DNSSEC states are preserved in the
[sanitized recheck CSV](awai-app-registrar-recheck-2026-10-17.csv). A transfer
to an independent registrar remains necessary before the selected direct-DNS,
DNSSEC-signed gateway route can replace Cloudflare nameservers.

## Account-wide public hostname and deployment refresh (2026-10-16)

A complete read-only pass across the three connected Cloudflare accounts
returned the following account-level surface. The `workers.dev` and preview
columns come from the per-script subdomain endpoint, not Worker settings that
contain environment bindings.

| Account | Active zones | Worker scripts | Worker custom domains | Zone routes | `workers.dev` / previews | Pages projects / preview deployments | Tunnels |
|---|---:|---:|---:|---:|---:|---:|---:|
| `cloud-kotoba` | 27 | 109 | 78 | 30 | 44 / 42 | 16 / 108 | 31 |
| `fund-mithril` | 3 | 36 | 23 | 6 | 28 / 26 | 0 / 0 | 0 |
| `com-junkawasaki` | 0 | 0 | 0 | 0 | 0 / 0 | 0 / 0 | 0 |
| **Total** | **30** | **145** | **101** | **36** | **72 / 68** | **16 / 108** | **31** |

The 31 `cloud-kotoba` tunnels have 6 healthy and 25 down statuses, with 92
configured ingress entries: 62 hostname/path rules and 30 catch-all rules.
The count includes repeated hostnames for separate rules. The normalized public
runtime CSV records the 62 hostname/path entries; the 30 catch-all rules have
no public hostname and are omitted. Down tunnels remain in the inventory while
their DNS or ingress names exist.

The active zones contain 361 DNS records across 21 zones, with 292 unique
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
is the 528-row historical snapshot: 101 Worker custom domains, 36 Worker
route patterns, 145 Worker script settings, 23 Pages production hostnames,
161 Pages preview URLs and aliases, and 62 hostname/path Tunnel ingress rules.
It included disabled Worker subdomain settings as if they were public hosts.
The corrected current snapshot is
[`awai-cloudflare-runtime-endpoints-2026-10-17.csv`](awai-cloudflare-runtime-endpoints-2026-10-17.csv):
72 enabled `workers.dev` hosts plus 68 separately represented preview URL
patterns. All 145 settings and the enabled/preview flags are in
[`awai-cloudflare-workers-dev-recheck-2026-10-17.csv`](awai-cloudflare-workers-dev-recheck-2026-10-17.csv).
Provider configuration and deployment presence do not prove external HTTP
access or identify an app owner for every service. The current read also
includes `itonami-client-static`, `murakumo-client-static`, and the active
`local.itonami.cloud` and `local.murakumo.cloud` custom domains. The two
`local.*` custom domains remain public runtime surfaces even though their
`workers.dev` names are disabled.

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
custom domains, 30 Worker route patterns, 16 Pages projects, and 31 Tunnels in
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
| `murakumo.cloud` | 28 | 14: apex and `www` → `murakumo-cloud`; `api` → `local-murakumo`; `console` and `operator` → `murakumo-operator`; `factoring` → `cloud-itonami-isic-6493-factoring`; `generation` → `murakumo-generation-proxy`; `ipfs-pilot` → `murakumo-ipfs-gateway-pilot`; `local` → `murakumo-client-static`; `meter` → `murakumo-meter`; `setup` → `murakumo-setup`; `status` → `murakumo-status`; `toshokan` → `murakumo-toshokan`; `web` → `murakumo-web`. A separate healthy `kekkai-control-plane` Tunnel serves heartbeat and netmap path patterns at `kekkai-control.murakumo.cloud`; public HTTP and Access behavior remain unverified. | 10 | 0 |
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

The latest Itonami/Awai migration catalog has 3,476 rows across 33 domain
groups, including 523 runtime endpoints, 361 DNS metadata rows, 2,581 Pages
deployment candidates, and 11 review rows: two root-only readbacks, one
source/provider mismatch, and eight zone-only reviews.
DNS rows are control-plane records, not HTTP endpoints; their contents and
targets are omitted. The catalog is in
[`awai-app-public-endpoint-migration-2026-10-17.csv`](awai-app-public-endpoint-migration-2026-10-17.csv).
It also records the source-declared but currently unrouted `oppai.fans` app.
The generated ownership map and 33-domain summary are in
[`awai-app-service-ownership-2026-10-17.csv`](awai-app-service-ownership-2026-10-17.csv)
and [`awai-app-domain-ownership-summary-2026-10-17.csv`](awai-app-domain-ownership-summary-2026-10-17.csv).
The summary now separates runtime endpoints, Pages deployment candidates,
DNS records, and migration review rows for each domain. Its service counts
include provider-exposed services; root readbacks and source-declared/no-route
entries remain migration evidence rather than being counted as live services.
The readable queue for all 33 groups is in
[`migrations/awai-public-service-migration-roadmap-2026-10-17.md`](migrations/awai-public-service-migration-roadmap-2026-10-17.md).
The selected first
pilot, `murakumo.cloud`, currently has 30 runtime endpoint rows, 28 DNS record
rows, and 18 distinct services in the catalog; it is a multi-service migration,
not an apex-only website change.
The service-by-service scope, DNS dependencies, and staged proof plan are in
[`migrations/murakumo-cloud-first-cohort.md`](migrations/murakumo-cloud-first-cohort.md).
They separate 84 canonical source matches from 13 product-owner hints, 5
repository-name candidates, 6 ambiguous config matches, and 66 owner-review
items. Service-source matches do not establish product app identities, signed
releases, or domain bindings.

### Read-only account refresh and orphan Worker domains (2026-10-16)

A complete API read confirmed 27 active zones, 109 Worker scripts, 78 Worker
custom domains, 318 DNS records (168 proxied), 30 routes, 16 Pages projects,
and eight Pages custom-domain mappings in `cloud-kotoba`. `fund-mithril` has
three active zones, 36 Worker scripts, 23 Worker custom domains, 43 DNS
records (23 proxied), and six routes; it has no Pages projects. Across both
accounts that is 30 active zones, 145 Worker scripts, 101 Worker custom
domains, 361 DNS records, and 36 routes. The earlier partial zone listing was
incomplete; the full read agrees with the 27-zone count above.

The Pages project list and every project's custom-domain list were read again.
All 16 projects are present and there are eight mappings total: seven active
(`babiniku.net`, `isekai.network`, `itonami.cloud`,
`cloud-itonami.itonami.cloud`, `bots.kotoba.cloud`,
`comics.spirit-in-physics.org`, and `gorigori-ketsu-dasshu.gftd.ai`) plus the
deactivated `cyber-drill.gftd.ai`. The earlier count of seven attachments
referred to active mappings.

Nine active zones have no DNS records, Worker routes, Worker custom domains,
or Pages custom-domain mappings. The three enabled `spirit-in-physics.com`
Worker-domain objects have no matching Cloudflare zone object (exact-name
lookup returned no zone and their stored zone ID was invalid), and no Pages
mapping. These are provider configuration findings only: external delegation
and HTTP reachability remain unverified. The row-level evidence is in
[`awai-cloudflare-empty-zones-and-orphan-worker-domains-2026-10-16.csv`](awai-cloudflare-empty-zones-and-orphan-worker-domains-2026-10-16.csv).
No Cloudflare setting, DNS record, or route was changed during this refresh.

### First-cohort public root and attachment refresh (2026-10-16)

Read-only Cloudflare API calls refreshed DNS records, DNSSEC state, Worker
custom domains, and Worker routes for the ten first-cohort zones. DNSSEC is
disabled for all ten. I also issued body-discarding HTTPS GETs to each apex.
Eight returned HTTP 200 through `2606:4700::` Cloudflare anycast addresses;
that proves the current public request reaches Cloudflare, not which origin
serves it. `itonami.app` and `oppai.fans` did not resolve for HTTPS.

Google Public DNS-over-HTTPS independently returned no A or AAAA answers for
the `itonami.app` and `oppai.fans` apexes. Both return Cloudflare NS records
and no DS answer. Cloudflare's zone API shows 16 DNS records on `itonami.app`
but none at the apex; its one Worker custom domain is
`arb.ossekai.itonami.app`, with a separate `kaisya.itonami.app/*` route.
`oppai.fans` has zero DNS records, Worker custom domains, Worker routes, or
Pages mappings. Its source-declared app remains unrouted. Row-level root
readback is in
[`awai-app-public-root-readback-2026-10-16.csv`](awai-app-public-root-readback-2026-10-16.csv).

This confirms that `itonami.app` is not currently reachable as the common
public launcher origin, and `oppai.fans` is not currently a public service.
The public roots that do return 200 still rely on Cloudflare edge delivery.
No origin cutover or DNS change was made.

### Latest Cloudflare and Registrar API recheck (2026-10-16T17:43:46Z)

A read-only pass across all three connected accounts completed successfully. It returned 30 active zones, 361 DNS records (191 proxied), 36 Worker routes, 101 Worker custom domains, 145 Worker scripts, 16 Pages projects with 8 custom-domain mappings, and 31 Tunnels (6 healthy and 25 down). DNSSEC was disabled on all 30 zones. Per-account counts are appended to [the resource recheck CSV](awai-cloudflare-resource-recheck-2026-10-17.csv); per-zone counts and DNSSEC state are appended to [the zone recheck CSV](awai-cloudflare-zone-recheck-2026-10-17.csv).

The Registrar API returned 29 active, locked registrations. 24 have auto-renew enabled; five are disabled: nubatama.net, manimani.cloud, kotoba.digital, yatalake.com, kotoba-labs.com. All ten current product/platform domains in the targeted set are active, locked, and set to auto-renew. Row-level records are in [the new 29-domain registrar snapshot](awai-cloudflare-registrar-recheck-2026-10-17.csv); the ten target-domain rows are also appended to [the targeted registrar recheck](awai-app-registrar-recheck-2026-10-17.csv).

This confirms Cloudflare configuration only. It does not establish parent delegation, public DNS answers, origin health, or registrar-transfer eligibility. No Cloudflare or registrar setting was changed.

### Live full-surface identity recheck (2026-10-17 JST)

A read-only refresh across the same three connected accounts completed at
`2026-10-16T19:20:50Z`, with no API read errors. Exact normalized row-identity
sets matched the saved inventories for all nine rechecked surfaces: 30 zones,
361 DNS records, 36 Worker routes, 101 Worker custom domains, 145
Workers.dev settings, 23 Pages project hostnames, 161 Pages preview aliases,
2,581 Pages production-deployment candidates, and 62 Tunnel host/path ingress
rules. The per-surface SHA-256 identities and comparison baselines are in
[`awai-cloudflare-live-fingerprint-recheck-2026-10-17.csv`](awai-cloudflare-live-fingerprint-recheck-2026-10-17.csv).

This is provider configuration and inventory parity evidence. It does not
prove that requests depend on or avoid Cloudflare, that configured origins are
healthy, or that public services can operate independently. No DNS, runtime,
or registrar setting changed in this read-only recheck.

### Fresh cross-account resource readback (2026-10-17 JST)

A read-only API pass at `2026-10-16T19:51:05Z` rechecked the identity sets for
zones, DNS record host/type/proxy state, Worker routes, Worker custom domains,
Workers.dev settings, Pages hostnames, and Tunnel objects. All seven SHA-256
sets exactly match the `2026-10-16T18:52:37Z` inventory. The current account
distribution is 27 zones / 318 DNS records / 30 routes / 78 Worker domains /
109 Workers.dev settings / 23 Pages hostnames / 31 Tunnels in `cloud-kotoba`,
and 3 / 43 / 6 / 23 / 36 / 0 / 0 in `fund-mithril`; the same queried surfaces
returned zero rows for `com-junkawasaki`. See
[`awai-cloudflare-live-inventory-recheck-2026-10-17.csv`](awai-cloudflare-live-inventory-recheck-2026-10-17.csv)
and [`awai-cloudflare-account-resource-counts-2026-10-17.csv`](awai-cloudflare-account-resource-counts-2026-10-17.csv).
Pages preview aliases, Pages production-deployment candidates, and Tunnel
host/path ingress were not re-enumerated in this pass; their last full-set
readback remains the `19:20:50Z` snapshot above.

These are configuration identities. They establish neither public traffic
behavior nor origin health, and no provider setting changed.

### Recursive DNS readback for remaining named domain groups (2026-10-17 JST)

Google Public DNS-over-HTTPS was queried for NS, A, and AAAA on all 31 named
domains in the ownership summary. Thirty returned Cloudflare nameservers; 17
also returned Cloudflare edge addresses at the apex. Eight
zones with no configured records still return Cloudflare NS but no apex A or
AAAA answer. `gftd.ai`, `kotobase.net`, and `yataverse.com` also have no apex
address while subdomain services remain in the endpoint catalog.
The product roots `itonami.app` and `oppai.fans` also have no apex A/AAAA answer;
both remain delegated to Cloudflare nameservers. `spirit-in-physics.com`
returned NXDOMAIN for all three query types despite its configured Worker
custom-domain rows. No matching registration appears in the connected
Cloudflare Registrar snapshot, so its external registration/delegation owner
remains unresolved. `spirit-in-physics.org` resolves to Cloudflare edge addresses.
The per-domain answers are in
[`awai-app-public-root-dns-readback-2026-10-17.csv`](awai-app-public-root-dns-readback-2026-10-17.csv).

These are recursive-resolver observations, not direct authoritative-server
queries or HTTP health checks. The eight empty-zone domains remain delegation
and retention reviews; the NXDOMAIN domain needs registrar and owner-state
reconciliation before any redirect, retirement, or app binding decision.

### Browser root readback for additional resolved domains (2026-10-17 JST)

Direct hidden-browser reads returned homepage content for `etzhayyim.com`,
`kotoba-lang.org`, `kotoba.cloud`, `kotoba.foundation`, `manimani.cloud`,
`mithril.fund`, and `spirit-in-physics.org`. `kotobalabs.com` navigated to the
`www.kotoba-lang.org` canonical host. `yaoi.dev` displayed an `internal error`
body. Browser navigation did not expose raw HTTP status or origin identity;
these observations classify visible content only. The expanded row-level
readback is in
[`awai-app-public-root-browser-probe-2026-10-17.csv`](awai-app-public-root-browser-probe-2026-10-17.csv).
