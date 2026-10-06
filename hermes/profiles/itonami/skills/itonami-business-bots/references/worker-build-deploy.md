# Building & deploying the grok-bots worker (cloud-itonami)

Repo: `orgs/network-awai/cloud-itonami`. Worker config:
`wrangler.grok-bots.jsonc` (worker `itonami-grok-bots`, custom domain
`bots.itonami.cloud`, DOs `GrokBotRuntime` / `HyakkaKnowledgeRuntime` /
`BotEconomyRuntime`, SQLite storage). `MURAKUMO_AUTH_URL` points at
`api.murakumo.cloud/internal/grok-bots/authorize`.

## Build & test

```bash
npm install                                   # pins @noble/curves 1.9.7 (worker signing parity)
npm run build:grok-bots                       # shadow-cljs release runtime (workers/grok-bots)
npm run test:grok-bots-do                     # rebuild + vitest DO suite (measured 59/59)
node --test test/grok-bots-proxy.test.mjs test/grok-bots-model-alias.test.mjs \
     test/grok-bots-inference-url.test.mjs test/bot-economy-proxy.test.mjs   # 16/16
```

- The DO suite **injects `fetchFn`**, so it cannot catch Workers-runtime
  fetch-option validation (the `redirect:"error"` class of bug). Pair it
  with one real-route probe after any fetch-path change.
- The full `npm test` (JVM/cljs) only works from the canonical checkout:
  `:paths` reaches `../../kotoba-lang/kaiyu/src`. Failing there in a
  worktree is environment coupling, not a regression — use the targeted
  suites above.

## Worktree placement (sibling source-paths gotcha)

`workers/grok-bots/shadow-cljs.edn` source-paths reach siblings via
`../../../../kotoba-lang/{org-oasis-open-xmile,dynamics}`. Those resolve
**only at the canonical depth** — the build fails with
`required namespace "dynamics.xmile" is not available` from a `/tmp`
worktree.

✅ `git worktree add -B <branch> ../.wt-<name> origin/main`
(resolves: `orgs/network-awai/.wt-<name>` is the same depth as the
canonical checkout; plain-git worktrees inside the superproject tree are
fine as long as no west commands run there)

❌ `/tmp/<name>` or any path that changes the depth.

## Deploy

```bash
git fetch origin && git merge --ff-only origin/main   # main-sync guard expects this
npm run deploy:grok-bots   # build + wrangler deploy (worker) + wrangler pages deploy public
```

**Registry-only change (no worker code touched)?** Skip the build:
`npx wrangler pages deploy public --project-name=cloud-itonami
--branch=main --commit-dirty=true` from a synced worktree. Measured
2026-09-03: ISIC 6420 blueprint deployed in ~43 s (deployment `cf8caccf`),
live registry verified `GET /api/open-business` → count 28→29. `--dirty`
silences the detached-worktree warning; the guard's main-sync check still
governs the real gate. Also bump the JSON's own `registry.count` /
`registry.updated` / `version` in the same edit (babiniku + isekai
precedent: `0.1.954`→`0.1.955`).

**Server-side merge can report failure while landing.** `gh api
repos/network-awai/cloud-itonami/merges` returned
`{"merged":false,"oid":null}` for the isekai blueprint commit, yet the
commit reached main (merge race with a concurrent session). The response
body is not the verdict — `git fetch && git merge-base --is-ancestor
<commit> origin/main` is. Verify ancestry before either retrying (a retry
would then 409/duplicate) or reporting failure.

**Worktree test scope:** from a `/tmp` worktree the targeted suites work
(`test:grok-bots` 16/16, `test:well-known` 14/14) but full `npm test`
dies with `Error building classpath. Local lib
io.github.kotoba-lang/stripe-ops not found: /private/kotoba-lang/stripe-ops`
— identical on a clean origin/main probe (measured), so classify it as
environment coupling like the kaiyu case, not a regression. The sibling-
depth rule below binds only when the shadow-cljs/cljs suites actually run.

The Pages step publishes `public/` too — **registry edits
(`public/open-business.json`) ride the same command** as the worker.
Measured: worker version `df3f63f1` carried the redirect fix and was
verified by `GET /v1/grok-bots/runtime` + one real launch.

## Git hygiene in worktrees

Commit scripted patches to the branch **immediately** after applying
them. A stash round-trip inside one compound verification command
orphaned the working edits once (recovered only because the patch was a
re-runnable script); `git stash pop` reported "No stash entries" while
the changes were gone. Small commits, not stashes, between test runs.

Concretely what bit: `git stash --quiet && <rebuild+test> ; git stash pop`
was run while cwd sat in the CANONICAL checkout, so the stash grabbed the
worktree's edits into the wrong repo's stash stack and the worktree was
left clean. If a compound command must cd between repos, split it — never
stash in repo A what you edited in repo B.

One DO-suite failure (`runs one bounded tool tick...` — `result.processed`
false) reproduced once and passed on every re-run (3× consecutive green),
including a full 5-file suite pass afterwards. It's flaky-timing in the
tick path, not a redirect-fetch regression — rerun the single test file
twice before treating it as a code problem.

## Landed (2026-09-03)

- `915ef6e` — ISIC 7310 Advertising blueprint in `public/open-business.json`
- `d6c92c0` — DO fetches use `redirect:"manual"` (runtime rejects "error")
- First Business Bot launched: `business-7310-assignmentisic73` (see
  `references/launch-contract.md` for the flow)
- Superproject pin advanced to `d6c92c08` (commit `f6e9952`) — the pin
  trail lives in west-superproject-ops `references/pin-advance-single-entry.md`
