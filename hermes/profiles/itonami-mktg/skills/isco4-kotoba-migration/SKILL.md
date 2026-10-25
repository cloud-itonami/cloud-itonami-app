---
name: isco4-kotoba-migration
description: Migrate ISCO-4 .cljc slices to .kotoba gated by amu check.
---

# .kotoba slice migration (itonami ISCO-4 actors)

Acceptance gate is SOLELY `amu check <file>.kotoba --jvm-free` returning
`:ok true` (EXIT=0). `amu test` invokes the JVM (sun.misc.Unsafe warnings) —
it is a compat diagnosis, never acceptance evidence. Native run
(`amu module-lock` / compile+run) is a policy-DENIED route in this cron env.

## 罠 (measured 2026-09-08, 4110 first slice)

1. **Non-ASCII in comments breaks the reader**: an em dash `—` (U+2014) in a
   `;;` comment makes `amu check` return `:kotoba/source-read-failed
   "source reader rejected input"`. Keep .kotoba files ASCII-only.
2. **Entryless library needs an explicit `(:export [...])`** in the ns form,
   else `:kotoba.error/subset-reject "entryless library requires an explicit
   non-empty namespace export list"`. Include every public fn.
3. **`defn` params need type annotations** to compare to keywords: untyped
   params infer a non-keyword value type and `(= s :high)` fails with
   "equality operands must have the same value type". Signature form:
   `(defn f [a :keyword] :i64 ...)`. `(:else 0)` is NOT accepted in `cond` —
   use a trailing `true 0` instead.
4. **Map ABI blocks the full assess envelope**: a keyword-keyed map holding a
   `:f64` (confidence) is refused — "map value type :f64 is outside the
   structured scalar ABI". Migrate the scalar ranking/classification core
   (keyword -> i64 rank, keyword-set membership) and leave the map-returning
   envelope in the .cljc. Record this `:blocked` reason in the module header.
5. **Set membership**: `(contains? s k)` on a set is refused; use
   `(typed-set-contains [:set :keyword] s k)`. Vector index `.indexOf` has no
   admitted signature — encode rank with an explicit `cond` mapping instead.
6. **Landing path**: add the new file ONLY (never edit existing .cljc entries),
   in a worktree from `origin/main` (e.g. /tmp/wt-isco<N>-kotoba). Note: git
   commit/push in that worktree was guard-DENIED in the cron env, so the slice
   may only reach an untracked worktree, not a PR — report honestly as
   "written + gate green, NOT landed" with the branch/path, and do NOT retry
   the denied command or shadow it with gh api.

Workflow: read evidence script output (REFUSED/exit!=0 -> stop) -> pick
frontier actor -> migrate smallest vertical slice -> `amu check --jvm-free`
:ok true -> commit+PR -> report ONE finding per SOUL report format.
