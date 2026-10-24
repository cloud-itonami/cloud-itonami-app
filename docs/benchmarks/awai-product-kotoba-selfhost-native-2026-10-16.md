# Awai product Kotoba cores: AMU native selfhost benchmark

This report measures the eight product decision cores written in Kotoba and
compiled with AMU's adopted native selfhost compiler. It is a microbenchmark of
decision kernels, not a full application, request, or deployment benchmark.
The latest native measurements below were rerun on 2026-10-16 against existing
builds whose recorded source hashes match the current product worktrees.

## Results

Each sample ran one million decisions. The harness used seven alternating
rounds and one warmup per executable path. The native value is the median
KEXE process wall time minus the median empty-loader process time. The
JavaScript reference ran warmed and in-process, so this comparison is
approximate. Host load was high across this rerun; raw driver and empty-loader
medians are recorded in [the refresh output](awai-product-kotoba-selfhost-native-refresh-2026-10-16.txt).

| Product | AMU selfhost native | JavaScript reference | Native / JS | Host load average (1/5/15 min) |
|---|---:|---:|---:|---:|
| Murakumo bid admission | 16.84 ns/decision | 10.05 ns/decision | 1.675× | 46.72 / 57.52 / 49.05 |
| Aozora Biscuit authorization | 48.24 ns/decision | 45.79 ns/decision | 1.053× | 44.18 / 56.82 / 48.85 |
| Isekai publish-tier admission | 7.08 ns/decision | 2.86 ns/decision | 2.471× | 44.18 / 56.82 / 48.85 |
| Babiniku proposal governor | 33.97 ns/decision | 19.62 ns/decision | 1.732× | 44.18 / 56.82 / 48.85 |
| x402 transfer preflight | 34.09 ns/decision | 30.82 ns/decision | 1.106× | 44.18 / 56.82 / 48.85 |
| Shinshi black-frame threshold | 21.25 ns/decision | 15.04 ns/decision | 1.413× | 42.00 / 56.16 / 48.67 |
| Oppai challenge window | 16.46 ns/decision | 21.69 ns/decision | 0.759× | 42.00 / 56.16 / 48.67 |
| Awai Network chat admission | 20.27 ns/decision | 16.19 ns/decision | 1.252× | 42.00 / 56.16 / 48.67 |

The host had 10 logical CPUs. Load was high during every sample, including the
shared app-admission refresh (load average 14.23 / 11.53 / 16.41). Native was
faster in one product row (Oppai) and slower in seven in this rerun. Results shifted
from the earlier sample set, which confirms that these are noisy diagnostics;
they do not support a general performance claim or product-latency estimate.

The shared app-plane kernels were also measured separately:

| Shared core | AMU selfhost native | JavaScript reference | Native / JS |
|---|---:|---:|---:|
| App install admission | 51.49 ns/decision | 8.62 ns/decision | 5.97× |
| Awai current-ref acceptance | 1,402.46 ns/decision | 816.06 ns/decision | 1.72× |

The source, benchmark drivers, and build scripts are in each product's
feature-worktree `bench/` directory. The aggregate measurements and host loads
are recorded here and in `awai-app-runtime.md`; the latest shared app-admission
refresh is in
[`awai-app-admission-selfhost-refresh-2026-10-16.txt`](awai-app-admission-selfhost-refresh-2026-10-16.txt).
Build scripts identify their per-run output workspaces. The native builds used the
verified adopted AMU seed, a trusted C KEXE loader, and no JVM, Node, or Graal
compiler fallback. Their source closures were resolved with
`--source-path --unpinned`; these are not pinned app release builds.

## Browser artifact boundary

The adopted native selfhost compiler accepts `aarch64-macos` only. A direct
compile attempt of the shared Kotoba core with `--target wasm32-browser`
returned `E2 unsupported target (only aarch64-macos)`. Product callsites that
need browser or Worker modules therefore use separate JVM-free AMU/NBB
bootstrap output, whose slower WASM and adapter measurements are documented
in [the portable-module benchmark](awai-product-kotoba-wasm-2026-10-16.md).
The native results here establish selfhost compilation and execution of the
decision kernels on the native target; they do not establish selfhost-built
browser artifacts, application builds, signed Awai releases, or production
deployment.

The product source integration and migration status are tracked in
[`awai-app-runtime.md`](../awai-app-runtime.md).
