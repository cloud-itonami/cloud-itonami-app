#!/usr/bin/env python3
"""Resolve Cloudflare service IDs to local source repositories where evidenced."""

import argparse
import csv
import json
import os
import re
import tomllib
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKSPACE = ROOT.parents[2]
RUNTIME = ROOT / "docs/awai-cloudflare-runtime-endpoints-2026-10-17.csv"
MIGRATION = ROOT / "docs/awai-app-public-endpoint-migration-2026-10-17.csv"
REMOTE_CONFIG_EVIDENCE = ROOT / "docs/awai-app-canonical-source-config-evidence-2026-10-17.csv"
OUTPUT = ROOT / "docs/awai-app-service-ownership-2026-10-17.csv"
EXCLUDED_ORG_ROOTS = {".git", "orgs", "workspaces", "wt"}
SKIP_DIRS = {
    ".git", ".worktrees", "worktrees", "wt", "node_modules", "target",
    "dist", "build", ".wrangler", ".cache", "vendor",
}
WRANGLER_NAME_RE = re.compile(
    r"wrangler(?:\.[A-Za-z0-9_-]+)?\.(?:json|jsonc|toml|yaml|yml)$"
)


def remote_config_evidence():
    rows = []
    with REMOTE_CONFIG_EVIDENCE.open(newline="", encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            service = row.get("service", "").strip()
            repo = row.get("repository", "").strip()
            commit = row.get("commit", "").strip()
            source_path = row.get("path", "").strip()
            blob = row.get("blob_sha", "").strip()
            if not all((service, repo, commit, source_path, blob)):
                raise SystemExit(f"incomplete canonical source evidence row: {row}")
            rows.append({
                "service": service,
                "repository": repo,
                "path": f"{repo}@{commit}:{source_path}#{blob}",
            })
    return rows


def apply_remote_evidence_only():
    """Apply pinned remote config snapshots without scanning every checkout."""
    with OUTPUT.open(newline="", encoding="utf-8") as stream:
        rows = list(csv.DictReader(stream))
        fields = list(rows[0]) if rows else []
    by_service = {row["service"]: row for row in rows}
    grouped = defaultdict(list)
    for evidence in remote_config_evidence():
        grouped[evidence["service"]].append(evidence)

    changed = []
    for service, evidence_rows in grouped.items():
        row = by_service.get(service)
        if row is None:
            raise SystemExit(f"service missing from existing ownership map: {service}")
        repositories = set(filter(None, row["owner_repository_candidates"].split(";")))
        repositories.update(item["repository"] for item in evidence_rows)
        paths = set(filter(None, row["evidence_paths"].split(";")))
        paths.update(item["path"] for item in evidence_rows)
        row["owner_repository_candidates"] = ";".join(sorted(repositories))
        row["evidence_paths"] = ";".join(sorted(paths))
        if len(repositories) == 1:
            row["owner_status"] = "resolved"
            row["evidence_basis"] = (
                "canonical-wrangler-name-local-and-remote"
                if row["evidence_basis"] == "canonical-wrangler-name"
                else "canonical-remote-wrangler-name"
            )
        else:
            row["owner_status"] = "ambiguous"
            row["evidence_basis"] = "multiple-canonical-wrangler-names"
        changed.append(service)

    with OUTPUT.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    print(json.dumps({"updated_services": sorted(changed), "output": str(OUTPUT)},
                     sort_keys=True))


def repository_for(path):
    for parent in path.parents:
        if (parent / ".git").exists():
            try:
                return parent.relative_to(WORKSPACE).as_posix()
            except ValueError:
                return ""
    return ""


def declared_names(path):
    text = path.read_text(encoding="utf-8", errors="replace")
    if path.suffix == ".toml":
        try:
            value = tomllib.loads(text).get("name")
            return {value} if isinstance(value, str) else set()
        except tomllib.TOMLDecodeError:
            return set(re.findall(r"(?m)^\s*name\s*=\s*['\"]([^'\"]+)['\"]", text))
    return set(re.findall(r"\"name\"\s*:\s*\"([^\"]+)\"", text))


def organization_roots():
    """Return canonical top-level org folders, excluding workspace containers."""
    for path in sorted(WORKSPACE.iterdir()):
        if not path.is_dir() or path.name in EXCLUDED_ORG_ROOTS:
            continue
        if any(child.is_dir() and (child / ".git").exists() for child in path.iterdir()):
            yield path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--remote-evidence-only", action="store_true",
        help="apply pinned remote source-config snapshots without a workspace scan")
    args = parser.parse_args()
    if args.remote_evidence_only:
        apply_remote_evidence_only()
        return

    with RUNTIME.open(newline="", encoding="utf-8") as stream:
        runtime_rows = list(csv.DictReader(stream))
    with MIGRATION.open(newline="", encoding="utf-8") as stream:
        migration_rows = list(csv.DictReader(stream))

    grouped = defaultdict(list)
    curated = defaultdict(set)
    service_domains = defaultdict(set)
    for row in runtime_rows:
        if row.get("service"):
            grouped[row["service"]].append(row)
    for row in migration_rows:
        service = row.get("service", "")
        repo = row.get("product_source_repository_hint", "")
        # Only the explicit first-product-cohort map is curated input. The
        # generated outside-cohort ownership hints below must not feed back
        # into the next ownership pass as stronger evidence.
        if row.get("cohort_state") == "first-product-cohort" and service and repo and repo != "unclassified":
            curated[service].add(repo)
        if service and row.get("product_domain"):
            service_domains[service].add(row["product_domain"])

    config_candidates = defaultdict(set)
    config_paths = defaultdict(set)
    remote_config_candidates = defaultdict(set)
    remote_config_paths = defaultdict(set)
    for evidence in remote_config_evidence():
        remote_config_candidates[evidence["service"]].add(evidence["repository"])
        remote_config_paths[evidence["service"]].add(evidence["path"])

    exact_repo_names = defaultdict(set)
    for org_root in organization_roots():
        for repo_dir in org_root.iterdir():
            if repo_dir.is_dir() and (repo_dir / ".git").exists():
                exact_repo_names[repo_dir.name].add(repo_dir.relative_to(WORKSPACE).as_posix())
        for directory, children, files in os.walk(org_root):
            children[:] = [name for name in children if name not in SKIP_DIRS]
            for filename in files:
                if not WRANGLER_NAME_RE.fullmatch(filename):
                    continue
                path = Path(directory) / filename
                repo = repository_for(path)
                if not repo:
                    continue
                for name in declared_names(path):
                    config_candidates[name].add(repo)
                    config_paths[name].add(path.relative_to(WORKSPACE).as_posix())

    fields = [
        "service", "account", "endpoint_count", "surfaces", "domains",
        "owner_status", "owner_repository_candidates", "evidence_basis", "evidence_paths",
    ]
    rows = []
    for service, endpoints in sorted(grouped.items()):
        curated_repos = curated.get(service, set())
        config_repos = (config_candidates.get(service, set())
                        | remote_config_candidates.get(service, set()))
        path_evidence = (config_paths.get(service, set())
                         | remote_config_paths.get(service, set()))
        # A unique config `name` match identifies this service's source repo.
        # Product-domain mappings identify app ownership and are kept as a
        # separate hint in the migration catalog; a product may contain many
        # services implemented in different repositories.
        if len(config_repos) == 1:
            candidates = config_repos
            if remote_config_candidates.get(service):
                basis = ("canonical-wrangler-name-local-and-remote"
                         if config_candidates.get(service)
                         else "canonical-remote-wrangler-name")
            else:
                basis = "canonical-wrangler-name"
            status = "resolved"
        elif len(config_repos) > 1:
            candidates = config_repos | curated_repos
            basis = "multiple-canonical-wrangler-names"
            status = "ambiguous"
        elif len(curated_repos) == 1:
            candidates = curated_repos
            basis = "curated-product-owner-hint-no-canonical-wrangler-match"
            status = "product-owner-hint"
        elif len(curated_repos) > 1:
            candidates = curated_repos
            basis = "conflicting-product-domain-owner-hints"
            status = "ambiguous"
        else:
            candidates = exact_repo_names.get(service, set())
            if len(candidates) == 1:
                basis = "canonical-repository-name"
                status = "candidate"
            elif len(candidates) > 1:
                basis = "multiple-canonical-repository-names"
                status = "ambiguous"
            else:
                basis = "no-canonical-source-match"
                status = "owner-review-pending"
        rows.append({
            "service": service,
            "account": ";".join(sorted({r["account"] for r in endpoints})),
            "endpoint_count": len(endpoints),
            "surfaces": ";".join(sorted({r["surface"] for r in endpoints})),
            "domains": ";".join(sorted(service_domains.get(service, set()))),
            "owner_status": status,
            "owner_repository_candidates": ";".join(sorted(candidates)),
            "evidence_basis": basis,
            "evidence_paths": ";".join(sorted(path_evidence)),
        })

    with OUTPUT.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    status_counts = defaultdict(int)
    for row in rows:
        status_counts[row["owner_status"]] += 1
    print(json.dumps({"services": len(rows), "endpoint_rows": len(runtime_rows),
                      "owner_status_counts": dict(sorted(status_counts.items())),
                      "output": str(OUTPUT)}, sort_keys=True))


if __name__ == "__main__":
    main()
