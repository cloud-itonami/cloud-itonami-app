# Kotoba / AMU selfhost refresh — 2026-10-17

## Refactor scope

The shared Itonami app host now routes install-admission and Amano current-ref
trust decisions through `.kotoba` cores. The host still owns I/O, DNSSEC,
signature verification, content retrieval, and capability consent. The
decision-core benchmark is separate from the complete app runtime.

The eight product decision cores for Murakumo, Aozora, Isekai, Babiniku, x402,
Shinshi, Oppai, and Awai Network are registered in the shared Kotoba oracle
and compiled to KIR artifacts. Their 16 native benchmark drivers are in
`bench/products/`, and `resources/cloud/itonami/app/product-policy-index.edn`
records domain, ABI, and measured-source status. This makes the decision source
set reviewable alongside the shared host; it does not yet wire the product
callsites into the Itonami runtime. Existing product callsites still use
separate bootstrap-emitted WASM wrappers, and Shinshi's core is also used by an
offline workflow. The native AMU selfhost measurements below qualify decision
cores, not native deployments of the eight apps.

## Requested domain mapping and runtime paths

| Domain / shared surface | Decision core | Current callsite path in the local refactor worktrees |
|---|---|---|
| `murakumo.cloud` | bid admission | `compute_market.cljk` calls the Kotoba core through its portable WASM wrapper |
| `aozora.app` | Biscuit operation authorization | `agent_biscuit.cljk` verifies the host-owned Biscuit facts, then calls the portable WASM wrapper |
| `oppai.fans` | challenge-window check | `guard.cljk` calls the portable WASM wrapper for bounded timestamp decisions |
| `isekai.network` | publish-tier admission | `account-tier.mjs` calls the portable WASM wrapper |
| `itonami.cloud` / `itonami.app` | shared install admission and Amano current-ref acceptance | `awai_apps.cljc` calls the shared Kotoba cores through `kotoba_oracle`; the shipped host artifact is KIR |
| `x402.nexus` | transfer preflight | `policy.cljk` calls the portable WASM wrapper before host custody checks |
| `shinshi.club` | black-frame threshold | the video-publish workflow calls the portable WASM wrapper; its measured scope is the offline threshold workflow |
| `babiniku.net` | proposal governor | `governor.cljk` calls the portable WASM wrapper |
| Awai Network (additional product core) | chat admission | `worker.cljk` calls the portable WASM wrapper |

The shared-host changes and centralized policy sources are local worktree edits;
they are not merged, released, or deployed. The user-listed Itonami domains use
the shared host cores, but this table does not claim that every service
currently exposed from `itonami.cloud` has been refactored.

AMU's adopted native selfhost target is `aarch64-macos`. It does not emit the
browser/Worker artifacts used by those portable callsites. AMU selfhost native
builds are therefore compilation and microbenchmark evidence for the same
decision sources, while the product callsites currently use separate
bootstrap-emitted WASM builds. The shared Itonami host executes its shipped KIR
through Kotoba's VM. None of these paths is a selfhost-native production app
deployment.

## AMU selfhost decision-core results

The 2026-10-16 comparison run used 1,000,000 decisions per sample, seven
alternating rounds, and one warmup per executable path. The source-closure
records in
[`awai-product-kotoba-selfhost-native-recheck-2026-10-16.txt`](awai-product-kotoba-selfhost-native-recheck-2026-10-16.txt)
match the centralized source hashes for Murakumo, Oppai, Isekai, Babiniku, Awai
Network, and shared Itonami admission. Aozora and x402 were refactored to
single-record input ABIs after their flat argument lists exceeded the current
compiler arity. Their prior rows below are previous-source comparisons. The
current Aozora and x402 sources both pass AMU native selfhost builds, but need
fresh timings before those rows describe the new source. Shinshi also needs a
rerun because its source changed after measurement. Same-day timing remains
unqualified while the host is heavily contended.

| Decision core | AMU selfhost native | JavaScript reference | Native / JS | Source match |
|---|---:|---:|---:|---|
| Murakumo bid admission | 4.55 ns/decision | 3.55 ns/decision | 1.283× | Exact |
| Aozora Biscuit authorization | 15.67 ns/decision | 14.35 ns/decision | 1.092× | Previous source |
| Isekai publish-tier admission | 2.65 ns/decision | 1.11 ns/decision | 2.393× | Exact |
| Babiniku proposal governor | 9.83 ns/decision | 5.87 ns/decision | 1.674× | Exact |
| x402 transfer preflight | 10.90 ns/decision | 10.50 ns/decision | 1.038× | Previous source |
| Shinshi black-frame threshold | 6.19 ns/decision | 4.75 ns/decision | 1.303× | Previous source |
| Oppai challenge window | 7.23 ns/decision | 6.51 ns/decision | 1.111× | Exact |
| Awai Network chat admission | 7.75 ns/decision | 5.01 ns/decision | 1.545× | Exact |
| Shared Itonami install admission | 28.13 ns/decision | 4.61 ns/decision | 6.102× | Exact |

### Centralized policy source hashes

| Domain | Source SHA-256 | Native-result source |
|---|---|---|
| `murakumo.cloud` | `81c98aa6cfa2d359b9708457084391fbce8d8fde1608159de9a5533bf94da87c` | Exact |
| `aozora.app` | `320be9896c4d33734988898f1485ee8cc986644aadedb9ca3a942b52237a34fe` | Exact current source; latest run is contended |
| `oppai.fans` | `97aceab094d46f7c72e98dcc231bf21ca2d7d21bc9c22a2d94cec00b52683368` | Exact |
| `isekai.network` | `837dba2d2cd51199591010220bb33b72c17bf6ce995966d12b9d90b61423abc8` | Exact |
| `babiniku.net` | `030af9b19b543b446401c5be3b59fcba0f2cfa2a037f3a1eea0f3761c0f5c7ab` | Exact |
| `x402.nexus` | `fa1eef144b9a8a524892b9ac1fe7ee66da583c53143b4d2f077e5fd4882bb650` | Exact current source; latest run is contended |
| `shinshi.club` | `27ff5e7e046c2fe88db3ffbd90df804ad9b1e30330a9707ddab7d7352bf82746` | Exact current micro-unit source; latest run is contended |
| `awai.network` | `1332d4ed5456ea3a1dd407f380ea678423275c3fb858e61563d193d5d380ce67` | Exact |
| shared `itonami.app` install core | `bd8455bfac6557362c08decbac92afc9dad00403f3e5b1dcf9ffa5c680ef48a6` | Exact |

AMU build provenance for the original measurement set is retained in the raw
recheck file linked above. The copied native drivers are now present under
`bench/products/`. Current Aozora and x402 sources and drivers build from the
adopted seed and have current-source contended timings below.

The reference run used 1,000,000 decisions, seven alternating rounds, and one
warmup per executable path. It ran on a 10-logical-CPU host at load averages
between 6.24–6.43 / 8.38–8.46 / 12.36–12.41. Each native result subtracts the
empty KEXE-loader median; the JavaScript reference is warmed in-process. The
native path was slower in all nine rows. These are normalized decision-kernel
microbenchmarks, not full request or application latency.

## 2026-10-17 rebuilds and repeats

Both shared cores rebuilt successfully from the current worktree using the
adopted AMU native selfhost seed (`47fba216e1a65b892458216575bbb70be228d86f4b7a6d73ebae84230279f20b`).
The build reports verify the seed pins and show no JVM, Node, or Graal compiler
fallback. Target: `aarch64-macos`; source closure: `--source-path --unpinned`.

| Core | Source SHA-256 | Native image SHA-256 | Build result |
|---|---|---|---|
| Shared app install admission | `bd8455bfac6557362c08decbac92afc9dad00403f3e5b1dcf9ffa5c680ef48a6` | `039458335a8d5e617cb792d3095093fea4bc57c68de4b581fb2e27263b80cfff` | Pass |
| Amano current-ref verification predicate | `d662bdfcb8392909aa70eaf1e23382951515a8984674add26f2d8e522e53ddc4` | `258214ce57683e714cb8b9e6800c6c155842c0ae3008a5929b01ac8c05db0ba0` | Pass, current source |
| Aozora Biscuit authorization | `320be9896c4d33734988898f1485ee8cc986644aadedb9ca3a942b52237a34fe` | `35355d90e9386caf28337693906a60d91d3134e182a5b876872fac2dee6de856` | Pass |
| x402 transfer preflight | `fa1eef144b9a8a524892b9ac1fe7ee66da583c53143b4d2f077e5fd4882bb650` | `1a11a6bcd071c301f52e4a60d5d81e030534f19d3022a7eb144f62aba3f20197` | Pass |

The same-day timed repeats are **contended diagnostics**, not comparable
performance results. The host has 10 logical CPUs. Current-ref load averages
were 75.97 / 61.84 / 52.00 before and 73.69 / 61.84 / 52.12 after its run.
Install-admission reported 205.27 ns/decision native versus 13.56 ns/decision
JavaScript after the 1-minute load rose to 80.52. Current-ref reported 724.28
ns/decision native versus 359.85 ns/decision JavaScript. Preserve these raw
outputs for traceability; do not use them to rank implementations.

The fresh current-ref benchmark alternated one million valid and
quorum-policy-mismatch decisions for seven rounds, with one warmup. Its
checksums passed. The install-admission run also passed its allow/refuse
checksum. The measurements exclude signature verification, registry and
network reads, content I/O, rendering, and the full app lifecycle. Empty-loader
subtraction is an approximate process-startup correction.

After those measurements, the current-ref predicate was split into smaller
Kotoba helpers to avoid stack overflow in the shared KIR runtime. The KIR was
regenerated and the current source rebuilt with AMU selfhost on
`aarch64-macos`; the current-source build is recorded in
`/Users/junkawasaki/github/workspaces/codex/itonami-app-plane-selfhost-bench-20261017-current-ref-refactor-kir-r2/report.json`.
No current-source timing was taken: the 1-minute host load was 66.20 on 10
logical CPUs immediately before the rebuild. The 724.28 / 359.85 ns current-ref
timing above belongs to the prior source hash and remains an unqualified,
contended diagnostic; it does not measure the refactored predicate.

The product-core source refresh on 2026-10-17 did not produce a comparable
timing run. The initial host load snapshot was `164.18 / 137.07 / 125.45` on 10
logical CPUs. Later Aozora and x402 timings remained diagnostics under heavy
contention. The 2026-10-16 table remains the last comparable set.

Reproducibility outputs are under
`/Users/junkawasaki/github/workspaces/codex/itonami-app-plane-selfhost-bench-20261017-admission-r2/`
and
`/Users/junkawasaki/github/workspaces/codex/itonami-app-plane-selfhost-bench-20261017-current-ref/`.
The checked-in runners now record load before and after measurement and set an
explicit 30-minute KEXE budget so a normal benchmark loop is not cut short by
the loader's default alarm.

### Current-source contended diagnostics

These two runs use the current record-input cores and updated native drivers.
The JavaScript reference allocates the corresponding input record each
decision. Both runs completed their workload checksums, but the 1-minute host
load was more than four times the 10 logical CPU count. Do not compare these
ratios with the 2026-10-16 table or treat them as qualified speed results.

| Decision core | AMU native | JavaScript reference | Native / JS | Load qualification |
|---|---:|---:|---:|---|
| Aozora Biscuit authorization | 130.63 ns/decision | 78.62 ns/decision | 1.661× | Contended; 46.60–46.60 |
| x402 transfer preflight | 180.08 ns/decision | 66.17 ns/decision | 2.721× | Contended; 45.57–49.77 |

Both runs used 1,000,000 decisions, seven alternating rounds, one warmup per
path, native wall time minus the empty-loader median, and the adopted AMU
selfhost seed. Source and image hashes plus raw output are retained in
`/Users/junkawasaki/github/workspaces/codex/itonami-app-plane-product-selfhost-20261017/`.

### Reproducible eight-domain suite

`bench/products/benchmark_amu_selfhost.mjs` now covers Murakumo, Aozora,
Oppai, Isekai, Babiniku, x402, Shinshi, and Awai Network. Each workload uses
the decision cases from its native Kotoba driver and checks every JavaScript
reference result against that case's expected result before accepting the
checksum. Record-input allocation is included for the Aozora and x402 JS
references; the other six interfaces pass scalar values.

`bench/products/run_amu_selfhost_suite.py` builds the eight drivers and empty
loader controls with the adopted AMU selfhost seed, saves compiler/source/image
hashes and raw per-domain output, and emits `results.csv`. It refuses to begin
when the 1-minute host load exceeds the logical CPU count unless
`--allow-contended` is supplied; the per-domain output still labels such runs as
contended diagnostics.

Invocation on this workspace:

```sh
python3 bench/products/run_amu_selfhost_suite.py \
  --amu-root /Users/junkawasaki/github/kotoba-lang/amu \
  --output /Users/junkawasaki/github/workspaces/codex/itonami-app-plane-selfhost-suite-<unique-id>
```

The suite preflight first read 213.99 one-minute load on a 10-logical-CPU
machine and refused before creating output or compiling. A later run completed
all eight current source rows with `--allow-contended`; those results are
diagnostics only. The earlier 2026-10-16 table remains the last comparable
timing set.

A follow-up preflight in the prior work session observed load averages of
231.17 / 230.09 / 244.99 on the same 10-logical-CPU host. It added no timing
sample and confirms that a comparable run needs a quiet host.

### Latest eight-domain AMU selfhost run — contended diagnostic

The complete suite ran 1,000,000 decisions per sample with seven alternating
rounds and one warmup per path. All native and JavaScript checksums passed. The
source hashes in the linked CSV match the indexed Kotoba sources, including the
current Aozora, x402, and Shinshi revisions. The Shinshi core accepts integer
micro-luma units (`floor(mean-luma * 1,000,000)`) and compares them to
8,000,000, preserving the strict 8.0 threshold while compiling on AMU's
`aarch64-macos` selfhost target.

The host had 10 logical CPUs. Initial load averages were 84.20 / 56.05 / 49.37;
per-domain 1-minute load ranged from 84.10 to 101.45 and ended at 90.15. Every
row is a contended diagnostic and must not be used to rank implementations or
claim a performance win. This is a decision-core microbenchmark, not full app,
chain, or network latency.

| Domain | AMU selfhost native | JavaScript reference | Native / JS | Status |
|---|---:|---:|---:|---|
| Murakumo | 23.08 ns/decision | 228.17 ns/decision | 0.10× | Contended diagnostic |
| Aozora | 127.95 ns/decision | 32.93 ns/decision | 3.89× | Contended diagnostic |
| Oppai | 19.21 ns/decision | 20.74 ns/decision | 0.93× | Contended diagnostic |
| Isekai | 7.80 ns/decision | 16.31 ns/decision | 0.48× | Contended diagnostic |
| Babiniku | 22.19 ns/decision | 19.86 ns/decision | 1.12× | Contended diagnostic |
| x402 | 168.29 ns/decision | 27.87 ns/decision | 6.04× | Contended diagnostic |
| Shinshi | 16.98 ns/decision | 11.78 ns/decision | 1.44× | Contended diagnostic |
| Awai Network | 19.07 ns/decision | 13.19 ns/decision | 1.45× | Contended diagnostic |

Raw rows with source, compiler, and native-image hashes are in
[`awai-kotoba-amu-selfhost-suite-2026-10-17-contended.csv`](awai-kotoba-amu-selfhost-suite-2026-10-17-contended.csv).
Per-domain logs and native build reports are retained at
`/Users/junkawasaki/github/workspaces/codex/itonami-app-plane-amu-selfhost-suite-20261017-r3/`.
The earlier Aozora/x402 diagnostic run is superseded by these rows.

## Deployment qualification

The native selfhost target currently qualifies only `aarch64-macos`. It does
not produce a browser or Worker artifact. A direct selfhost-seed compile probe
for `wasm32-browser` was refused with `E2 unsupported target (only
aarch64-macos)`. Product WASM wrappers therefore remain separate bootstrap
builds; no product app build, signed Awai release, production chain profile,
public gateway, or domain deployment is qualified by these numbers. The Awai
production chain profile is still uncreated, so the measurements do not
authorize a public cutover.
