# Awai / Itonami public-service migration roadmap

Snapshot: 2026-10-17 JST. Generated from the normalized public-endpoint
catalog and service-owner map. This is the migration queue and evidence
boundary; it does not claim any route has moved or authorize DNS/provider
changes.

## Inventory closure

- 33 domain groups cover 3,476 catalog rows: 523 runtime/provider rows, 361 DNS-record rows, and 2,581 Pages deployment candidates.
- The grouped domain rows contain 199 service-key assignments (some service keys appear in multiple host groups). The owner map has 174 distinct services: 84 resolved and 90 still needing owner/source review.
- The union of provider service keys across all domain groups exactly matches the 174-service owner map.
- Domain classes: account-zone-no-service=8, first-product-cohort=10, other-public-domain=13, platform-hostname=2.
- Surface counts: active-zone-no-service=8, dns-record=361, pages-preview=161, pages-production=23, pages-production-deployment=2,581, public-root-readback=2, source-declared-no-public-route=1, tunnel-ingress=62, worker-custom-domain=101, worker-route=36, workers.dev=72, workers.dev-preview=68.
- All Pages deployment rows remain reachability candidates until an
  independent HTTP readback proves otherwise. DNS-record rows are
  control-plane metadata, not application endpoints.

## Per-domain migration queue

### `aozora.app`

**Class / stage:** `first-product-cohort` / `common-foundation-and-product-app-qualification`

**Inventory:** 27 rows; 15 runtime/provider; 0 Pages deployment candidates; 12 DNS records; 5 services.

**Owner evidence:** 5 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 0 owner review.

**Service keys:** app-aozora-appview;app-aozora-auth;app-aozora-live;app-aozora-pds;app-aozora-spa

**Next action:** Migrate AT Protocol PDS, AppView, auth, feeds, blobs and live media with repo/write/read receipts; plan credential renewal for the new origin.

### `awai.network`

**Class / stage:** `first-product-cohort` / `common-foundation-and-product-app-qualification`

**Inventory:** 11 rows; 2 runtime/provider; 0 Pages deployment candidates; 9 DNS records; 1 service.

**Owner evidence:** 1 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 0 owner review.

**Service keys:** network-awai-apex

**Next action:** Preserve the public Basho/Hokusai/Giemon disclosures and Murakumo service handoff; keep unreleased model claims explicit while qualifying chat, image/video jobs, streaming, and artifact reads across independent nodes.

### `babiniku.net`

**Class / stage:** `first-product-cohort` / `common-foundation-and-product-app-qualification`

**Inventory:** 295 rows; 57 runtime/provider; 232 Pages deployment candidates; 6 DNS records; 1 service.

**Owner evidence:** 1 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 0 owner review.

**Service keys:** net-babiniku

**Next action:** Preserve room/chat state and live-broadcast behavior; reconcile production asset storage, migrate generated assets, and prove ingest/playback without Cloudflare Stream or Worker dependencies.

### `etzhadaat.org`

**Class / stage:** `account-zone-no-service` / `external-delegation-and-retention-review`

**Inventory:** 1 row; 0 runtime/provider; 0 Pages deployment candidates; 0 DNS records; 0 services.

**Owner evidence:** 0 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 0 owner review.

**Service keys:** No service mapped in the connected-account snapshot.

**Next action:** Confirm registrar delegation and recursive public DNS/HTTP. This active zone has zero configured DNS records and no runtime endpoint row; decide whether an external service exists, should be assigned to an app, or should be retained.

### `etzhayyim.com`

**Class / stage:** `other-public-domain` / `service-owner-and-product-boundary-review`

**Inventory:** 40 rows; 14 runtime/provider; 0 Pages deployment candidates; 26 DNS records; 9 services.

**Owner evidence:** 1 resolved / 2 owner hints / 1 candidates / 0 ambiguous / 5 owner review.

**Service keys:** etzhayyim-anvil;etzhayyim-did-web;etzhayyim-pds;etzhayyim-xrpc-proxy;itonami-5820;itonami-agent;kotoba-etzhayyim;ossekai-arb-intake;sip

**Next action:** Resolve pending service owners and candidate repo; classify Itonami, Murakumo and AT Protocol aliases, then set canonical-host or redirect decisions.

### `gftd.ai`

**Class / stage:** `other-public-domain` / `service-owner-and-product-boundary-review`

**Inventory:** 146 rows; 57 runtime/provider; 1 Pages deployment candidates; 88 DNS records; 31 services.

**Owner evidence:** 5 resolved / 0 owner hints / 2 candidates / 0 ambiguous / 24 owner review.

**Service keys:** blockscout-explorer;bpmn-dispatcher;bpmn-dispatcher-local-20260514093458;cisa-forge;engi;genko-gftd;geth-rpc;gftd-llm;gorigori-ketsu-dasshu;goriketsu-proxy;intel-blockchain;ipfs-origin;kagami-query;kami-app-daw;kami-app-nle;keiei-llm;kotoba-etzhayyim;kotobase;magatama-hg7g75wa;magatama-y5kk5r1x;mangaka-gftd-redirect;mangaka-reader;maps-embedder;maps-langserver;medical-coverage-mcp;murakumo-fleet;ollama-gftd;risingwave-private;yata-query;yukkuri-gemma-gad;yukkuri-renderer

**Next action:** Resolve wildcard and Tunnel-backed services individually; partition product apps from shared compute/data adapters and trace each hostname to a service owner.

### `gftd.cloud`

**Class / stage:** `account-zone-no-service` / `external-delegation-and-retention-review`

**Inventory:** 1 row; 0 runtime/provider; 0 Pages deployment candidates; 0 DNS records; 0 services.

**Owner evidence:** 0 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 0 owner review.

**Service keys:** No service mapped in the connected-account snapshot.

**Next action:** Confirm registrar delegation and recursive public DNS/HTTP. This active zone has zero configured DNS records and no runtime endpoint row; decide whether an external service exists, should be assigned to an app, or should be retained.

### `isekai.network`

**Class / stage:** `first-product-cohort` / `common-foundation-and-product-app-qualification`

**Inventory:** 376 rows; 64 runtime/provider; 306 Pages deployment candidates; 6 DNS records; 1 service.

**Owner evidence:** 1 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 0 owner review.

**Service keys:** network-isekai

**Next action:** Verify the pinned static CID independently, then migrate D1 scores, social rooms, generation and game writes with import and write receipts.

### `itonami.app`

**Class / stage:** `first-product-cohort` / `common-foundation-and-product-app-qualification`

**Inventory:** 19 rows; 2 runtime/provider; 0 Pages deployment candidates; 16 DNS records; 2 services.

**Owner evidence:** 1 resolved / 1 owner hints / 0 candidates / 0 ambiguous / 0 owner review.

**Service keys:** cloud-itonami-kaisya;ossekai-arb-intake

**Next action:** Qualify the common launcher and peer-backed registry/content path; map wildcard, kaisya, arb and _dnslink aliases before it becomes the resolver for other apps.

### `itonami.cloud`

**Class / stage:** `first-product-cohort` / `common-foundation-and-product-app-qualification`

**Inventory:** 2,099 rows; 54 runtime/provider; 2,013 Pages deployment candidates; 32 DNS records; 22 services.

**Owner evidence:** 12 resolved / 7 owner hints / 0 candidates / 3 ambiguous / 0 owner review.

**Service keys:** app-mail-relay;cloud-itonami;cloud-itonami-agent-edge;cloud-itonami-commitment-ledger;cloud-itonami-hanmoto;cloud-itonami-isic-6492;cloud-itonami-mcp-edge;cloud-itonami-partners;itonami-5820;itonami-agent-treasury-tokyo2026;itonami-app-auth;itonami-client-static;itonami-cloud-webhooks;itonami-dougaka;itonami-ethglobal-tokyo-demo;itonami-fleet-dispatch;itonami-grok-bots;itonami-media-kouryaku;itonami-office;itonami-public-bots-status;kenbun;mangaka-reader

**Next action:** Preserve WebAuthn account/session continuity and migrate D1, KV, Durable Object and service bindings; qualify the new RP origin and credential renewal.

### `kotoba-labs.com`

**Class / stage:** `account-zone-no-service` / `external-delegation-and-retention-review`

**Inventory:** 1 row; 0 runtime/provider; 0 Pages deployment candidates; 0 DNS records; 0 services.

**Owner evidence:** 0 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 0 owner review.

**Service keys:** No service mapped in the connected-account snapshot.

**Next action:** Confirm registrar delegation and recursive public DNS/HTTP. This active zone has zero configured DNS records and no runtime endpoint row; decide whether an external service exists, should be assigned to an app, or should be retained.

### `kotoba-lang.org`

**Class / stage:** `other-public-domain` / `service-owner-and-product-boundary-review`

**Inventory:** 13 rows; 2 runtime/provider; 0 Pages deployment candidates; 11 DNS records; 1 service.

**Owner evidence:** 0 resolved / 0 owner hints / 0 candidates / 1 ambiguous / 0 owner review.

**Service keys:** kotoba-lang-org

**Next action:** Resolve the duplicate Wrangler-name repositories and publisher; choose the canonical site source and assign an app identity before packaging.

### `kotoba.cloud`

**Class / stage:** `other-public-domain` / `service-owner-and-product-boundary-review`

**Inventory:** 45 rows; 18 runtime/provider; 0 Pages deployment candidates; 27 DNS records; 5 services.

**Owner evidence:** 3 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 2 owner review.

**Service keys:** cloud-itonami;kagitaba-kms;kotoba-cloud-docs;kotoba-cloud-pages;kotobase-authn

**Next action:** Separate auth, KMS, Pages and Itonami-adjacent services into app identities; resolve the remaining source owners and preserve credential/key custody.

### `kotoba.digital`

**Class / stage:** `account-zone-no-service` / `external-delegation-and-retention-review`

**Inventory:** 1 row; 0 runtime/provider; 0 Pages deployment candidates; 0 DNS records; 0 services.

**Owner evidence:** 0 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 0 owner review.

**Service keys:** No service mapped in the connected-account snapshot.

**Next action:** Confirm registrar delegation and recursive public DNS/HTTP. This active zone has zero configured DNS records and no runtime endpoint row; decide whether an external service exists, should be assigned to an app, or should be retained.

### `kotoba.foundation`

**Class / stage:** `other-public-domain` / `app-identity-and-service-contract-review`

**Inventory:** 4 rows; 3 runtime/provider; 0 Pages deployment candidates; 1 DNS records; 1 service.

**Owner evidence:** 1 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 0 owner review.

**Service keys:** kotoba-foundation-commons

**Next action:** Confirm whether Commons is an independent app or a Murakumo capability; assign publisher, data boundary and immutable release source.

### `kotobalabs.com`

**Class / stage:** `other-public-domain` / `service-owner-and-product-boundary-review`

**Inventory:** 8 rows; 2 runtime/provider; 0 Pages deployment candidates; 6 DNS records; 1 service.

**Owner evidence:** 0 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 1 owner review.

**Service keys:** kotoba-labs-site

**Next action:** Identify the public site/service owner, repository and state; decide app membership or retirement only after a public readback.

### `kotobase.net`

**Class / stage:** `other-public-domain` / `app-identity-and-service-contract-review`

**Inventory:** 24 rows; 1 runtime/provider; 0 Pages deployment candidates; 23 DNS records; 1 service.

**Owner evidence:** 1 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 0 owner review.

**Service keys:** app-hyakka

**Next action:** Confirm the auth service contract and credential/session data path; preserve downstream authentication before selecting an Awai app boundary.

### `manimani.cloud`

**Class / stage:** `other-public-domain` / `service-owner-and-product-boundary-review`

**Inventory:** 2 rows; 1 runtime/provider; 0 Pages deployment candidates; 1 DNS records; 1 service.

**Owner evidence:** 0 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 1 owner review.

**Service keys:** cloud-manimani

**Next action:** Resolve its source owner and classify its public service contract, state and dependencies before assigning an app identity.

### `mithril.fund`

**Class / stage:** `other-public-domain` / `service-owner-and-product-boundary-review`

**Inventory:** 98 rows; 55 runtime/provider; 0 Pages deployment candidates; 43 DNS records; 20 services.

**Owner evidence:** 5 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 15 owner review.

**Service keys:** fund-mithril-analysis;fund-mithril-apex;fund-mithril-api;fund-mithril-app;fund-mithril-auth;fund-mithril-blog;fund-mithril-code;fund-mithril-console;fund-mithril-docs;fund-mithril-graph;fund-mithril-knowledge;fund-mithril-kyber;fund-mithril-models;fund-mithril-storage;fund-mithril-support;fund-mithril-talk;fund-mithril-twin;fund-mithril-www;fund-mithril-yabai;mithril-admin

**Next action:** Resolve 19 script owners; map financial/account data, auth/session and signer boundaries before assigning app identities or planning imports.

### `mithril.love`

**Class / stage:** `account-zone-no-service` / `external-delegation-and-retention-review`

**Inventory:** 1 row; 0 runtime/provider; 0 Pages deployment candidates; 0 DNS records; 0 services.

**Owner evidence:** 0 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 0 owner review.

**Service keys:** No service mapped in the connected-account snapshot.

**Next action:** Confirm registrar delegation and recursive public DNS/HTTP. This active zone has zero configured DNS records and no runtime endpoint row; decide whether an external service exists, should be assigned to an app, or should be retained.

### `murakumo.cloud`

**Class / stage:** `first-product-cohort` / `common-foundation-and-product-app-qualification`

**Inventory:** 58 rows; 30 runtime/provider; 0 Pages deployment candidates; 28 DNS records; 18 services.

**Owner evidence:** 14 resolved / 4 owner hints / 0 candidates / 0 ambiguous / 0 owner review.

**Service keys:** cloud-itonami-isic-6493-factoring;comfyui-gad;kekkai-control-plane;kotoba-foundation-commons;local-murakumo;murakumo-ads-mcp;murakumo-client-static;murakumo-cloud;murakumo-generation-proxy;murakumo-ipfs-gateway-pilot;murakumo-llms;murakumo-meter;murakumo-node-registration;murakumo-operator;murakumo-setup;murakumo-status;murakumo-toshokan;murakumo-web

**Next action:** Separate the Murakumo UI from compute; preserve job identity, placement, billing and receipts, then prove inference and generation with one operator node unavailable.

### `mythril.build`

**Class / stage:** `account-zone-no-service` / `external-delegation-and-retention-review`

**Inventory:** 1 row; 0 runtime/provider; 0 Pages deployment candidates; 0 DNS records; 0 services.

**Owner evidence:** 0 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 0 owner review.

**Service keys:** No service mapped in the connected-account snapshot.

**Next action:** Confirm registrar delegation and recursive public DNS/HTTP. This active zone has zero configured DNS records and no runtime endpoint row; decide whether an external service exists, should be assigned to an app, or should be retained.

### `nubatama.net`

**Class / stage:** `account-zone-no-service` / `external-delegation-and-retention-review`

**Inventory:** 1 row; 0 runtime/provider; 0 Pages deployment candidates; 0 DNS records; 0 services.

**Owner evidence:** 0 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 0 owner review.

**Service keys:** No service mapped in the connected-account snapshot.

**Next action:** Confirm registrar delegation and recursive public DNS/HTTP. This active zone has zero configured DNS records and no runtime endpoint row; decide whether an external service exists, should be assigned to an app, or should be retained.

### `oppai.fans`

**Class / stage:** `first-product-cohort` / `common-foundation-and-product-app-qualification`

**Inventory:** 2 rows; 0 runtime/provider; 0 Pages deployment candidates; 0 DNS records; 0 services.

**Owner evidence:** 0 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 0 owner review.

**Service keys:** No service mapped in the connected-account snapshot.

**Next action:** Resolve external delegation and serving origin first; preserve age/consent gates, face-data custody and the Murakumo capability boundary.

### `pages.dev`

**Class / stage:** `platform-hostname` / `per-alias-app-ownership-and-retirement-decision`

**Inventory:** 58 rows; 31 runtime/provider; 27 Pages deployment candidates; 0 DNS records; 16 services.

**Owner evidence:** 8 resolved / 1 owner hints / 2 candidates / 0 ambiguous / 5 owner review.

**Service keys:** cloud-itonami;cloud-itonami-commitment-ledger;cloud-itonami-isic-6492;cloud-itonami-partners;cyber-drill;cyber-drill-merble;deps-gftd-ai;gorigori-ketsu-dasshu;itonami-agent-treasury-tokyo2026;net-babiniku;network-isekai;org-spirit-in-physics-comics;spirit-in-physics-researcher;spirit-in-physics-web;store-36b48539;torihiki

**Next action:** Map all 16 projects and their public deployment URLs to product owners and Awai app IDs; choose keep-as-alias or retire only with URL-level readback.

### `shinshi.club`

**Class / stage:** `first-product-cohort` / `common-foundation-and-product-app-qualification`

**Inventory:** 11 rows; 1 runtime/provider; 0 Pages deployment candidates; 10 DNS records; 1 service.

**Owner evidence:** 0 resolved / 0 owner hints / 0 candidates / 1 ambiguous / 0 owner review.

**Service keys:** magatama-sh1n5h1x

**Next action:** Preserve the advertised 18+ access control, USDC commerce entitlements, both stores, moderation, and consent; choose the canonical app source, then prove a canary and rollback.

### `spirit-in-physics.com`

**Class / stage:** `other-public-domain` / `app-identity-and-service-contract-review`

**Inventory:** 3 rows; 3 runtime/provider; 0 Pages deployment candidates; 0 DNS records; 2 services.

**Owner evidence:** 2 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 0 owner review.

**Service keys:** spirit-in-physics-researcher;spirit-in-physics-web

**Next action:** Confirm publisher and dataset/account boundaries for the two source-matched services, then assign the app identity and import evidence.

### `spirit-in-physics.org`

**Class / stage:** `other-public-domain` / `service-owner-and-product-boundary-review`

**Inventory:** 13 rows; 7 runtime/provider; 2 Pages deployment candidates; 4 DNS records; 4 services.

**Owner evidence:** 3 resolved / 0 owner hints / 1 candidates / 0 ambiguous / 0 owner review.

**Service keys:** org-spirit-in-physics-comics;spirit-in-physics-api;spirit-in-physics-researcher;spirit-in-physics-web

**Next action:** Resolve the candidate service; separate site, researcher API and comics surfaces, then map datasets and writes to app identities.

### `workers.dev`

**Class / stage:** `platform-hostname` / `per-alias-app-ownership-and-retirement-decision`

**Inventory:** 98 rows; 98 runtime/provider; 0 Pages deployment candidates; 0 DNS records; 51 services.

**Owner evidence:** 35 resolved / 1 owner hints / 0 candidates / 1 ambiguous / 14 owner review.

**Service keys:** aiueos-updates;app-otent;cloud-itonami-app-classifieds-production;cloud-itonami-app-edge;cloud-itonami-domain-reverify;cloud-itonami-mail-inbound;cloud-itonami-marketplace-buyer;cloud-itonami-marketplace-crossborder;cloud-itonami-marketplace-fulfillment;cloud-itonami-marketplace-listing;cloud-itonami-marketplace-onboarding;cloud-itonami-marketplace-order;cloud-itonami-marketplace-returns;cloud-itonami-marketplace-settlement;cloud-itonami-tsukuru;cloud-itonami-workspace-outbox;engi-node;fund-mithril-admin;gleif-lei-datasource;goriketsu-proxy;inga-replica-testnet;itonami-pricing-resident;kaigi;kotoba-cloud-cljk-preview;kotoba-cloud-pages-candidate;kotoba-worker-demo;kura-conformance;mithril-admin-staging;mithril-api;mithril-api-staging;mithril-auth;mithril-auth-staging;mithril-hermes-gateway-trial;mithril-hermes-runtime;mithril-knowledge-fund-preview;mithril-move-smoke-apex;mithril-native-shared-trial;mithril-vault-training-20261011;mithril-web;mithril-web-release;mithril-web-staging;ossekai-arb-intake;saisei-worker;shinshi-cljc;social-rooms;spirit-in-physics-api;spirit-in-physics-researcher;spirit-in-physics-web;tomoshibi-mail;torihiki-node;torihiki-validator-v3

**Next action:** Map each enabled script hostname and preview URL pattern to its product app or an explicit retirement decision; preserve host-owned capabilities and verify every retained alias.

### `x402.nexus`

**Class / stage:** `first-product-cohort` / `common-foundation-and-product-app-qualification`

**Inventory:** 9 rows; 2 runtime/provider; 0 Pages deployment candidates; 7 DNS records; 2 services.

**Owner evidence:** 1 resolved / 1 owner hints / 0 candidates / 0 ambiguous / 0 owner review.

**Service keys:** nexus-wallet-25mbair;nexus-x402

**Next action:** Preserve the published facilitator, discovery, MCP, and seller APIs; keep settlement and signing host-owned, migrate verifier/nonce/budget state, and prove idempotent replay with chain-backed receipts.

### `yaoi.dev`

**Class / stage:** `other-public-domain` / `app-identity-and-service-contract-review`

**Inventory:** 4 rows; 2 runtime/provider; 0 Pages deployment candidates; 2 DNS records; 1 service.

**Owner evidence:** 1 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 0 owner review.

**Service keys:** dev-yaoi

**Next action:** Confirm the source and product owner; identify content/data and privacy obligations before creating an app manifest.

### `yatalake.com`

**Class / stage:** `account-zone-no-service` / `external-delegation-and-retention-review`

**Inventory:** 1 row; 0 runtime/provider; 0 Pages deployment candidates; 0 DNS records; 0 services.

**Owner evidence:** 0 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 0 owner review.

**Service keys:** No service mapped in the connected-account snapshot.

**Next action:** Confirm registrar delegation and recursive public DNS/HTTP. This active zone has zero configured DNS records and no runtime endpoint row; decide whether an external service exists, should be assigned to an app, or should be retained.

### `yataverse.com`

**Class / stage:** `other-public-domain` / `service-owner-and-product-boundary-review`

**Inventory:** 5 rows; 2 runtime/provider; 0 Pages deployment candidates; 3 DNS records; 2 services.

**Owner evidence:** 1 resolved / 0 owner hints / 0 candidates / 0 ambiguous / 1 owner review.

**Service keys:** app-hyakka;app-hyakka-patents

**Next action:** Resolve the pending service and partition the knowledge/content services; identify data export and independent read/write proofs.

## Cutover gates

Product-domain routes remain pending until each applicable gate is
evidenced for the exact app, hostname, and service contract:

1. Operator-approved Awai production chain profile: chain ID, genesis
   hash, validator-set identity, quorum policy, and independent peer
   endpoints. The profile is currently uncreated; no values are inferred.
2. Publisher-controlled app identity, signed immutable release, and
   finalized hostname binding with independently verified current-ref and
   finality proofs.
3. Service-specific state export/import, data custody and credential
   rotation, followed by retained write/read receipts and replay checks.
4. A serving path without Cloudflare Workers, Pages, proxying, R2, D1,
   Durable Objects, or Tunnels; independently operated gateway/peers.
5. Independent public DNS authority proven over UDP and TCP, with
   NS/glue, parent DS/DNSSEC, external-resolver checks, an operator-node
   loss drill, and rollback. Keep mail routing separate and validate
   MX/SPF/DKIM/DMARC and end-to-end delivery before moving mail records.
6. Public DNS/TLS/HTTP readback for each
   retained route and alias. Preview and deployment URLs need an explicit
   keep, redirect, or retire decision at the URL level.

For `account-zone-no-service` groups, first verify external registrar
delegation, public DNS/HTTP, and owner intent. Do not assign an app or
retire the domain from an empty Cloudflare zone alone.

## Evidence files

- [Endpoint-level migration catalog](../awai-app-public-endpoint-migration-2026-10-17.csv)
- [Service-owner evidence map](../awai-app-service-ownership-2026-10-17.csv)
- [Domain summary and generated actions](../awai-app-domain-ownership-summary-2026-10-17.csv)
- [Inventory scope and evidence limits](../awai-app-domain-inventory.md)

The inputs are connected-provider configuration snapshots and dated
readbacks. They do not prove current global DNS delegation, live origin
health, or service runtime behavior. No provider, DNS, registrar, or
deployment settings are changed by this generator.
