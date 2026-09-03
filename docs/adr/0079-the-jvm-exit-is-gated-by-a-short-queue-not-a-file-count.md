# ADR-0079: The JVM exit is gated by a short queue, not a file count

**Status:** accepted — 2026-08-27

## Context

The runtime rule (ADR-0065, ADR-2608095000, ADR-0049, and the workspace
CLAUDE.md) says new code goes on ClojureScript or Kotoba and `:clj` is a frozen
compat layer. A PreToolUse hook enforces the *new* half — it refuses a new
`.clj` outright. Nothing measured the *stock*, so "how much is left" was
answerable only by counting files by extension.

That count is wrong in both directions, and both directions cost real work
before this was understood:

- **A `.clj` with no JVM interop is a `.clj` by extension only.** Measured
  2026-08-27, 24 of 152 were in this state — the same defect
  `portable_nbb.cljs` names for tests, one level up.
- **Renaming one is not enough.** A namespace with no interop that requires a
  JVM-bound namespace is still JVM-bound, and renaming it produces a `.cljc`
  that ClojureScript cannot load: a file claiming a portability it does not
  have, which is worse than an honest `.clj`.

## Decision

**Measure the require graph, not the extensions.** `scripts/jvm-exit-report.cljs`
(nbb, no JVM) walks it and reports three sets: portable today, blocked by an
in-repo namespace *with what is blocking it*, and **unmeasured** because a
dependency lives outside this repository. The third set exists because the
script's own first version omitted it and reported four files as portable when
only two would load.

**Split every namespace into decisions and host, and put the decisions where
they have no dependencies at all.** `bin/test-portable-cljs` grants no classpath
beyond `src` and `test` — a portable judgement needing a resolved dependency
tree would not be very portable — so a namespace that requires a library cannot
run there. `host` requires two; its decisions require none, so they live in
`host-bounds`. Same for `config` → `config-policy` and `store` → `store-core`.

**A rename is finished when the namespace RUNS on ClojureScript, not when the
extension changes.** Both runners list it, or it is not done.

## What this bought, measured

| | before | after |
|---|---|---|
| `.clj` under `src/` | 152 | **150** |
| tests running on ClojureScript | 20 | **50** (212 assertions) |
| JVM tests | 1948 | **1970** (11660 assertions) |

The `.clj` count barely moved, and that is the point of this ADR: the number
that matters is not how many files are left but **which ones everything else is
waiting on**.

```
5 file(s)  cloud.itonami.app.store
3 file(s)  cloud.itonami.app.app-client
2 file(s)  cloud.itonami.app.project-repository
2 file(s)  cloud.itonami.app.funding
```

Eleven files (2,674 lines) are clean and waiting on that queue. Eleven more
(2,433 lines) cannot be judged from here at all, because they require libraries
outside this repository.

## Consequences

- **One `.clj` in a dependency pins a whole graph.** `langchain.edn-persist` was
  the last `.clj` in that library's runtime surface and it alone was why `store`
  could not be portable. It is now policy plus an injected `IStateStore`
  (kotoba-lang/langchain#20), following `kotoba.lang.fs`.
- **`kotoba.kgraph` is not fixed, and adding its new dependency does not fix
  it.** It was extracted into `kotoba-lang/kgraph`, `.cljc` with zero interop, so
  depending on that reads like the answer. Measured: with both on the classpath
  `kotoba/kgraph.clj` from the `kotoba` pin still wins, because it comes first —
  the dependency resolved, the require succeeded, and **nothing changed**. The
  real move is bumping `kotoba`, which is 320 commits behind and which around
  forty namespaces here require. `deps.edn` records this rather than leaving it
  to be rediscovered.
- **Some things are host questions and should stay host questions.**
  `config/data-dir` reads an environment and a home directory; the loopback bind
  check goes through `policy.kotoba` and the Kotoba oracle. Neither belongs in a
  namespace whose whole claim is that it needs nothing.

## The two failures worth keeping

**A split dynamic var fails open and looks correct.** Moving
`*granted-capabilities*` into `host-bounds` and leaving an alias in `host` meant
callers bound one var and `require-cap!` read the other. The capability gate
stopped denying and every call still succeeded. Both vars were defined; both
names resolved; nothing in the diff looked wrong. `host_grant_test` caught it by
binding the var a real caller binds. The fix is not a better alias:
`host-bounds/require-cap!` takes the grant set as an **argument**, so the failure
cannot be expressed.

**A suite that does not compile reports success.** Twice, `clojure -M:test`
exited 0 having run zero tests — once on `*print-fn*` (ClojureScript-only) and
once on a patch that deleted two helpers along with the function between them.
The exit code is not the signal; the absence of a `Ran N tests` line is. Check
for the line.

Both are the shape ADR-2608136000 names: a check that could not run answering
the same way as a check that ran and found nothing.
