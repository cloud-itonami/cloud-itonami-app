# Business Bot launch contract (measured 2026-09-03)

Source of truth: `orgs/network-awai/cloud-itonami/workers/grok-bots/worker_entry.js`
(`businessLaunchPlan` :420, `walletLaunchMessage` :463, `verifyWalletLaunchSignature`
:492, `launchBusiness` :522). Line numbers drift; the behavior below was
measured live.

## Endpoint & auth

- `POST https://itonami.cloud/api/v1/grok-bots/businesses/launch`
  (Pages proxy) or `POST https://bots.itonami.cloud/v1/grok-bots/businesses/launch`
  (direct) — both measured 201.
- Header: `authorization: Bearer <credential>`. The DO forwards it to
  `https://api.murakumo.cloud/internal/grok-bots/authorize`; 204 = pass.
  **Credential classes, measured 2026-09-03 evening:**
  - `mro_...` operator Biscuit — **the only working path now.** Minted by the
    OWNER at `auth.murakumo.cloud` (passkey, human-only) via
    `POST /v1/murakumo/operator-token` `{"actions":["grok-bots"]}`; TTL 900 s;
    requires the holder DID in `MURAKUMO_OPERATOR_DIDS` (kotobase-authn secret).
    The DO forwards it and the murakumo edge verifies it via
    `/rpc/verify-operator` (ADR-2608291200/1300).
  - Shared bearer `MURAKUMO_SERVICE_TOKEN` — historical. The kagi copy
    (`MURAKUMO_SERVICE_TOKEN_LOCAL_MURAKUMO` v3) and operator copy
    (`~/.gftd/murakumo-service-token`) hold a stale value and 401 against live
    (ADR-2608291300: secret lost from the vault; only the Worker holds truth).
    Do not burn session time re-testing it — go straight to the mro_ ask.
  - Wrong/absent credential → 401 `authentication_error` from the DO, or
    `valid CACAO or delegated service credential required` when the request is
    seen pre-forward.

## Request body

| field | constraint (enforced, throws otherwise) |
|---|---|
| `assignment_id` | `^[A-Za-z0-9._:-]{8,120}$` |
| `business_id` | must equal a blueprint `id` in the DO's live registry fetch |
| `wallet_address` | `0x` + 40 hex (lowercased before check) |
| `credit_limit_micros` | safe integer 1000..50000 |
| `wallet_signature` | `0x` + 130 hex = 64-byte compact r||s + recovery byte (27+v) |

The blueprint's `repo` must match `^https://github.com/<org>/<repo>$`
(`validBusinessRepo`). The business code used in URLs is the blueprint's
`isicRev5` (sanitized).

## IDs derived from the input

- `bot_id` = `business-{code}-{assignment_id lowercased, [a-z0-9] only, 16 chars}` sliced to 64
  (e.g. `assignment-isic7310-20260903-01` → `business-7310-assignmentisic73`)
- `position_id` = `bot-position:{bot_id}:1`
- `item_url` = `https://itonami.cloud/api/open-business/{code}`

## Signing (worker version: @noble/curves 1.9.7)

```js
import { secp256k1 } from "@noble/curves/secp256k1";
import { keccak_256 } from "@noble/hashes/sha3";

// walletLaunchMessage — 8 lines, joined with \n. Byte-exact or verification fails.
const msg = [
  "Itonami Cloud business Bot authorization",
  "Origin: https://itonami.cloud",
  "Chain ID: 8453",
  `Assignment ID: ${plan.assignment_id}`,
  `Business ID: ${plan.business.id}`,
  `Wallet: ${plan.wallet_address}`,
  `Credit limit (micro-USDC): ${plan.credit_limit_micros}`,
  "Action: Link this Base account to a simulation-only, non-transferable Bot lending position.",
].join("\n");

const body = new TextEncoder().encode(msg);
const prefix = new TextEncoder().encode(`\x19Ethereum Signed Message:\n${body.length}`);
const bytes = new Uint8Array(prefix.length + body.length);
bytes.set(prefix); bytes.set(body, prefix.length);

const sig = secp256k1.sign(keccak_256(bytes), priv);
const wallet_signature = `0x${sig.toCompactHex()}${(sig.recovery + 27).toString(16)}`;
```

Address derivation (same for key generation):
uncompressed pubkey (65B) → `keccak_256(pub.slice(1))` → last 20 bytes.

⚠ **noble v2 (workspace-root node_modules) is incompatible for this:**
subpaths need `.js` (`@noble/curves/secp256k1.js`), `sign()` returns a
plain 64-byte Uint8Array, `toCompactHex()`/`recovery` don't exist, and
`format: "recovered"` puts the recid at byte[0]. Signing with v2 produced
a signature the worker rejected (`signer does not match wallet` →
"wallet signature does not prove the linked Base account"). Run the
script from `orgs/network-awai/cloud-itonami/` so v1.9.7 resolves.

Worker-side verify path (for debugging mismatches):
`Signature.fromCompact(b[0..64]).addRecoveryBit(rec).recoverPublicKey(keccak_256(bytes)).toRawBytes(false)`
— the method expects the ALREADY-keccak'd digest, no extra prehash.

## Intake dry-run gate (inside launchBusiness)

The DO POSTs `{title: "<name> Bot assignment", note: "assignment=<id>; Base wallet proof verified"}`
to `https://itonami.cloud/api/open-business/{code}/intakes` and refuses to
continue unless the response JSON has `ok:true, dryRun:true, committed:false,
recorded:true` (`"honest dry-run intake was not observed"`). This is a
deliberate honesty gate — the launch never commits a real intake.

## 201 response shape (abridged, measured)

```json
{"object":"itonami.business-bot-assignment",
 "assignment_id":"...", "business":{"id","name","repo","offer","item_url"},
 "web3":{"network":"base","chain_id":8453,"linked_account":"0x…","execution_authority":"short-lived-cacao"},
 "intake":{"dry_run":true,"committed":false,"recorded":true,"action":"intakes"},
 "position":{"position_id","bot_id","kind":"unique-loan-position","transferable":false,
             "credit_limit_micros":10000,"per_call_cap_micros":1000,"daily_cap_micros":5000,
             "allowed_services":["murakumo","kotobase"],"business":{...},"status":"active","created":true},
 "bot":{"configured":true,"status":"running","model":"murakumo-main","interval_ms":3600000,
        "budget_tokens_remaining":4096,"queue":{"pending":2},"next_alarm":...},
 "job":{...}}
```

The bot auto-configures: goal (4-sentence bounded-step contract), model
`murakumo-main`, 1h interval, max_output 128, budget 4096 tokens, tools
`http_get` + `x402_purchase`, allowed host `itonami.cloud` only — and the
first job ("Read {item_url}, then produce the first bounded operating
checkpoint...") is queued before start.

## Which noble copy resolves — run the signing script from the repo, not /tmp

Node resolves `@noble/curves` by walking up from the SCRIPT's directory. A
script under `/tmp` picks up the workspace-root `node_modules` (noble **v2**)
even when cwd is the cloud-itonami repo. Copy the signing helper into the
repo (e.g. `.build-launch-sig-tmp.mjs`) and run `node .build-launch-sig-tmp.mjs`
from `orgs/network-awai/cloud-itonami/` so v1.9.7 resolves, then delete it.

Self-check before POSTing (cheap, catches v2/v1 mixups and message drift):
rebuild the plan + `walletLaunchMessage` in a second script from the same
kagi key and compare the signature byte-for-byte with the request body, or
replicate `verifyWalletLaunchSignature` locally and confirm it recovers
`plan.wallet_address`. A v2-signed body fails the worker with
`wallet signature does not prove the linked Base account`.

## Post-launch: budget refill + resume (measured 2026-09-03, isekai 6420; updated 2026-09-04, 7310)

A new business bot ships with `budget_tokens: 4096`, which is spent by ~3 ticks
(each checkpoint commits ~1,700 tokens with one `http_get`). The DO then holds
itself: `governor/held (reason: budget-exhausted)` — this is the FIRST hold and
it is normal, not a failure. Refill + resume:

1. `POST /v1/grok-bots/bots` (body includes `bot_id`) = configure. On an
   existing bot it rewrites the config row and appends `bot/reconfigured`
   (201). **⚠ Measured 2026-09-04 (7310): configure is a FULL-ROW REWRITE.**
   Any field you omit falls back to the admission default — omitting
   `allowed_tools` wiped the bot's tool grants and the next tick held with
   `tool-not-granted` (govern-proposal checks `(:allowed-tools config)` in
   `grok_bot_runtime.cljc:129`). Always pass the complete field set:
   `goal, model?, interval_ms, max_output_tokens, budget_tokens,
   allowed_tools, allowed_hosts, start:true`.
2. **Admission limits (measured 2026-09-04, drifted from the src comment):**
   `budget_tokens` is admitted in **262144..1048576** (400
   `budget_tokens is outside the admitted range (min 262144, max 1048576)`
   otherwise — the old 2×max_output / 4096-era bounds no longer apply).
   `goal` is required (400 `goal is required`); `interval` 60_000..86_400_000
   ms; `max_output` 16..512. Configure does NOT clear a `held` status on its
   own — pass `start: true` or `POST .../start` after.
3. **`http-host-not-granted` holds**: the governor rejects any tool call whose
   URL host is not in `allowed_hosts` (admitted-https-url in gro_bot_runtime.cljc).
   A model that proposes URLs beyond `itonami.cloud` gets held every tick. Fix
   by reconfiguring with a goal that explicitly bounds http_get to the granted
   host ("never propose any other host") — the goal text is the only lever.
4. The launch job can sit `held` forever (idempotent re-POST returns it
   unchanged; `stop()` cancels pending/leased but NOT held). Enqueue a fresh,
   well-scoped job with a new `x-idempotency-key` instead of fighting the old one.
5. Events endpoint shape: `GET .../events?limit=N` → `{object:"list", data:[...]}` —
   the events are under **`data`**, not `events`. `limit=8` was measured to
   change the shape vs no param; parse `data` either way.
6. Budget top-up = spend-capacity creation → owner-scoped per the money rules.
   Measured consent: owner said "do it" for a 32768-token refill on 2026-09-03.

## Model pin: the alias is the only lever (measured 2026-09-03)

`CHECKPOINT_ID = /^(qwen|gemma|llama|mistral|deepseek|phi)/` in
`grok_bot_runtime.js` migrates any concrete checkpoint id back to
`FLEET_MODEL_ALIAS` ("murakumo-main") **on read** — a configure POST with
`model: "qwen3.8-27b-throughput-b70"` returns `murakumo-main` and logs
`bot/model-migrated`. So per-bot model choice via configure is impossible
by design (ADR-2607173100: fleet swaps models by one KV alias PUT).
Consequences:

- The alias (`GET /infer/models/murakumo-main` → `alias-for`) is a
  single point of health for EVERY bot. Its target must skip the gad
  short-circuit (cloud-murakumo-api PR #206 added `murakumo-main` to
  `DEDICATED_HOSTED_MODELS`; alias repointed to the b70 slot) or all bots
  tick into raw 1101 failures simultaneously.
- When a bot's ticks all fail with `Unexpected token 'e', "error code:
  1101"`, do not reconfigure the bot — probe the alias target and the
  inference origin first; fix upstream or repoint the alias.

## Measured error catalog

| status / message | cause |
|---|---|
| 400 `registry reference is invalid` | blueprint absent from the LIVE registry fetch (repo existence is not enough) |
| 400 `Invalid redirect value, must be one of "follow" or "manual"` | a DO fetch used `redirect:"error"` — fixed in `d6c92c0`; if it reappears, a fetch regressed |
| 400 `honest dry-run intake was not observed` | intake endpoint didn't return the dry-run shape |
| 400 `wallet signature does not prove the linked Base account` | signature/address mismatch — usually noble v2 vs v1.9.7, or a byte-different message |
| 400 `credit must be 0.001-0.05 USDC` | credit_limit_micros outside 1000..50000 |
| 400 `assignment_id is invalid` | regex failure |
| 401 `authentication_error` / `valid CACAO or delegated service credential required` | credential fails the authorize round-trip — with the shared bearer dead (2026-09-03 evening), the fix is an owner-minted `mro_` operator Biscuit, not a token retry |
| 401 `invalid or insufficient Murakumo operator credential for <action>` | a `mro_` token reached authn verify and was refused (bad signature / expired / wrong scope / not allowlisted). This is the mro_ path working — the fix is a fresh mint (or add the DID to `MURAKUMO_OPERATOR_DIDS`), never a bearer retry |

Errors surface as `400 {"error":{"type":"runtime_error","message":…}}`
from the worker's catch-all.

## Discriminating the two 401s on the authorize route (measured 2026-09-03 evening)

After the mro_ accept went live (merge `38ff4c1`, worker `5e110f43` on
cloud-murakumo-api), the authorize route has TWO distinct refusal bodies —
use them to tell which gate spoke before spending time on a fix:

| body | gate | fix |
|---|---|---|
| `invalid service bearer` | pre-authn bearer comparison | mro_ path not reached: credential class wrong, or (pre-`38ff4c1`) the mro_ accept was not deployed. A fresh `mro_` token here means the edge is old |
| `invalid or insufficient Murakumo operator credential for grok-bots` | authn `verify-operator` | the mro_ path IS live; the token itself failed — re-mint (900 s TTL) or check the DID allowlist |

## Signature binding: credit_limit and assignment_id are inside the signed message

`walletLaunchMessage` embeds assignment_id, business_id, wallet address AND
credit_limit — changing any of them invalidates the signature. If the launch
credit or assignment id changes, re-sign from the kagi key (`ITONAMI_8121_BOT_WALLET_KEY`
for samu) with the noble 1.9.7 recipe above. Measured working sequence for
samu (8121): kagi key generated → repo-placed helper signed
`assignment-isic8121-20260903-01` / 1000 micro-USDC → helper deleted → launch
POST held only on the owner mro_ mint. Keep the helper out of git (dot-file
scratch, delete after signing).
