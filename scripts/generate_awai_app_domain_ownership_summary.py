#!/usr/bin/env python3
"""Aggregate public endpoint ownership and migration state by root domain."""

import csv
from collections import Counter, defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs/awai-app-public-endpoint-migration-2026-10-17.csv"
OUTPUT = ROOT / "docs/awai-app-domain-ownership-summary-2026-10-17.csv"
CUTOVER_GATE = (
    "Production chain profile and quorum; signed app release and exact domain bind; "
    "service state import and read/write receipts; no Cloudflare proxy/Workers/Pages/R2/Tunnel "
    "in the serving path; independent authoritative DNSSEC, DNS and TLS; preserve mail flow; "
    "rollback; public DNS and HTTP readback"
)
EMPTY_ZONE_GATE = (
    "Confirm registrar delegation and public DNS/HTTP; connected Cloudflare zone is empty; "
    "record owner intent before assigning an app or retiring the domain"
)
PROVIDER_SURFACES = {
    "worker-custom-domain", "worker-route", "workers.dev", "workers.dev-preview", "pages-preview",
    "pages-production", "pages-production-deployment", "tunnel-ingress",
}
RUNTIME_SURFACES = PROVIDER_SURFACES - {"pages-production-deployment"}
INVENTORIED_SURFACES = RUNTIME_SURFACES | {
    "pages-production-deployment",
    "dns-record",
}

DOMAIN_ACTIONS = {
    "itonami.app": "Qualify the common launcher and peer-backed registry/content path; map wildcard, kaisya, arb and _dnslink aliases before it becomes the resolver for other apps.",
    "murakumo.cloud": "Separate the Murakumo UI from compute; preserve job identity, placement, billing and receipts, then prove inference and generation with one operator node unavailable.",
    "aozora.app": "Migrate AT Protocol PDS, AppView, auth, feeds, blobs and live media with repo/write/read receipts; plan credential renewal for the new origin.",
    "oppai.fans": "Resolve external delegation and serving origin first; preserve age/consent gates, face-data custody and the Murakumo capability boundary.",
    "isekai.network": "Verify the pinned static CID independently, then migrate D1 scores, social rooms, generation and game writes with import and write receipts.",
    "itonami.cloud": "Preserve WebAuthn account/session continuity and migrate D1, KV, Durable Object and service bindings; qualify the new RP origin and credential renewal.",
    "x402.nexus": "Preserve the published facilitator, discovery, MCP, and seller APIs; keep settlement and signing host-owned, migrate verifier/nonce/budget state, and prove idempotent replay with chain-backed receipts.",
    "shinshi.club": "Preserve the advertised 18+ access control, USDC commerce entitlements, both stores, moderation, and consent; choose the canonical app source, then prove a canary and rollback.",
    "babiniku.net": "Preserve room/chat state and live-broadcast behavior; reconcile production asset storage, migrate generated assets, and prove ingest/playback without Cloudflare Stream or Worker dependencies.",
    "awai.network": "Preserve the public Basho/Hokusai/Giemon disclosures and Murakumo service handoff; keep unreleased model claims explicit while qualifying chat, image/video jobs, streaming, and artifact reads across independent nodes.",
    "etzhayyim.com": "Resolve pending service owners and candidate repo; classify Itonami, Murakumo and AT Protocol aliases, then set canonical-host or redirect decisions.",
    "gftd.ai": "Resolve wildcard and Tunnel-backed services individually; partition product apps from shared compute/data adapters and trace each hostname to a service owner.",
    "kotoba-lang.org": "Resolve the duplicate Wrangler-name repositories and publisher; choose the canonical site source and assign an app identity before packaging.",
    "kotoba.cloud": "Separate auth, KMS, Pages and Itonami-adjacent services into app identities; resolve the remaining source owners and preserve credential/key custody.",
    "kotoba.foundation": "Confirm whether Commons is an independent app or a Murakumo capability; assign publisher, data boundary and immutable release source.",
    "kotobalabs.com": "Identify the public site/service owner, repository and state; decide app membership or retirement only after a public readback.",
    "kotobase.net": "Confirm the auth service contract and credential/session data path; preserve downstream authentication before selecting an Awai app boundary.",
    "manimani.cloud": "Resolve its source owner and classify its public service contract, state and dependencies before assigning an app identity.",
    "mithril.fund": "Resolve 19 script owners; map financial/account data, auth/session and signer boundaries before assigning app identities or planning imports.",
    "spirit-in-physics.com": "Confirm publisher and dataset/account boundaries for the two source-matched services, then assign the app identity and import evidence.",
    "spirit-in-physics.org": "Resolve the candidate service; separate site, researcher API and comics surfaces, then map datasets and writes to app identities.",
    "yaoi.dev": "Confirm the source and product owner; identify content/data and privacy obligations before creating an app manifest.",
    "yataverse.com": "Resolve the pending service and partition the knowledge/content services; identify data export and independent read/write proofs.",
    "pages.dev": "Map all 16 projects and their public deployment URLs to product owners and Awai app IDs; choose keep-as-alias or retire only with URL-level readback.",
    "workers.dev": "Map each enabled script hostname and preview URL pattern to its product app or an explicit retirement decision; preserve host-owned capabilities and verify every retained alias.",
    "workers.dev-preview": "Disable or map every enabled version-preview URL pattern before retiring a Worker; preview hosts remain independent public routes from the stable workers.dev hostname.",
}


def main():
    with SOURCE.open(newline="", encoding="utf-8") as stream:
        source_rows = list(csv.DictReader(stream))

    grouped = defaultdict(list)
    for row in source_rows:
        grouped[row["product_domain"] or "(unmapped)"].append(row)

    fields = [
        "product_domain",
        "domain_class",
        "catalog_rows",
        "runtime_endpoint_rows",
        "pages_deployment_candidate_rows",
        "provider_endpoint_rows",
        "dns_record_rows",
        "migration_review_rows",
        "unique_services",
        "surfaces",
        "resolved_services",
        "product_owner_hint_services",
        "candidate_services",
        "ambiguous_services",
        "owner_review_services",
        "services",
        "resolved_service_source_repositories",
        "product_app_repository_hints",
        "migration_disposition",
        "migration_stage",
        "next_migration_action",
        "cutover_gate",
    ]
    output = []
    for domain, rows in sorted(grouped.items()):
        services = {}
        for row in rows:
            # Readbacks and source-declared/no-route rows are migration evidence,
            # not services currently exposed by a provider surface.
            if row["service"] and row["surface"] in PROVIDER_SURFACES:
                services[row["service"]] = row
        if any(";" in service for service in services):
            raise ValueError(
                f"service identifier for {domain} contains the summary delimiter"
            )
        owner_counts = Counter(row["source_owner_status"] for row in services.values())
        domain_class = rows[0]["cohort_state"]
        dispositions = sorted({row["service_migration_state"] for row in rows})
        if domain_class == "first-product-cohort":
            migration_stage = "common-foundation-and-product-app-qualification"
        elif domain_class == "account-zone-no-service":
            migration_stage = "external-delegation-and-retention-review"
        elif domain_class == "platform-hostname":
            migration_stage = "per-alias-app-ownership-and-retirement-decision"
        elif any(row["source_owner_status"] in {"owner-review-pending", "candidate", "ambiguous"}
                 for row in services.values()):
            migration_stage = "service-owner-and-product-boundary-review"
        else:
            migration_stage = "app-identity-and-service-contract-review"
        resolved_repos = sorted({
            row["source_owner_repository_candidates"]
            for row in services.values()
            if row["source_owner_status"] == "resolved"
            and row["source_owner_repository_candidates"]
        })
        product_repos = sorted({
            row["product_source_repository_hint"]
            for row in rows
            if row["cohort_state"] == "first-product-cohort"
            and row["product_source_repository_hint"]
            and row["product_source_repository_hint"] != "unclassified"
        })
        output.append({
            "product_domain": domain,
            "domain_class": domain_class,
            "catalog_rows": len(rows),
            "runtime_endpoint_rows": sum(row["surface"] in RUNTIME_SURFACES for row in rows),
            "pages_deployment_candidate_rows": sum(
                row["surface"] == "pages-production-deployment" for row in rows
            ),
            "provider_endpoint_rows": sum(row["surface"] in PROVIDER_SURFACES for row in rows),
            "dns_record_rows": sum(row["surface"] == "dns-record" for row in rows),
            "migration_review_rows": sum(
                row["surface"] not in INVENTORIED_SURFACES for row in rows
            ),
            "unique_services": len(services),
            "surfaces": ";".join(sorted({row["surface"] for row in rows})),
            "resolved_services": owner_counts["resolved"],
            "product_owner_hint_services": owner_counts["product-owner-hint"],
            "candidate_services": owner_counts["candidate"],
            "ambiguous_services": owner_counts["ambiguous"],
            "owner_review_services": owner_counts["owner-review-pending"],
            "services": ";".join(sorted(services)),
            "resolved_service_source_repositories": ";".join(resolved_repos),
            "product_app_repository_hints": ";".join(product_repos),
            "migration_disposition": ";".join(dispositions),
            "migration_stage": migration_stage,
            "next_migration_action": (
                "Confirm registrar delegation and recursive public DNS/HTTP. This active zone has "
                "zero configured DNS records and no runtime endpoint row; decide whether an "
                "external service exists, should be assigned to an app, or should be retained."
                if domain_class == "account-zone-no-service"
                else DOMAIN_ACTIONS.get(
                    domain,
                    "Identify the owning service and public contract, then assign an Awai app identity and migration receipt.",
                )
            ),
            "cutover_gate": EMPTY_ZONE_GATE if domain_class == "account-zone-no-service" else CUTOVER_GATE,
        })

    with OUTPUT.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        writer.writerows(output)
    print(f"domains={len(output)}")
    print(f"catalog_rows={sum(row['catalog_rows'] for row in output)}")
    print(f"provider_endpoint_rows={sum(row['provider_endpoint_rows'] for row in output)}")
    print(f"domains_without_action={sum(row['product_domain'] not in DOMAIN_ACTIONS and row['domain_class'] != 'account-zone-no-service' for row in output)}")
    print(f"output={OUTPUT}")


if __name__ == "__main__":
    main()
