# x402 micropayment rails — selling-side surfaces (ADR-0096)

ADR-0096 (merged `cdb06bee`, network-isekai): x402 rides BESIDE the gem
ledger, never inside it. Per-call micropayment for fork execution / asset
fetch; gem pack / subscription stays on the ADR-0070 PSP bridge; testnet
harness first, mainnet only with real measured transactions.

## The harness is LIVE on production (2026-09-03)

isekai.network serves the seller side:

- `GET /api/x402/prices` → `{mode:"testnet-harness", priced:[…]}` — 200 with
  the honest unpriced note when `X402_PRICES_JSON` is unset.
- `GET /api/x402/price?resource=/api/fork` → **402** with the x402 **v1**
  challenge (`{x402Version:1, accepts:[…]}`) AND a `PAYMENT-REQUIRED` header
  carrying base64 of the same JSON. `x-x402-mode: testnet-harness` on every
  response.
- `POST /api/x402/verify` with `x-payment` (or body `payment.header`) →
  **shape-verify only**; `settlement` is always `null` (honest — the
  facilitator's job, not this route's).
- Source: `functions/api/x402/{prices,price,verify}.js` + shared
  `_harness-core.js` (network-isekai, merged via PRs #288/#289/#290/#291).
- Pricing registry: Pages **secret** `X402_PRICES_JSON` (JSON map keyed by
  resource path). `/api/fork` is seeded: 1000 micro-USDC, payTo = the 6420
  bot wallet, `royaltyBps: 500`. Unpriced resources 404 — never invent a
  price. Changing it: record current value → `wrangler pages secret put`
  → **redeploy (secrets bind at deploy)** → diff probe.
- Royalty pass-through (`royaltyBps`/`creator`) rides challenge `extra`
  verbatim from the caller's ledger fold — never recomputed in the route.
- EIP-712 domain is the MEASURED one: base-sepolia USDC is `USDC`/`2`
  (NOT `USD Coin` — that name belongs to mainnet `0x833589fC…`; wrong domain
  = signatures recovering the wrong address). Contract consts and the full
  protocol shape live in `orgs/network-awai/nexus-x402/src/pay/x402.cljc`
  (`eip712-domain-by-network`, `payment-requirements`, `challenge`),
  facilitator logic in `pay/facilitator.cljc` (zero-dep, zero key custody).

## Pages Functions routing — the three 1101/404 traps (all measured)

1. **One file per route.** `functions/api/x402.js` only serves
   `/api/x402`; sub-paths fall through to the SPA HTML fallback (200 with
   `<!DOCTYPE html>` — looks alive, serves nothing). Sub-routes need their
   own files: `functions/api/x402/prices.js` etc. (fork's `[cid].js` /
   `feed.js` are the precedent).
2. **The context has NO `url` field.** Handlers destructure
   `({ request, env, url })` → `url` is undefined → every request throws
   (error code **1101**). Derive it: `const url = new URL(request.url)` —
   every other function in the repo does (fork/feed.js line 1 of the
   handler).
3. **Relative imports must match the file's own directory.** A sibling
   helper is `./_harness-core.js`, not `../_harness-core.js` — one wrong
   `../` is a module-not-found at bundle time.

Local self-test without the Workers runtime: copy the route files + core
into a flat temp dir and `import()` them as REAL files (a `data:text/javascript`
import cannot resolve relative specifiers). The test shim:
`test/x402-harness.test.mjs` (17/17) — request/Response shims + env with
`X402_PRICES_JSON`.

Secrets on Pages: `npx wrangler pages secret put NAME --project-name P`
(production) — takes effect only on the NEXT `wrangler pages deploy`.
Deploy from a synced detached worktree of origin/main (`--commit-dirty=true`
silences the detached warning) — measured chain #290→deploy→probe in
~25 s.

## Deployment-to-live chain (measured end-to-end 2026-09-03 evening)

The harness reached production through three follow-up fixes after the
first merge — each one is a distinct Pages-Functions lesson:

- **#289**: `functions/api/x402.js` alone only serves `/api/x402`;
  `/api/x402/prices` fell through to the SPA HTML fallback (200 +
  `<!DOCTYPE html>` — looks alive, serves nothing). Split into
  `functions/api/x402/{prices,verify}.js` + shared `_harness-core.js`.
  Also: relative imports must match the file's own directory
  (`./_harness-core.js` not `../_harness-core.js`).
- **#290**: live probe showed **error 1101** on every route — the handler
  destructured a nonexistent `url` field from the Pages context.
  `const url = new URL(request.url)` fixed it. Lesson: a 200 from the
  SPA fallback and a 1101 from a throwing handler are BOTH silent deaths;
  probe the response BODY (JSON vs HTML) and status against the contract,
  not just "got a response".
- **#291**: `/price` also needed its own file (one route = one file).
- The Pages git-integration deploy was NOT picked up after the merges
  (stale source on the deployment list) — the reliable path was a direct
  `npx wrangler pages deploy . --project-name network-isekai --branch=main
  --commit-dirty=true` from a detached worktree of origin/main, re-deployed
  after each secret change (secrets bind at deploy).
- Price seeding: `echo '<json>' | wrangler pages secret put
  X402_PRICES_JSON --project-name network-isekai` → redeploy → `/prices`
  lists the resource → `/price?resource=…` returns the 402 challenge with
  `PAYMENT-REQUIRED` header + `x-x402-mode` header.
- **End-to-end verify (measured)**: POST `/api/x402/verify` with a
  base64-json `x-payment` header carrying a well-formed authorization →
  `{isValid:true, payer:echo, settlement:null}`. A POST body WITHOUT the
  header re-challenges 402 — the two paths are distinguishable from curl
  alone, no wallet needed to smoke-test the wire shape.
- Watch out: the watch cron prompt must say "read the **`data` array**"
  for events endpoints, and prices listing changes are diffed against a
  last-price file (both in the isekai-x402 profile jobs).

## Honesty rules (this surface)

- No settlement, no chain I/O, no key custody in the harness. `settlement:
  null` is a FEATURE; faking a receipt is the failure mode to design out.
- Never hardcode prices or recipients; unpriced = 404.
- `x-x402-mode: testnet-harness` must appear on every response so no client
  can mistake the harness for a settled rail. A watch job treats a missing
  header as impersonation.
- Mainnet is a separate ADR + real transaction, not a flag flip.

Ops profile: `~/.hermes/profiles/isekai-x402/` (SOUL carries the rules
above) with `x402-harness-watch` (23 */2 * * *) and
`x402-price-diff-daily` (11 8 * * *); ledger via export_cron.py landed on
superproject main. Bot-side purchases stay behind `x402_purchase` +
allowed_hosts and the credit bounds — flipping a host grant open is
spend-capacity creation (owner-scoped).
