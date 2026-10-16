# Kotoba / AMU selfhost decision-core recheck

This recheck reran eight Kotoba product decision cores plus Itonami's shared install-admission core on source-matched native images. Each matched build report verifies the adopted AMU compiler seed pins and records hashes for the exact Kotoba core and benchmark drivers. The native target is `aarch64-macos`; source closures were built with `--source-path --unpinned`.

## Results

| Decision core | AMU selfhost native | JavaScript reference | Native / JS |
|---|---:|---:|---:|
| Murakumo bid admission | 4.55 ns/decision | 3.55 ns/decision | 1.283× |
| Aozora Biscuit authorization | 15.67 ns/decision | 14.35 ns/decision | 1.092× |
| Isekai publish-tier admission | 2.65 ns/decision | 1.11 ns/decision | 2.393× |
| Babiniku proposal governor | 9.83 ns/decision | 5.87 ns/decision | 1.674× |
| x402 transfer preflight | 10.90 ns/decision | 10.50 ns/decision | 1.038× |
| Shinshi black-frame threshold | 6.19 ns/decision | 4.75 ns/decision | 1.303× |
| Oppai challenge window | 7.23 ns/decision | 6.51 ns/decision | 1.111× |
| Awai Network chat admission | 7.75 ns/decision | 5.01 ns/decision | 1.545× |
| Shared Itonami install admission | 28.13 ns/decision | 4.61 ns/decision | 6.102× |

In this source-matched recheck, AMU native was slower than the warmed JavaScript reference in all nine rows. The host had 10 logical CPUs; load averages ranged from 6.24–6.43 / 8.38–8.46 / 12.36–12.41 (1/5/15 minute averages). These numbers are diagnostics for small normalized decision kernels. They do not represent full application latency or establish an end-to-end speedup.

## High-load repeat

A second source-matched run completed all nine harnesses, but the host was heavily contended: 10 logical CPUs with load averages spanning 81.31–86.54 / 68.90–70.83 / 43.21–44.50. The resulting medians varied substantially from the recheck above, including native/JavaScript ratios below 1.0 for some rows. Treat this run as a reproducibility and harness-completion check, not a performance comparison.

| Decision core | AMU selfhost native | JavaScript reference | Native / JS |
|---|---:|---:|---:|
| Murakumo bid admission | 10.96 ns/decision | 10.31 ns/decision | 1.063× |
| Aozora Biscuit authorization | 42.03 ns/decision | 49.11 ns/decision | 0.856× |
| Isekai publish-tier admission | 10.25 ns/decision | 3.06 ns/decision | 3.352× |
| Babiniku proposal governor | 29.01 ns/decision | 23.14 ns/decision | 1.254× |
| x402 transfer preflight | 29.96 ns/decision | 30.12 ns/decision | 0.995× |
| Shinshi black-frame threshold | 18.44 ns/decision | 18.38 ns/decision | 1.003× |
| Oppai challenge window | 16.12 ns/decision | 19.96 ns/decision | 0.807× |
| Awai Network chat admission | 21.97 ns/decision | 13.26 ns/decision | 1.657× |
| Shared Itonami install admission | 81.65 ns/decision | 11.71 ns/decision | 6.972× |

The current machine-readable run record and per-product stdout are at `/Users/junkawasaki/github/workspaces/codex/itonami-app-plane-next-kotoba-selfhost-refresh-20261016/results.json` and that directory's `*.txt` files. The lower-load recheck stdout remains in [`awai-product-kotoba-selfhost-native-recheck-2026-10-16.txt`](awai-product-kotoba-selfhost-native-recheck-2026-10-16.txt).

## Method and limits

Every harness ran 1,000,000 decisions over seven alternating rounds, with one warmup per executable path. Native time is the median process wall time minus the median empty KEXE-loader time; JavaScript ran warmed in-process. The workload case mix differs by decision core. The measurements exclude request parsing, cryptography, storage, chain or peer reads, network I/O, rendering, and the complete app lifecycle.

The adopted native selfhost compiler accepts `aarch64-macos` only. Product callsites that need browser/Worker portability currently use separate bootstrap-emitted WASM wrappers. Those wrappers are not selfhost-built app artifacts, and no Worker/Pages build, signed Awai release, production chain profile, or deployment is proven by this benchmark.

The lower-load run's full stdout, exact source hashes, build report paths, and per-run load averages are in [`awai-product-kotoba-selfhost-native-recheck-2026-10-16.txt`](awai-product-kotoba-selfhost-native-recheck-2026-10-16.txt). The machine-readable manifest now points to the high-load repeat described above.

## Product source carry-through (2026-10-17)

This 2026-10-16 table remains historical. In the shared Itonami worktree,
Aozora and x402 were moved to single-record Kotoba input ABIs and both updated
cores passed an AMU adopted-seed native build. Their prior rows above use the
older flat-argument sources. Contended current-source diagnostics are recorded
in [`awai-kotoba-amu-selfhost-refresh-2026-10-17.md`](awai-kotoba-amu-selfhost-refresh-2026-10-17.md);
they are not comparable performance results. The product repositories and
callsite integrations remain pending. Seven product callsites still use
separate bootstrap-emitted WASM wrappers; Shinshi's threshold remains an
offline workflow. This benchmark qualifies native decision kernels only. It
does not qualify those WASM outputs, integrated product builds, full app
behavior, signed Awai releases, the production chain profile, or deployments.
