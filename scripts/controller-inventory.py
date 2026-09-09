#!/usr/bin/env python3
"""Read-only controller workspace inventory; does not copy files or resolve credentials.

Usage: controller-inventory.py --input /tmp/bot-workspace-paths.txt --output manifest.json
Tracked-byte estimates use lstat (symlinks are not followed). Git metadata comes only
from each existing local repository. A missing checkout is not an absent repository.
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import subprocess
from urllib.parse import urlsplit, urlunsplit


def git(path, *args):
    result = subprocess.run(["git", "-C", str(path), *args], stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, timeout=120, check=False,
                            env={**os.environ, "GIT_OPTIONAL_LOCKS": "0"})
    return result.stdout if result.returncode == 0 else None


def safe_remote(value):
    if not value:
        return None
    value = value.decode(errors="replace").strip()
    if "://" in value:
        parsed = urlsplit(value)
        host = parsed.hostname or ""
        if parsed.port:
            host += ":" + str(parsed.port)
        return urlunsplit((parsed.scheme, host, parsed.path, "", ""))
    # Preserve SSH usernames (not credentials), omit query/fragment data.
    return value.split("?", 1)[0].split("#", 1)[0]


def file_estimate(root, names):
    count = size = missing = symlinks = 0
    for name in names:
        if not name:
            continue
        path = root / os.fsdecode(name)
        try:
            info = path.lstat()
        except OSError:
            missing += 1
            continue
        if path.is_symlink():
            symlinks += 1
        elif not path.is_file():
            continue
        count += 1
        size += info.st_size
    return {"files": count, "bytes": size, "missingTrackedFiles": missing,
            "symlinks": symlinks, "includesUntracked": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source-root", type=Path,
                        default=Path("/Users/junkawasaki/github/com-junkawasaki"))
    parser.add_argument("--runtime-root", type=Path,
                        default=Path("/Users/junkawasaki/.cloud-itonami/data/bot-workspaces"))
    parser.add_argument("--target-root", type=Path,
                        default=Path("/Users/judah/itonami-workspace"))
    args = parser.parse_args()
    paths = sorted(set(line.strip() for line in args.input.read_text().splitlines()
                       if line.strip()))
    entries, repositories = [], {}
    for original in paths:
        source = Path(original)
        canonical = source.resolve(strict=False)
        flags = []
        if source.is_relative_to(args.source_root):
            target = args.target_root / source.relative_to(args.source_root)
            category = "repository-workspace"
        elif source.is_relative_to(args.runtime_root):
            target = args.target_root / ".bot-workspaces" / source.relative_to(args.runtime_root)
            category = "owner-runtime-workspace"
            flags.append("operator-local-runtime-state")
        else:
            target = None
            category = "unmapped"
            flags.append("operator-local-unmapped")
        exists = source.exists()
        if not exists:
            flags.append("missing-local-checkout-or-workspace")
        if "gftd" in original:
            flags.append("legacy-name-review")
        if str(canonical) != str(source):
            flags.append("canonical-path-differs")
        entry = {"source": original, "canonical": str(canonical),
                 "target": str(target) if target else None, "category": category,
                 "exists": exists, "flags": flags, "gitRoot": None}
        if exists and source.is_dir():
            top = git(source, "rev-parse", "--show-toplevel")
            if top:
                root = Path(os.fsdecode(top).strip())
                entry["gitRoot"] = str(root)
                if str(root) not in repositories:
                    head = git(root, "rev-parse", "HEAD")
                    tracked = git(root, "ls-files", "-z")
                    status = git(root, "status", "--porcelain=v1", "-z", "--untracked-files=no")
                    remotes = git(root, "remote") or b""
                    remote_names = remotes.decode(errors="replace").splitlines()
                    remote_name = "origin" if "origin" in remote_names else (remote_names[0] if remote_names else None)
                    remote = git(root, "remote", "get-url", remote_name) if remote_name else None
                    repositories[str(root)] = {
                        "root": str(root), "head": head.decode().strip() if head else None,
                        "origin": safe_remote(remote), "remoteName": remote_name,
                        "trackedDirty": bool(status) if status is not None else None,
                        "trackedSnapshot": file_estimate(root, tracked.split(b"\0")) if tracked is not None else None,
                    }
            else:
                entry["flags"].append("non-git-workspace-needs-state-snapshot")
        entries.append(entry)
    estimates = [r["trackedSnapshot"] for r in repositories.values() if r["trackedSnapshot"]]
    totals = {"workspacePaths": len(entries), "existingPaths": sum(e["exists"] for e in entries),
              "missingPaths": sum(not e["exists"] for e in entries),
              "operatorLocalPaths": sum(e["category"] != "repository-workspace" for e in entries),
              "uniqueGitRoots": len(repositories),
              "dirtyGitRoots": sum(r["trackedDirty"] is True for r in repositories.values()),
              "trackedFiles": sum(e["files"] for e in estimates),
              "trackedBytes": sum(e["bytes"] for e in estimates)}
    manifest = {"schemaVersion": 1, "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                "readOnly": True, "targetRoot": str(args.target_root),
                "limitations": ["Missing local paths do not establish remote repository absence.",
                                "Tracked-byte estimate excludes untracked files, git objects, nested repositories not listed, and runtime state.",
                                "Symlinks are measured without following their targets; migration must validate them.",
                                "Inventory is not authority to copy operator credentials or private data."],
                "totals": totals, "workspaces": entries, "repositories": list(repositories.values())}
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(totals))


if __name__ == "__main__":
    main()
