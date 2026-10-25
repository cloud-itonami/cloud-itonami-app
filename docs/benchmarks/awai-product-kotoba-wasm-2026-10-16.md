# Awai product Kotoba WASM diagnostic

Eight product decision cores compiled to `wasm32-browser-kotoba-v1` with AMU
checkout `c3b1ef6a01ca5f0dd2357c89b92f6aef65b0479e`, `--jvm-free`, and a bounded
fuel budget of 10,000,000 per guest. The modules have no imports and together
occupy 4,605 bytes, including x402's additional source-specific amount-gate
export. The source wrappers are connected to their local CLJS/JavaScript
decision functions, including Shinshi's shared `black-frame?` helper.
Shinshi's offline SCI tools still use the host fallback and do not form a
public service callsite. Application builds and Awai app release bindings
remain unverified.

## Results

The initial runs used one million alternating allow/refuse decisions, seven
alternating rounds, and one warmup. The updated x402 full-core run used ten
ordered outcomes. Runners converted host Number arguments to WASM i64 BigInts
and created a fresh guest every 10,000 calls. Host load average for the initial
shared run was 28.83 / 31.79 / 32.09; x402 remeasurement load was 32.41 / 30.85
/ 33.84 on a 10-logical-CPU machine.

| Product core | WASM bytes | WASM ns/decision | JS ns/decision | WASM / JS | Source callsite |
|---|---:|---:|---:|---:|---|
| Murakumo bid admission | 531 | 173.42 | 36.24 | 4.79× | `eligible-bid?` source calls wrapper |
| Aozora Biscuit authorization | 970 | 247.13 | 45.93 | 5.38× | PDS authorization source calls wrapper |
| Babiniku proposal governor | 549 | 184.92 | 37.73 | 4.90× | `review-turn` imports wrapper |
| x402 transfer preflight | 812 | 782.69 | 94.15 | 8.31× | `transfer-verdict` calls four-argument amount gate |
| Shinshi black-frame threshold | 401 | 145.74 | 33.75 | 4.32× | Shared CLJS helper calls wrapper; offline SCI path uses host fallback |
| Oppai challenge window | 503 | 153.38 | 34.38 | 4.46× | `challenge-valid?` imports wrapper for bounded timestamps |
| Isekai publish-tier admission | 393 | 104.19 | 26.91 | 3.87× | `account-tier.mjs` imports wrapper |
| Awai Network chat admission | 446 | 152.81 | 33.29 | 4.59× | `sanitize-chat` imports wrapper |

These core-only portable WASM calls are slower than their small direct-JS
references; current measurements range from 3.9× to 8.3× slower and 104–783 ns
per decision. This is an in-process microbenchmark of normalized inputs, not
a service or full-request benchmark. It excludes signature checks, parsing,
storage, networking, app resolution, and chain consensus.

## Source-adapter measurements

Additional runs measured the Babiniku, x402, and Oppai source adapters after
their callsites were connected. These include host normalization relevant to
the gate, the Number-to-i64 BigInt boundary, and 10,000-call guest rotation.
They do not include the rest of each request path.

| Source adapter | WASM ns/decision | Direct JavaScript ns/decision | WASM / JS | Cases |
|---|---:|---:|---:|---|
| Babiniku `review-turn` decision | 168.34 | 10.36 | 16.24× | Eight mixed decisions; blocked-term search excluded |
| x402 transfer amount gate | 229.19 | 12.10 | 18.94× | Four amount-limit inputs after host validation |
| Oppai challenge window | 109.92 | 22.73 | 4.84× | Eight inclusive-expiry and fallback boundary inputs |

The i64 BigInt boundary makes these small gates slower than their direct JS
counterparts. Their absolute measured cost is 0.11–0.23 microseconds per call.
Raw outputs and harnesses live in each feature worktree's `bench/` directory.

## Compiler and runtime boundary

The portable artifact was produced with AMU's JVM-free `wasm32-browser`
target. It is separate from the adopted AMU native selfhost benchmark output:
the native microbenchmarks use `aarch64-macos`, while the portable WASM target
uses the compiler's WASM backend. The native and WASM numbers must not be
reported as one target's performance.

The first default-fuel WASM probe trapped after 512 calls. The measured
artifacts therefore pin a finite 10,000,000 fuel budget and their host rotates
instances after 10,000 decisions. This constrains the WASM runtime lifetime;
it does not establish production host limits.

Exact outputs and the local harness are preserved at
`/Users/junkawasaki/github/workspaces/codex/awai-wasm-probe-20261016/awai_product_cores_wasm_result_2026-10-16.txt` and
`/Users/junkawasaki/github/workspaces/codex/awai-wasm-probe-20261016/bench_awai_product_cores.mjs`.
