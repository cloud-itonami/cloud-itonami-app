#!/usr/bin/env python3
"""Enrich the read-only Cloudflare runtime snapshot with Itonami app migration state."""

import csv
from pathlib import Path
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs/awai-cloudflare-runtime-endpoints-2026-10-17.csv"
DNS_SOURCE = ROOT / "docs/awai-cloudflare-dns-hostnames-2026-10-17.csv"
OWNERSHIP_SOURCE = ROOT / "docs/awai-app-service-ownership-2026-10-17.csv"
PAGES_DEPLOYMENT_SOURCE = ROOT / "docs/awai-pages-production-deployment-urls-2026-10-16.csv"
ROOT_READBACK_SOURCE = ROOT / "docs/awai-app-public-root-readback-2026-10-16.csv"
ZONE_RECHECK_SOURCE = ROOT / "docs/awai-cloudflare-zone-recheck-2026-10-17.csv"
OUTPUT = ROOT / "docs/awai-app-public-endpoint-migration-2026-10-17.csv"

DOMAIN_TO_APP = {
    "murakumo.cloud": ("murakumo", "network-awai/cloud-murakumo"),
    "aozora.app": ("aozora", "network-awai/app-aozora-engine"),
    "oppai.fans": ("oppai", "network-awai/fans-oppai"),
    "isekai.network": ("isekai", "network-awai/network-isekai"),
    "itonami.app": ("itonami", "network-awai/cloud-itonami"),
    "itonami.cloud": ("itonami", "network-awai/cloud-itonami"),
    "x402.nexus": ("x402", "network-awai/nexus-x402"),
    "shinshi.club": ("shinshi", "network-awai/club-shinshi-app"),
    "babiniku.net": ("babiniku", "network-awai/net-babiniku"),
    "awai.network": ("awai-network", "network-awai/network-awai-apex"),
}
COHORT = set(DOMAIN_TO_APP)

SERVICE_TO_DOMAIN = {
    "network-isekai": "isekai.network",
    "net-babiniku": "babiniku.net",
    "cloud-itonami": "itonami.cloud",
    "oppai-fans": "oppai.fans",
    "nexus-x402": "x402.nexus",
    "network-awai-apex": "awai.network",
    "magatama-sh1n5h1x": "shinshi.club",
    "murakumo-cloud": "murakumo.cloud",
    "app-aozora-spa": "aozora.app",
    "app-aozora-appview": "aozora.app",
    "app-aozora-auth": "aozora.app",
    "app-aozora-live": "aozora.app",
    "app-aozora-pds": "aozora.app",
    "cloud-itonami-agent-edge": "itonami.cloud",
    "cloud-itonami-hanmoto": "itonami.cloud",
    "cloud-itonami-mcp-edge": "itonami.cloud",
    "itonami-app-auth": "itonami.cloud",
    "itonami-client-static": "itonami.cloud",
    "itonami-cloud-webhooks": "itonami.cloud",
    "itonami-dougaka": "itonami.cloud",
    "itonami-ethglobal-tokyo-demo": "itonami.cloud",
    "itonami-fleet-dispatch": "itonami.cloud",
    "itonami-grok-bots": "itonami.cloud",
    "itonami-media-kouryaku": "itonami.cloud",
    "itonami-office": "itonami.cloud",
    "kenbun": "itonami.cloud",
    "mangaka-reader": "itonami.cloud",
    "app-mail-relay": "itonami.cloud",
    "local-murakumo": "murakumo.cloud",
    "murakumo-client-static": "murakumo.cloud",
    "murakumo-generation-proxy": "murakumo.cloud",
    "murakumo-ipfs-gateway-pilot": "murakumo.cloud",
    "murakumo-meter": "murakumo.cloud",
    "murakumo-operator": "murakumo.cloud",
    "murakumo-setup": "murakumo.cloud",
    "murakumo-status": "murakumo.cloud",
    "murakumo-toshokan": "murakumo.cloud",
    "murakumo-web": "murakumo.cloud",
    "murakumo-ads-mcp": "murakumo.cloud",
    "murakumo-llms": "murakumo.cloud",
    "murakumo-node-registration": "murakumo.cloud",
}

PAGES_PROJECT_TO_DOMAIN = {
    "cloud-itonami": "itonami.cloud",
    "itonami-agent-treasury-tokyo2026": "itonami.cloud",
    "cloud-itonami-partners": "itonami.cloud",
    "cloud-itonami-isic-6492": "itonami.cloud",
    "cloud-itonami-commitment-ledger": "itonami.cloud",
    "net-babiniku": "babiniku.net",
    "network-isekai": "isekai.network",
    "org-spirit-in-physics-comics": "spirit-in-physics.org",
    "gorigori-ketsu-dasshu": "gftd.ai",
}


def route_domain(value, known_zones):
    """Return a root zone only when the host is covered by the inventory."""
    clean = value.split("^", 1)[0].split("/", 1)[0].rstrip(".")
    for domain in sorted(known_zones, key=len, reverse=True):
        if clean == domain or clean.endswith("." + domain):
            return domain
    return ""


def domain_for(row, known_zones, worker_domains, page_domains):
    domain = route_domain(row["host_or_pattern"], known_zones)
    if domain:
        return domain, "host-suffix"
    project = row["zone_or_project"]
    if project in known_zones:
        return project, "zone-attachment"
    service = row["service"]
    if row["surface"] == "pages-preview":
        project_name = project or service
        if project_name in page_domains:
            return page_domains[project_name], "pages-project-domain-attachment"
        if project_name in SERVICE_TO_DOMAIN:
            return SERVICE_TO_DOMAIN[project_name], "explicit-project-or-service-map"
        return "pages.dev", "pages-dev-preview"
    if row["surface"] in {"workers.dev", "workers.dev-preview"}:
        if service in worker_domains:
            return worker_domains[service], "worker-domain-attachment"
        if service in SERVICE_TO_DOMAIN:
            return SERVICE_TO_DOMAIN[service], "explicit-project-or-service-map"
        return "workers.dev", "workers-dev-only"
    if row["surface"] == "pages-production":
        return "pages.dev", "pages-production-host"
    if project in SERVICE_TO_DOMAIN:
        return SERVICE_TO_DOMAIN[project], "explicit-project-or-service-map"
    if service in SERVICE_TO_DOMAIN:
        return SERVICE_TO_DOMAIN[service], "explicit-project-or-service-map"
    return "", "unmapped"


def state_for(domain, row):
    if domain == "shinshi.club":
        return "offline-workflow-kotoba-core-local-wip-native-aarch64-selfhost-benchmarked-bootstrap-wasm-wired-public-runtime-unverified"
    if domain in {
        "murakumo.cloud", "aozora.app", "isekai.network", "babiniku.net",
        "x402.nexus", "oppai.fans", "awai.network",
    }:
        return "service-core-local-wip-uncommitted-native-aarch64-selfhost-benchmarked-bootstrap-wasm-wired-build-release-pending"
    if domain in {"itonami.app", "itonami.cloud"}:
        return "shared-app-core-local-wip-uncommitted-kir-host-wired-native-aarch64-selfhost-benchmarked-chain-profile-release-pending"
    if domain in COHORT:
        return "product-source-not-yet-migrated"
    if domain:
        return "outside-first-product-cohort-owner-review-pending"
    return "unmapped"


def migration_state(owner):
    status = owner.get("owner_status", "owner-review-pending")
    if status == "resolved":
        return "owner-resolved-migration-disposition-pending"
    if status == "product-owner-hint":
        return "product-owner-hint-needs-service-source-confirmation"
    if status == "candidate":
        return "owner-candidate-review-required"
    if status == "ambiguous":
        return "owner-ambiguous-review-required"
    return "owner-review-pending"


def root_reachability(readback):
    status = readback.get("https_get_status", "")
    if status == "200":
        if readback.get("https_remote_ip", "").startswith("2606:4700:"):
            return "root-https-200-cloudflare-edge-origin-unverified"
        return "root-https-200-origin-unverified"
    if status == "000" and "A/AAAA NODATA" in readback.get("google_doh_note", ""):
        return "root-dns-nodata-https-unresolved"
    if status:
        return f"root-https-status-{status}"
    return "root-readback-incomplete"


def main():
    with SOURCE.open(newline="", encoding="utf-8") as stream:
        source_rows = list(csv.DictReader(stream))
    with DNS_SOURCE.open(newline="", encoding="utf-8") as stream:
        dns_rows = list(csv.DictReader(stream))
    dns_catalog_rows = [
        {
            "account": row["account"],
            "surface": "dns-record",
            "host_or_pattern": row["hostname"],
            "service": "",
            "zone_or_project": row["zone"],
            "control_plane_state": (
                f"{row['record_type']};proxied={row['cloudflare_proxy_enabled']}"
            ),
            "preview_urls_enabled": "",
            "deployment_id": "",
            "created_on": "",
        }
        for row in dns_rows
    ]
    source_rows.extend(dns_catalog_rows)
    with OWNERSHIP_SOURCE.open(newline="", encoding="utf-8") as stream:
        ownership_rows = list(csv.DictReader(stream))
    with PAGES_DEPLOYMENT_SOURCE.open(newline="", encoding="utf-8") as stream:
        pages_deployment_rows = list(csv.DictReader(stream))
    with ROOT_READBACK_SOURCE.open(newline="", encoding="utf-8") as stream:
        root_readbacks = {row["domain"]: row for row in csv.DictReader(stream)}
    with ZONE_RECHECK_SOURCE.open(newline="", encoding="utf-8") as stream:
        zone_rows = list(csv.DictReader(stream))
    ownership = {row["service"]: row for row in ownership_rows}

    known_zones = {row["zone"] for row in dns_rows if row.get("zone")}
    known_zones.update(
        row["zone_or_project"]
        for row in source_rows
        if row["surface"] in {"worker-custom-domain", "worker-route"}
        and row["zone_or_project"]
        and "." in row["zone_or_project"]
    )
    worker_domain_sets = {}
    page_domains = {}
    for row in source_rows:
        if row["surface"] == "worker-custom-domain":
            worker_domain_sets.setdefault(row["service"], set()).add(row["zone_or_project"])
        elif row["surface"] == "pages-production":
            attached_domain = route_domain(row["host_or_pattern"], known_zones)
            if attached_domain:
                page_domains.setdefault(row["service"], set()).add(attached_domain)
    worker_domains = {
        service: next(iter(domains))
        for service, domains in worker_domain_sets.items()
        if len(domains) == 1
    }
    page_domains = {
        project: next(iter(domains))
        for project, domains in page_domains.items()
        if len(domains) == 1
    }

    fields = [
        "account",
        "surface",
        "host_or_pattern",
        "service",
        "zone_or_project",
        "control_plane_state",
        "preview_urls_enabled",
        "deployment_id",
        "created_on",
        "product_domain",
        "mapping_basis",
        "cohort_state",
        "app_key",
        "product_source_repository_hint",
        "app_id_state",
        "kotoba_amu_state",
        "source_owner_status",
        "source_owner_repository_candidates",
        "source_owner_evidence",
        "source_owner_evidence_paths",
        "service_migration_state",
        "target_host",
        "evidence_limit",
        "external_http_reachability",
    ]
    output_rows = []
    for row in source_rows:
        domain, basis = domain_for(row, known_zones, worker_domains, page_domains)
        if row["surface"] == "dns-record":
            basis = "dns-zone-record-hostname"
        app_key, repo = DOMAIN_TO_APP.get(domain, ("unclassified", "unclassified"))
        owner = ownership.get(row["service"], {})
        owner_status = owner.get("owner_status", "owner-review-pending")
        owner_repo = owner.get("owner_repository_candidates", "")
        owner_evidence = owner.get("evidence_basis", "no-service-owner-row")
        if domain not in COHORT:
            if owner_status == "resolved" and owner_repo:
                repo = owner_repo
            elif owner_status in {"product-owner-hint", "candidate", "ambiguous"} and owner_repo:
                repo = f"{owner_status}:{owner_repo}"
        app_id_state = (
            "evidenced:net.kotoba.murakumo"
            if domain == "murakumo.cloud"
            else "not-created-or-not-evidenced"
        )
        reachability = "not-probed"
        evidence_limit = (
            "Cloudflare API configuration snapshot; not public HTTP, origin, or runtime proof"
        )
        if row["surface"] == "dns-record":
            evidence_limit = (
                "Cloudflare DNS control-plane metadata; record content and target omitted; "
                "authoritative delegation, public DNS, and serving behavior not established"
            )
        readback = root_readbacks.get(domain)
        if readback and row["host_or_pattern"] == domain and row["surface"] in {
            "worker-custom-domain", "pages-production"
        }:
            reachability = root_reachability(readback)
            evidence_limit = (
                "Cloudflare API configuration plus direct root HTTPS readback: "
                f"GET={readback['https_get_status']}; edge_ip={readback['https_remote_ip'] or 'none'}. "
                "This does not identify an independent origin or prove app runtime; "
                "see awai-app-public-root-readback-2026-10-16.csv."
            )
        output_rows.append(
            {
                **row,
                "product_domain": domain or "",
                "mapping_basis": basis,
                "cohort_state": "first-product-cohort" if domain in COHORT else (
                    "other-public-domain" if domain not in {"workers.dev", "pages.dev", ""} else "platform-hostname"
                ),
                "app_key": app_key,
                "product_source_repository_hint": repo,
                "app_id_state": app_id_state,
                "kotoba_amu_state": state_for(domain, row),
                "source_owner_status": owner_status,
                "source_owner_repository_candidates": owner_repo,
                "source_owner_evidence": owner_evidence,
                "source_owner_evidence_paths": owner.get("evidence_paths", ""),
                "service_migration_state": (
                    "first-product-cohort-migration-pending"
                    if domain in COHORT
                    else migration_state(owner)
                ),
                "target_host": "itonami.app Awai app runtime; independent DNS/TLS required",
                "evidence_limit": evidence_limit,
                "external_http_reachability": reachability,
            }
        )

    for row in pages_deployment_rows:
        project = row["project"]
        parsed_url = urlsplit(row["url"])
        if (parsed_url.scheme != "https" or not parsed_url.hostname
                or not parsed_url.hostname.endswith(".pages.dev")):
            raise ValueError(f"invalid Pages deployment URL for {project}: {row['url']}")
        owner = ownership.get(project, {})
        owner_status = owner.get("owner_status", "owner-review-pending")
        owner_repo = owner.get("owner_repository_candidates", "")
        domain = PAGES_PROJECT_TO_DOMAIN.get(project, "pages.dev")
        app_key, repo = DOMAIN_TO_APP.get(domain, ("unclassified", "unclassified"))
        if domain not in COHORT:
            if owner_status == "resolved" and owner_repo:
                repo = owner_repo
            elif owner_status in {"product-owner-hint", "candidate", "ambiguous"} and owner_repo:
                repo = f"{owner_status}:{owner_repo}"
        if domain in COHORT:
            migration = "first-product-cohort-migration-pending"
        elif domain == "pages.dev":
            migration = "candidate-exposure-reachability-unverified"
        else:
            migration = migration_state(owner)
        output_rows.append({
            "account": "cloud-kotoba",
            "surface": "pages-production-deployment",
            "host_or_pattern": parsed_url.hostname,
            "service": project,
            "zone_or_project": project,
            "control_plane_state": row["latest_stage_status"],
            "preview_urls_enabled": "",
            "deployment_id": row["deployment_id"],
            "created_on": row["created_on"],
            "product_domain": domain,
            "mapping_basis": (
                "pages-project-domain-association"
                if project in PAGES_PROJECT_TO_DOMAIN
                and project not in {
                    "itonami-agent-treasury-tokyo2026",
                    "cloud-itonami-partners",
                    "cloud-itonami-isic-6492",
                    "cloud-itonami-commitment-ledger",
                }
                else "project-name-domain-owner-hint"
                if project in PAGES_PROJECT_TO_DOMAIN
                else "pages-dev-deployment-project"
            ),
            "cohort_state": (
                "first-product-cohort" if domain in COHORT
                else "platform-hostname" if domain == "pages.dev"
                else "other-public-domain"
            ),
            "app_key": app_key,
            "product_source_repository_hint": repo,
            "app_id_state": "not-created-or-not-evidenced",
            "kotoba_amu_state": state_for(domain, row),
            "source_owner_status": owner_status,
            "source_owner_repository_candidates": owner_repo,
            "source_owner_evidence": owner.get("evidence_basis", "no-pages-project-owner-row"),
            "source_owner_evidence_paths": owner.get("evidence_paths", ""),
            "service_migration_state": migration,
            "target_host": "itonami.app Awai app runtime; independent DNS/TLS required",
            "evidence_limit": (
                "Cloudflare Pages deployment API record; deployment stage is not public HTTP reachability"
            ),
            "external_http_reachability": row["external_http_reachability"],
        })

    # These roots have no exact attached endpoint row in the provider snapshot;
    # retain the direct readback as a migration row instead of implying they
    # were not checked.
    attached_roots = {
        row["product_domain"]
        for row in output_rows
        if row["host_or_pattern"] == row["product_domain"]
        and row["surface"] in {"worker-custom-domain", "pages-production"}
    }
    for domain, readback in sorted(root_readbacks.items()):
        if domain not in DOMAIN_TO_APP or domain in attached_roots:
            continue
        app_key, repo = DOMAIN_TO_APP[domain]
        output_rows.append(
            {
                "account": "cloud-kotoba",
                "surface": "public-root-readback",
                "host_or_pattern": domain,
                "service": f"{app_key} public root (direct DNS/HTTPS readback)",
                "zone_or_project": domain,
                "control_plane_state": "public-root-no-a-aaaa-answer",
                "preview_urls_enabled": "",
                "deployment_id": "",
                "created_on": "",
                "product_domain": domain,
                "mapping_basis": "direct-public-root-readback",
                "cohort_state": "first-product-cohort",
                "app_key": app_key,
                "product_source_repository_hint": repo,
                "app_id_state": "not-created-or-not-evidenced",
                "kotoba_amu_state": state_for(domain, {}),
                "source_owner_status": "domain-inventory",
                "source_owner_repository_candidates": repo,
                "source_owner_evidence": "domain-inventory-and-public-root-readback",
                "source_owner_evidence_paths": "docs/awai-app-public-root-readback-2026-10-16.csv",
                "service_migration_state": "first-product-cohort-migration-pending",
                "target_host": "itonami.app Awai app runtime; independent DNS/TLS required",
                "evidence_limit": readback["evidence_limit"],
                "external_http_reachability": root_reachability(readback),
            }
        )

    # Source/provider disagreement is a migration item even when there is no
    # current DNS or Worker route for the advertised root domain.
    output_rows.append(
        {
            "account": "cloud-kotoba",
            "surface": "source-declared-no-public-route",
            "host_or_pattern": "oppai.fans, www.oppai.fans",
            "service": "fans-oppai source-declared custom domains (zone has no DNS records or Worker/Pages custom domains)",
            "zone_or_project": "oppai.fans",
            "control_plane_state": "source-provider-drift",
            "preview_urls_enabled": "",
            "deployment_id": "",
            "created_on": "",
            "product_domain": "oppai.fans",
            "mapping_basis": "source-manifest-and-zone-state",
            "cohort_state": "first-product-cohort",
            "app_key": "oppai",
            "product_source_repository_hint": "network-awai/fans-oppai",
            "app_id_state": "not-created-or-not-evidenced",
            "kotoba_amu_state": "service-core-local-wip-uncommitted-native-aarch64-selfhost-benchmarked-bootstrap-wasm-wired-build-release-pending",
            "source_owner_status": "resolved",
            "source_owner_repository_candidates": "network-awai/fans-oppai",
            "source_owner_evidence": "source-manifest-and-zone-state",
            "source_owner_evidence_paths": "",
            "service_migration_state": "first-product-cohort-migration-pending",
            "target_host": "itonami.app Awai app runtime; independent DNS/TLS required",
            "evidence_limit": "Source manifest plus Cloudflare API configuration; current public resolution not established",
            "external_http_reachability": "not-probed",
        }
    )

    # Keep active account zones that have no DNS records and no service row in
    # the migration catalog visible until external delegation is checked.
    represented_domains = {row["product_domain"] for row in output_rows if row["product_domain"]}
    empty_zone_rows = 0
    for zone in zone_rows:
        domain = zone["zone"]
        if (int(zone["dns_record_count"]) != 0
                or domain in represented_domains
                or zone["zone_status"] != "active"):
            continue
        output_rows.append({
            "account": zone["account"],
            "surface": "active-zone-no-service",
            "host_or_pattern": domain,
            "service": "",
            "zone_or_project": domain,
            "control_plane_state": "active-zone-zero-dns-records",
            "preview_urls_enabled": "",
            "deployment_id": "",
            "created_on": "",
            "product_domain": domain,
            "mapping_basis": "cloudflare-zone-recheck-2026-10-17",
            "cohort_state": "account-zone-no-service",
            "app_key": "unclassified",
            "product_source_repository_hint": "unclassified",
            "app_id_state": "not-assigned-no-service-observed",
            "kotoba_amu_state": "no-runtime-service-or-product-source-mapped",
            "source_owner_status": "no-service-observed",
            "source_owner_repository_candidates": "",
            "source_owner_evidence": (
                "active zone has zero DNS records and no current runtime endpoint row"
            ),
            "source_owner_evidence_paths": (
                "docs/awai-cloudflare-zone-recheck-2026-10-17.csv"
            ),
            "service_migration_state": "external-delegation-review-no-cloudflare-service",
            "target_host": (
                "No app target assigned; consider Itonami/Awai only after service and owner are identified"
            ),
            "evidence_limit": (
                "Cloudflare zone/DNS API only: zero configured records; registrar delegation, "
                "external authoritative DNS, public HTTP, and retirement intent remain unverified"
            ),
            "external_http_reachability": "not-probed-external-dns-unknown",
        })
        represented_domains.add(domain)
        empty_zone_rows += 1

    with OUTPUT.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        writer.writerows(output_rows)
    print(f"source_runtime_rows={len(source_rows)}")
    print(f"pages_production_candidate_rows={len(pages_deployment_rows)}")
    print(f"account_zone_no_service_rows={empty_zone_rows}")
    print(f"catalog_rows={len(output_rows)}")
    print(f"first_cohort_rows={sum(row['cohort_state'] == 'first-product-cohort' for row in output_rows)}")
    print(f"outside_cohort_rows={sum(row['cohort_state'] == 'other-public-domain' for row in output_rows)}")
    print(f"platform_alias_rows={sum(row['cohort_state'] == 'platform-hostname' for row in output_rows)}")
    print(f"unmapped_rows={sum(row['mapping_basis'] == 'unmapped' for row in output_rows)}")
    print(f"unclassified_app_key_rows={sum(row['app_key'] == 'unclassified' for row in output_rows)}")
    print(f"output={OUTPUT}")


if __name__ == "__main__":
    main()
