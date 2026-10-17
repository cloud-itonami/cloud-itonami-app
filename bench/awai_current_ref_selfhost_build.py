#!/usr/bin/env python3
"""Build the Awai current-ref benchmark with Amu's adopted native seed."""
import argparse
import hashlib
import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_exports(container):
    header, payload = container.read_bytes().split(b"\n\n", 1)
    lines = header.decode("utf-8").splitlines()
    marker = lines[0].split()
    if len(marker) != 3 or marker[0] != "KSEED1" or int(marker[1]) != len(payload):
        raise SystemExit(f"invalid native container: {container}")
    exports = {}
    for line in lines[1:]:
        name, offset, arity = line.split()
        exports[name] = {"offset": int(offset), "arity": int(arity)}
    if not exports.get("main") or exports["main"]["arity"] != 0:
        raise SystemExit(f"expected a zero-argument main export: {container}")
    return payload, exports


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--amu-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    amu = args.amu_root.resolve()
    out = args.output.resolve()
    compiler = amu / "seed/bootstrap/adopted-20261012.bin"
    loader_source = amu / "seed/bootstrap/adopted-loader-20261012.c"
    verify_script = amu / "scripts/seed/build-adopted.py"
    inputs = [compiler, loader_source, verify_script,
              ROOT / "src/cloud/itonami/app/awai_current_ref_core.kotoba",
              ROOT / "bench/awai_current_ref_selfhost_driver.kotoba",
              ROOT / "bench/awai_current_ref_selfhost_empty_driver.kotoba"]
    if any(not path.is_file() for path in inputs):
        raise SystemExit("missing compiler or benchmark source input")
    if out.exists():
        raise SystemExit(f"refusing to replace existing benchmark workspace: {out}")

    subprocess.run(["python3", str(verify_script), "--verify-only"],
                   cwd=amu, check=True, capture_output=True, text=True)
    pins = {str(path): sha256(path) for path in inputs}
    out.mkdir(parents=True)
    host = out / "host"
    artifacts = out / "artifacts"
    host.mkdir()
    artifacts.mkdir()
    project = artifacts / "project"
    source_dir = project / "src/cloud/itonami/app"
    bench_dir = project / "bench"
    source_dir.mkdir(parents=True)
    bench_dir.mkdir(parents=True)
    core = source_dir / "awai_current_ref_core.kotoba"
    driver = bench_dir / "awai_current_ref_selfhost_driver.kotoba"
    empty = bench_dir / "awai_current_ref_selfhost_empty_driver.kotoba"
    for source, destination in zip(inputs[3:], (core, driver, empty)):
        destination.write_bytes(source.read_bytes())

    loader = host / "kexe-loader"
    env = dict(os.environ)
    env.update(KEXE_COMMAND="1", KEXE_CAP_RESOURCES_35=str(artifacts),
               KEXE_FUEL="off", KEXE_VECTORS="4194304",
               KEXE_VECTOR_ITEMS="134217728", KEXE_STRING_POOL="268435456",
               KEXE_PAIRS="16777216", KEXE_CPU_SECONDS="1800",
               KEXE_WALL_SECONDS="1800", TMPDIR=str(artifacts))
    commands = []

    def run(name, argv):
        result = subprocess.run(list(map(str, argv)), cwd=artifacts, env=env,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, timeout=1800)
        (host / f"{name}.log").write_text(result.stdout)
        commands.append({"name": name, "argv": list(map(str, argv)),
                         "exit": result.returncode})
        (host / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")
        if result.returncode:
            raise SystemExit(f"failed {name}; log preserved at {host / (name + '.log')}")
        return result.stdout

    run("loader-build", ["clang", "-std=c11", "-O2", loader_source,
                         "-o", loader])
    built = {}
    for name, source in (("current-ref", driver), ("empty", empty)):
        container = artifacts / f"{name}.kseed"
        native = artifacts / f"{name}.bin"
        run(f"{name}-compile", [loader, compiler, "0", "0", "aarch64",
                                "35,37,38,39", "--", "compile", source,
                                "--source-path", project / "src", "--unpinned",
                                "--target", "aarch64-macos", "--output", container])
        run(f"{name}-extract", [loader, compiler, "0", "0", "aarch64",
                                "35,37,38,39", "--", "extract-native", container,
                                "--symbol", "main", "--output", native])
        payload, exports = parse_exports(container)
        if payload != native.read_bytes():
            raise SystemExit(f"container payload differs from extracted image: {name}")
        built[name] = {"container": str(container), "containerSha256": sha256(container),
                       "nativeImage": str(native), "nativeImageSha256": sha256(native),
                       "bytes": len(payload), "exports": exports}

    if pins != {str(path): sha256(path) for path in inputs}:
        raise SystemExit("compiler/source input changed during the build")
    report = {"status": "PASS_SELFHOST_NATIVE_BENCH_BUILD",
              "compiler": str(compiler), "compilerSha256": sha256(compiler),
              "compilerSeedPinsVerified": True,
              "trustedLoaderSourceSha256": sha256(loader_source),
              "jvmNodeOrGraalCompilerFallback": False,
              "sourceClosure": {str(core): sha256(core), str(driver): sha256(driver),
                                str(empty): sha256(empty)},
              "sourceClosureMode": "--source-path --unpinned",
              "artifacts": built, "output": str(out)}
    (out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
