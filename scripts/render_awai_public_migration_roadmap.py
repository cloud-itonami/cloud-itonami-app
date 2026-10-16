#!/usr/bin/env python3
"""Render the per-domain Awai/Itonami migration queue from the dated catalog."""

import csv
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "docs/awai-app-public-endpoint-migration-2026-10-17.csv"
DOMAINS = ROOT / "docs/awai-app-domain-ownership-summary-2026-10-17.csv"
OWNERS = ROOT / "docs/awai-app-service-ownership-2026-10-17.csv"
OUTPUT = ROOT / "docs/migrations/awai-public-service-migration-roadmap-2026-10-17.md"


def read_rows(path):
    with path.open(newline="", encoding="utf-8") as stream:
        return list(csv.DictReader(stream))


def count_label(row):
    return (
        f"{row['resolved_services']} resolved / "
        f"{row['product_owner_hint_services']} owner hints / "
        f"{row['candidate_services']} candidates / "
        f"{row['ambiguous_services']} ambiguous / "
        f"{row['owner_review_services']} owner review"
    )


def render(catalog, domains, owners):
    if not catalog or not domains or not owners:
        raise ValueError("migration inputs must be non-empty")
    if len({row["product_domain"] for row in domains}) != len(domains):
        raise ValueError("domain summary contains duplicate groups")
    if sum(int(row["catalog_rows"]) for row in domains) != len(catalog):
        raise ValueError("domain summary does not cover the endpoint catalog")
    if any(not row["product_domain"] for row in domains):
        raise ValueError("domain summary contains an unassigned group")
    catalog_group_counts = Counter(row["product_domain"] for row in catalog)
    for row in domains:
        domain = row["product_domain"]
        if catalog_group_counts[domain] != int(row["catalog_rows"]):
            raise ValueError(f"catalog row count does not match summary for {domain}")
        listed_services = [name for name in row["services"].split(";") if name]
        if len(listed_services) != int(row["unique_services"]):
            raise ValueError(f"service list does not match summary for {domain}")
        owner_total = sum(int(row[key]) for key in (
            "resolved_services", "product_owner_hint_services",
            "candidate_services", "ambiguous_services", "owner_review_services",
        ))
        if owner_total != int(row["unique_services"]):
            raise ValueError(f"owner status counts do not close for {domain}")
    if any(not row["migration_stage"] or not row["next_migration_action"]
           or not row["cutover_gate"] for row in domains):
        raise ValueError("a domain is missing a migration action or cutover gate")
    if any(not row["service_migration_state"] or not row["target_host"]
           for row in catalog):
        raise ValueError("an endpoint row is missing migration state or target")
    page_rows = [row for row in catalog
                 if row["surface"] == "pages-production-deployment"]
    if any(row["external_http_reachability"] != "unverified"
           for row in page_rows):
        raise ValueError("Pages deployment candidates include a reachability claim")
    if any(row["mapping_basis"] == "unmapped" for row in catalog):
        raise ValueError("endpoint catalog contains an unmapped row")

    surfaces = Counter(row["surface"] for row in catalog)
    owner_statuses = Counter(row["owner_status"] for row in owners)
    if len({row["service"] for row in owners}) != len(owners):
        raise ValueError("service-owner map contains duplicate services")
    grouped_service_names = {
        name for row in domains for name in row["services"].split(";") if name
    }
    owner_service_names = {row["service"] for row in owners}
    if grouped_service_names != owner_service_names:
        raise ValueError("domain service groups do not match the owner map")
    allowed_owner_statuses = {
        "resolved", "owner-review-pending", "product-owner-hint",
        "ambiguous", "candidate",
    }
    if not set(owner_statuses) <= allowed_owner_statuses:
        raise ValueError("service-owner map contains an unknown owner status")
    domain_classes = Counter(row["domain_class"] for row in domains)
    runtime_rows = sum(int(row["runtime_endpoint_rows"]) for row in domains)
    dns_rows = sum(int(row["dns_record_rows"]) for row in domains)
    page_candidates = sum(int(row["pages_deployment_candidate_rows"]) for row in domains)
    service_assignments = sum(int(row["unique_services"]) for row in domains)
    unresolved_owners = len(owners) - owner_statuses["resolved"]

    lines = [
        "# Awai / Itonami public-service migration roadmap",
        "",
        "Snapshot: 2026-10-17 JST. Generated from the normalized public-endpoint",
        "catalog and service-owner map. This is the migration queue and evidence",
        "boundary; it does not claim any route has moved or authorize DNS/provider",
        "changes.",
        "",
        "## Inventory closure",
        "",
        f"- {len(domains)} domain groups cover {len(catalog):,} catalog rows: "
        f"{runtime_rows:,} runtime/provider rows, {dns_rows:,} DNS-record rows, "
        f"and {page_candidates:,} Pages deployment candidates.",
        f"- The grouped domain rows contain {service_assignments} service-key assignments "
        f"(some service keys appear in multiple host groups). The owner map has "
        f"{len(owners)} distinct services: {owner_statuses['resolved']} resolved and "
        f"{unresolved_owners} still needing owner/source review.",
        "- The union of provider service keys across all domain groups exactly "
        "matches the 174-service owner map.",
        f"- Domain classes: {', '.join(f'{name}={count}' for name, count in sorted(domain_classes.items()))}.",
        "- Surface counts: " + ", ".join(
            f"{surface}={count:,}" for surface, count in sorted(surfaces.items())
        ) + ".",
        "- All Pages deployment rows remain reachability candidates until an",
        "  independent HTTP readback proves otherwise. DNS-record rows are",
        "  control-plane metadata, not application endpoints.",
        "",
        "## Per-domain migration queue",
        "",
    ]

    for row in domains:
        lines.extend([
            f"### `{row['product_domain']}`",
            "",
            f"**Class / stage:** `{row['domain_class']}` / `{row['migration_stage']}`",
            "",
            (f"**Inventory:** {int(row['catalog_rows']):,} "
             f"{ 'row' if int(row['catalog_rows']) == 1 else 'rows' }; "
             f"{int(row['runtime_endpoint_rows']):,} runtime/provider; "
             f"{int(row['pages_deployment_candidate_rows']):,} Pages deployment "
             f"candidates; {int(row['dns_record_rows']):,} DNS records; "
             f"{row['unique_services']} "
             f"{ 'service' if int(row['unique_services']) == 1 else 'services' }."),
            "",
            f"**Owner evidence:** {count_label(row)}.",
            "",
            f"**Service keys:** {row['services'] or 'No service mapped in the connected-account snapshot.'}",
            "",
            f"**Next action:** {row['next_migration_action']}",
            "",
        ])

    lines.extend([
        "## Cutover gates",
        "",
        "Product-domain routes remain pending until each applicable gate is",
        "evidenced for the exact app, hostname, and service contract:",
        "",
        "1. Operator-approved Awai production chain profile: chain ID, genesis",
        "   hash, validator-set identity, quorum policy, and independent peer",
        "   endpoints. The profile is currently uncreated; no values are inferred.",
        "2. Publisher-controlled app identity, signed immutable release, and",
        "   finalized hostname binding with independently verified current-ref and",
        "   finality proofs.",
        "3. Service-specific state export/import, data custody and credential",
        "   rotation, followed by retained write/read receipts and replay checks.",
        "4. A serving path without Cloudflare Workers, Pages, proxying, R2, D1,",
        "   Durable Objects, or Tunnels; independently operated gateway/peers.",
        "5. Independent public DNS authority proven over UDP and TCP, with",
        "   NS/glue, parent DS/DNSSEC, external-resolver checks, an operator-node",
        "   loss drill, and rollback. Keep mail routing separate and validate",
        "   MX/SPF/DKIM/DMARC and end-to-end delivery before moving mail records.",
        "6. Public DNS/TLS/HTTP readback for each",
        "   retained route and alias. Preview and deployment URLs need an explicit",
        "   keep, redirect, or retire decision at the URL level.",
        "",
        "For `account-zone-no-service` groups, first verify external registrar",
        "delegation, public DNS/HTTP, and owner intent. Do not assign an app or",
        "retire the domain from an empty Cloudflare zone alone.",
        "",
        "## Evidence files",
        "",
        "- [Endpoint-level migration catalog](../awai-app-public-endpoint-migration-2026-10-17.csv)",
        "- [Service-owner evidence map](../awai-app-service-ownership-2026-10-17.csv)",
        "- [Domain summary and generated actions](../awai-app-domain-ownership-summary-2026-10-17.csv)",
        "- [Inventory scope and evidence limits](../awai-app-domain-inventory.md)",
        "",
        "The inputs are connected-provider configuration snapshots and dated",
        "readbacks. They do not prove current global DNS delegation, live origin",
        "health, or service runtime behavior. No provider, DNS, registrar, or",
        "deployment settings are changed by this generator.",
        "",
    ])
    return "\n".join(lines)


def main():
    catalog = read_rows(CATALOG)
    domains = read_rows(DOMAINS)
    owners = read_rows(OWNERS)
    output = render(catalog, domains, owners)
    OUTPUT.write_text(output, encoding="utf-8")
    print(f"rendered {len(domains)} domain groups and {len(catalog)} endpoint rows to {OUTPUT}")


if __name__ == "__main__":
    main()
