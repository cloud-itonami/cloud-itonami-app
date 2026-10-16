#!/usr/bin/env python3
"""Build and compare the eight indexed product decision cores with AMU selfhost."""
import argparse
import csv
import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "bench/products/build_amu_selfhost.py"
BENCH = ROOT / "bench/products/benchmark_amu_selfhost.mjs"
PRODUCTS = [
    ("murakumo", "cloud_murakumo/compute_market_admission.kotoba",
     "compute_market_admission_selfhost_driver.kotoba",
     "compute_market_admission_selfhost_empty_driver.kotoba"),
    ("aozora", "aozora/pds/agent_biscuit_admission_core.kotoba",
     "aozora_agent_biscuit_selfhost_driver.kotoba",
     "aozora_agent_biscuit_selfhost_empty_driver.kotoba"),
    ("oppai", "oppai/gen/challenge_window_core.kotoba",
     "oppai_challenge_window_selfhost_driver.kotoba",
     "oppai_challenge_window_selfhost_empty_driver.kotoba"),
    ("isekai", "isekai/moderation/publish_admission_core.kotoba",
     "isekai_publish_admission_selfhost_driver.kotoba",
     "isekai_publish_admission_selfhost_empty_driver.kotoba"),
    ("babiniku", "babiniku/governor_core.kotoba",
     "babiniku_governor_selfhost_driver.kotoba",
     "babiniku_governor_selfhost_empty_driver.kotoba"),
    ("x402", "nexus/wallet/transfer_preflight_core.kotoba",
     "x402_transfer_preflight_selfhost_driver.kotoba",
     "x402_transfer_preflight_selfhost_empty_driver.kotoba"),
    ("shinshi", "shinshi/common/video_black_frame_core.kotoba",
     "shinshi_video_black_frame_selfhost_driver.kotoba",
     "shinshi_video_black_frame_selfhost_empty_driver.kotoba"),
    ("awai", "awai/apex/chat_admission_core.kotoba",
     "awai_apex_chat_admission_selfhost_driver.kotoba",
     "awai_apex_chat_admission_selfhost_empty_driver.kotoba"),
]


def parse_fields(text):
    return dict(line.split("=", 1) for line in text.splitlines() if "=" in line)


def run(argv, log_path):
    result = subprocess.run(argv, cwd=ROOT, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, check=False)
    log_path.write_text(result.stdout)
    if result.returncode:
        raise SystemExit(f"command failed ({result.returncode}); see {log_path}")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--amu-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--allow-contended", action="store_true",
                        help="run when 1-minute load exceeds logical CPUs; results will be diagnostics")
    args = parser.parse_args()

    output = args.output.resolve()
    if output.exists():
        raise SystemExit(f"refusing to replace benchmark output: {output}")
    load = os.getloadavg()
    cpus = os.cpu_count() or 1
    if load[0] > cpus and not args.allow_contended:
        raise SystemExit(f"host is contended (1-minute load {load[0]:.2f} > {cpus} logical CPUs); retry when quiet or pass --allow-contended for diagnostics")

    output.mkdir(parents=True)
    (output / "host.json").write_text(json.dumps({
        "platform": sys.platform,
        "machine": os.uname().machine,
        "logicalCpus": cpus,
        "loadBefore": list(load),
        "allowContended": args.allow_contended,
    }, indent=2) + "\n")
    rows = []
    for domain, core, driver, empty_driver in PRODUCTS:
        build_dir = output / "build" / domain
        build_dir.parent.mkdir(parents=True, exist_ok=True)
        build_text = run([
            sys.executable, str(BUILD), "--amu-root", str(args.amu_root.resolve()),
            "--core", core, "--driver", f"products/{driver}",
            "--empty-driver", f"products/{empty_driver}", "--output", str(build_dir),
        ], output / f"{domain}-build.log")
        build_report = json.loads((build_dir / "report.json").read_text())
        benchmark_text = run([
            "node", str(BENCH), domain, str(build_dir), str(output / f"{domain}.txt"),
        ], output / f"{domain}-benchmark.log")
        values = parse_fields(benchmark_text)
        rows.append({
            "domain": domain,
            "sourceSha256": values.get("source-sha256", ""),
            "compilerSha256": build_report.get("compilerSha256", ""),
            "nativeImageSha256": build_report.get("artifacts", {}).get("decision", {}).get("nativeImageSha256", ""),
            "iterations": 1_000_000,
            "rounds": 7,
            "nativeAdjustedMedianNsPerDecision": values.get("selfhost-native-adjusted-median-ns/decision", ""),
            "javascriptMedianNsPerDecision": values.get("javascript-reference-median-ns/decision", ""),
            "nativeOverJavaScriptRatio": values.get("ratio-selfhost-native-over-js", ""),
            "loadBefore": values.get("load-before-1-5-15", ""),
            "loadAfter": values.get("load-after-1-5-15", ""),
            "logicalCpus": values.get("logical-cpus", ""),
            "qualification": values.get("qualification", ""),
        })

    with (output / "results.csv").open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)
    (output / "suite.json").write_text(json.dumps({
        "status": "complete",
        "domains": len(rows),
        "resultsCsv": str(output / "results.csv"),
        "hostAfter": list(os.getloadavg()),
        "rows": rows,
    }, indent=2) + "\n")
    print(output / "results.csv")


if __name__ == "__main__":
    main()
