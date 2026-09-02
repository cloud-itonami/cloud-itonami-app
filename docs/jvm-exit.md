# JVM-exit run path

The shipped server and MCP do not start with `clojure -M:server` or
`clojure -M:mcp`. Those aliases, and `:cli`, are **gone** from
`deps.edn` — not stubbed, not exit-2. `bin/itonami` no longer
`spawnSync`s `clojure -M:cli`. A bare `-M:server` / `-M:mcp` / `-M:cli`
is an unknown alias.

Compile is the public launcher `bin/kotoba compile --target wasm --json`.
Success is the wasm file on disk **and then**
`{:kotoba.cli/ok? true :kotoba.cli/code :compile/emitted}`. amu
`{:ok true :target …}` alone is not that envelope. `:command/planned` is
not emit.

Production `io.github.kotoba-lang/kotoba` stays `:git/tag v0.6.29` — this
document does not move that pin and does not claim a v0.7.2 emit.

Compiler freeze is `compiler-pin.edn`:

- kotoba-lang `bfe618b3a3844d5563a943d63bdefcdc535af98c` (merge #514, green run 33598929287)
- not `c9dd99f5` (leave it; do not keep using it as the follow-up compiler)
- not `4adda169` (PR #513; do not vendor as the pin)
- not HEAD past `bfe618b3`
- amu emit frontend `8435eafb7dada2d3a85cee2c278ca6d38deb7588` (last amu commit at or before that kotoba-lang SHA; not a floating HEAD)

## Compile (this is emit)

Public launcher (the envelope kotoba-clj reviews):

```bash
AMU=<path-to-amu> bin/kotoba compile src/cloud/itonami/app/server_main.kotoba --target wasm --json
AMU=<path-to-amu> bin/kotoba compile src/cloud/itonami/app/mcp_main.kotoba --target wasm --json
```

Amu canary with provenance (CI `--jvm-free`):

```bash
AMU=<path-to-amu> nbb --classpath bin bin/compile-amu
AMU=<path-to-amu> PATH="$(scripts/path-without-jvm)" nbb --classpath bin bin/compile-amu
```

Artifacts (gitignored under `target/`):

| Artifact | Command |
|---|---|
| `target/amu/server_main.wasm` | `bin/kotoba compile …/server_main.kotoba --target wasm` |
| `target/amu/mcp_main.wasm` | `bin/kotoba compile …/mcp_main.kotoba --target wasm` |
| `target/amu/server_main.kexe` | `amu compile … --target aarch64-macos --jvm-free` |
| `target/amu/mcp_main.kexe` | `amu compile … --target aarch64-macos --jvm-free` |

Success for the public launcher is file bytes **and** `:compile/emitted`.
Success for the amu canary is file bytes plus `.provenance.edn`.
`:command/planned` is not emit. KEXE is sealed, not a Mach-O app binary.
`amu verify` of aarch64-macos KEXE on Linux is HOLD (`unable to start the
private compiler runtime`). Do not fake `:ok`.

Public-language `kotoba compile` admits `--target wasm|web` only. Native is
amu, not `kotoba compile --target native`.

## Run (guest actually runs)

```bash
nbb --classpath bin bin/cloud-itonami-server
nbb --classpath bin bin/itonami-mcp
```

The nbb process owns the socket / stdio. It **loads** `target/amu/*.wasm`
through amu's typed ABI host (`instantiateKotoba`) and **calls**
`health-route?` / `protocol-ok?` / `main`. Duplicating those predicates in
ClojureScript is not the guest running. Guest `main` is the listen-port
token `1338` (server) or stdio token `1` (MCP). A zero main is an unused
guest.

`scripts/verify-no-java` starts those processes with
`scripts/path-without-jvm` and fails if the pid tree contains `java`,
`clojure`, or `clj`, or if `/health` is not `guest-wasm` with
`guest-main` 1338.

## Honest leftovers

PR 270 started as a host adapter + compile canary. This follow-up is the
start of guest-run for the two entries. Rank 1 #2 is not started by
kotoba-clj; this app PR is the start. The leftover JVM surface is not
gone.

- `:server` `:mcp` `:cli` aliases are **deleted** from `deps.edn`. Not
  a refuse stub. Guest still does not serve HTTP/MCP: nbb owns the
  socket; `:http/accept` / `:http/reply` stay kotoba-lang HOLD.
- `:gen` `:repository` `:ao-messenger` `:test` `:lint` `:build` still use
  the JVM. Default CI is `amu-jvm-free-emit` (java/clojure off PATH).
  `clojure -M:test` remains as leftover
  (`.github/workflows/leftover-jvm-tests.yml`, workflow_dispatch only) —
  not the required check, and not gone by renaming.
- `bin/kotoba` `:compile/emitted` is this app's compile adapter. It is
  not the production kotoba v0.6.29 binary. Production pin stays
  `:git/tag v0.6.29`.
- `:gen` (`clojure -M:test:gen`) is the JVM KIR-EDN writer. Amu has no
  KIR-EDN emit. `bin/gen_kir_amu.cljs` proves `:kir-sha256` and does not
  write `resources/`
- Production oracle stays KIR (`kotoba-kir` interpreter) for
  `resources/cloud/itonami/app/oracle/*.kir.edn` (ADR-0065)
- Record-typed decision cores stay interpreter-only (ADR-0067)
- `server.clj` / `mcp.clj` / `cli.clj` / Passkey / store / web page /
  connectors remain leftover source
- `scripts/build-macos-release` / `scripts/build-windows-release` still
  build an uberjar (`clojure -T:build`) — leftover packaging
- `cloud.itonami.app.server-process` still knows how to spawn leftover
  `clojure -M:server` from leftover CLI code

## Host capabilities (kotoba-lang HOLD)

Listen and MCP stdio are an nbb host adapter (Node `http` / stdin).
Catalog entries at kotoba-lang@bfe618b3:

- `lang/capability-catalog.edn` `:http/accept` — `:compiler-wire-id` 17,
  `:source-status` `:friendly-qualified`
- `lang/capability-catalog.edn` `:http/reply` — `:compiler-wire-id` 18,
  `:source-status` `:friendly-qualified`

http-ingress remains host-listen / guest-poll; `:native-aot` is pending
(ADR-0038). Do not invent HttpClient FFI, JNI, or a C `.so`.

## HTTP client after this port

This PR does not tell an HttpClient-deletion story. Repo search for
`java.net.http.HttpClient` is not the live offender. The live offender
was `clojure -M:mcp` / `:server` and `bin/itonami-mcp` `spawnSync clojure`.
`fleet_call` is not published on the nbb MCP host.

## Overlay

`~/.cloud-itonami/data/config.edn` is not read or written here. Overlay
already `max-output-tokens` 400 and murakumo `:enabled?` false. This
change is the JVM run-path / guest-run follow-up, not the token cap
(PR 269 stays separate).
