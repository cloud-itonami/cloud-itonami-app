# JVM-exit run path

The shipped server and MCP no longer start with `clojure -M:server` or
`clojure -M:mcp`. Compile is amu `--jvm-free`. Production
`io.github.kotoba-lang/kotoba` stays `:git/tag v0.6.29` — this document does
not move that pin and does not claim a v0.7.2 emit.

Compiler freeze is `compiler-pin.edn`:

- kotoba-lang `c9dd99f5958e9e51ba9976bc0c66f7c44feb65d2` (PR #511, last green)
- not `4adda169` (PR #513, main red)
- amu emit frontend `b1fdaad20d290032c9fdfad7f768763d6facf144` (last amu commit at or before that kotoba-lang SHA; not HEAD)

## Compile (this is emit)

```bash
AMU=<path-to-amu> nbb --classpath bin bin/compile-amu
```

With Java/Clojure removed from PATH:

```bash
AMU=<path-to-amu> PATH="$(scripts/path-without-jvm)" nbb --classpath bin bin/compile-amu
```

Artifacts (named for kotoba-clj emit review):

| Artifact | Command |
|---|---|
| `target/amu/server_main.wasm` | `amu compile <abs>/server_main.kotoba --target wasm32 --jvm-free --output target/amu/server_main.wasm` |
| `target/amu/mcp_main.wasm` | `amu compile <abs>/mcp_main.kotoba --target wasm32 --jvm-free --output target/amu/mcp_main.wasm` |
| `target/amu/server_main.kexe` | `amu compile <abs>/server_main.kotoba --target aarch64-macos --jvm-free --output target/amu/server_main.kexe` |
| `target/amu/mcp_main.kexe` | `amu compile <abs>/mcp_main.kotoba --target aarch64-macos --jvm-free --output target/amu/mcp_main.kexe` |

Success is file bytes plus `.provenance.edn`. `:command/planned` is not emit.
KEXE is sealed, not a Mach-O app binary. `kotoba run` / kototama-native loads
a signed kexe; this repository does not ship kexe as `CloudItonami`.

Public-language `kotoba compile` admits `--target wasm|web` only. Native is
amu, not `kotoba compile --target native`.

## Run (no java / clojure child)

```bash
nbb --classpath bin bin/cloud-itonami-server
nbb --classpath bin bin/itonami-mcp
```

`scripts/verify-no-java` starts those processes with `scripts/path-without-jvm`
and fails if the pid tree contains `java`, `clojure`, or `clj`.

`nbb scripts/jvm-exit-report.cljs` is a source-graph heuristic, not that proof.

## Honest leftovers

These still use the JVM. This PR does not kill them by inventing FFI, JNI,
or a second KIR-EDN writer.

- `deps.edn` aliases `:cli`, `:gen`, `:repository`, `:ao-messenger`, `:test`, `:lint`, `:build`
- `:server` and `:mcp` aliases remain in `deps.edn` as leftover JVM entry
  points and are **not** the run path
- `:gen` (`clojure -M:test:gen`) is the JVM KIR-EDN writer. Amu has no
  KIR-EDN emit. `bin/gen_kir_amu.cljs` proves `:kir-sha256` and does not
  write `resources/`
- Production oracle stays KIR (`kotoba-kir` interpreter) for
  `resources/cloud/itonami/app/oracle/*.kir.edn` (ADR-0065)
- Record-typed decision cores stay interpreter-only (ADR-0067)
- `server.clj` / `mcp.clj` / Passkey / store / web page / connectors
- `bin/itonami` still `spawnSync`s `clojure -M:cli` (`:cli` leftover)
- `scripts/build-macos-release` / `scripts/build-windows-release` still
  build an uberjar (`clojure -T:build`) — leftover packaging
- `cloud.itonami.app.server-process` still knows how to spawn
  `clojure -M:server` from leftover CLI code

## Host capabilities

Listen and MCP stdio are an nbb host adapter (Node `http` / stdin). Catalog
names `http/accept` and `http/reply` as `:friendly-qualified`. They are not
wired as guest effects in these entries; the host listens. That is a
kotoba-lang HOLD, not a kotoba-clj FFI.

## HTTP client after this port

This PR does not tell an HttpClient-deletion story. Repo search for
`java.net.http.HttpClient` is not the live offender. The live offender was
`clojure -M:mcp` / `:server` and `bin/itonami-mcp` `spawnSync clojure`.
`fleet_call` is not published on the nbb MCP host.

## Overlay

`~/.cloud-itonami/data/config.edn` is not read or written here. Overlay
already `max-output-tokens` 400 and murakumo `:enabled?` false. This change
is JVM removal, not the token cap (PR 269 stays separate).
