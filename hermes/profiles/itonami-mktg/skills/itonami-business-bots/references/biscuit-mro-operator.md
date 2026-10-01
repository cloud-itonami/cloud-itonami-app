# mro_ operator Biscuit — the credential path for grok-bots write-plane ops

Measured 2026-09-03 (ADR-2608291300, accepted; ADR-2608291200 for authn).
The shared `MURAKUMO_SERVICE_TOKEN` bearer is DEAD for fresh sessions: the
vault lost the true value long ago (kagi item
`MURAKUMO_SERVICE_TOKEN_LOCAL_MURAKUMO` v3 + operator copy
`~/.gftd/murakumo-service-token` both return 401 `invalid service bearer`
against `POST api.murakumo.cloud/internal/grok-bots/authorize` — measured
evening 2026-09-03). Only the Worker still holds the true value, which is
why live callers keep working while fresh sessions cannot.

## Why the Biscuit path is the fix (not a new shared secret)

- **Named holder**: every token carries the principal DID — audit says WHO,
  not "whoever had the string".
- **TTL 900 s**: nothing long-lived on disk to lose/rotate catastrophically.
- **Revocation measured**: removing a DID from `MURAKUMO_OPERATOR_DIDS`
  invalidates even unexpired tokens immediately (`valid:true → false`,
  ADR table row R).
- **Attenuation-safe**: `verify-operator` folds `->grant`; a future writer
  with append rights can only narrow, never widen.
- Worker holds no crypto/root key — it asks `authn` per request and records
  the verdict in an inbound-stripped header (`x-murakumo-verified-operator`).

## Mint (OWNER-ONLY — WebAuthn ceremony, agents cannot do 安全床①)

1. Passkey sign-in at `https://auth.murakumo.cloud` (human ceremony).
2. `GET https://auth.murakumo.cloud/v1/session` (with `gftd_session=`
   cookie) → `.accountDid` — this is the DID that must be allowlisted.
3. One-time (owner): `wrangler secret put MURAKUMO_OPERATOR_DIDS --name
   kotobase-authn` with that DID. **DONE 2026-09-03 evening** — the owner's
   account DID `did:web:kotobase.net:tenant:u_f342cf9f5786dd` (passkey
   username `@kotoba-f342cf9f5786dd`, sign-in at auth.murakumo.cloud) is
   now allowlisted (secret uploaded successfully). Minting is LIVE for this
   principal. To find the DID without the session: resolve the public
   account-DID doc — `GET https://kotobase.net/tenant/u_<user>/did.json`
   (identity apex is `kotobase.net`, per kotobase org_did.cljc; the
   `did:web:kotoba.cloud:tenant:*` form 404s). Account DID derivation:
   passkey username `@kotoba-<hex>` → tenant user `u_<hex>`.
4. Per session (15-min TTL): `POST /v1/murakumo/operator-token`
   `{"actions":["grok-bots"]}` with the session → `mro_…` (~722 chars).
5. Use as `authorization: Bearer mro_…` on grok-bots write routes (launch,
   start/pause/queue/configure). The DO forwards it to murakumo authorize,
   which falls through to `auth.kotobase.net/rpc/verify-operator` (checks
   signature, scope `murakumo://can/operator:grok-bots`, allowlist, expiry).

## Measured verify-table (from ADR-2608291300, all live)

| test | result |
|---|---|
| allowlist 外 DID mint | 403 `operator_not_authorized` |
| allowlist 内 mint | 201, mro_ 722 chars |
| requested actions only, sorted | `["grok-bots","models"]` |
| verify admits granted action | `valid:true` + holder DID match |
| token without the action | refuse |
| 1-char tamper | refuse |
| operator token as inference credential | refuse (A9 — never reuse across classes) |
| allowlist removal | immediate invalidation (revocation works) |

## Minting from an agent session (measured constraint, 2026-09-03)

The mint needs the owner's `gftd_session` cookie from the browser passkey
sign-in. Measured attempts:
- A curl `POST /v1/murakumo/operator-token` with no cookie → session invalid
  (mint is session-gated, not key-gated).
- The owner runs the mint themselves — a helper script at `/tmp/mint-mro.sh`
  (mint + live authorize round-trip verify, token to `/tmp/mro-token-live`
  0600, cookie prompted at runtime and never written to disk). Do NOT have
  the owner paste the cookie or the token into chat (安全床①) — the token
  grants 15 min of operator control and chat transcript is not a secret
  store. If the owner runs the script, the agent can read the token FILE
  from disk when needed for immediate use, but never echo it.
- Passkey login first-try can fail with 「パスキーの処理に失敗しました」 —
  retry from the sign-in page (「この端末にパスキーを作成」 path) worked for
  the owner on the second attempt (measured).

## Automating the mint from an agent session (measured 2026-09-03 evening)

Two automation routes were attempted when the owner said to drive it with
computer use. Neither completed the mint (the owner did not finish the
passkey ceremony inside the automated window), but the mechanics below are
measured and reusable — the missing ingredient was only the owner gesture.
Route 3 below (2026-09-04) reaches `create()` itself via raw CDP.

**Route 1 — cua-driver attaching to the owner's logged-in Chrome: refuses.**
`cua-driver call browser_prepare '{"pid":<chrome pid>}'` →
`browser_requires_setup`; adding `strategy:{kind:"existing_profile"}` +
`window_id` → `browser_consent_required` (standard permission mode requires
`--grant existing-profile` or an embedding authorization host — the CLI
cannot self-grant; `Permission denied: tool 'grant' has no reviewed risk
classification`). An isolated profile via `allow_launch:true` works but has
no owner cookies. This gate is BY DESIGN (profile authorization is separate
from MCP transport approval) — do not burn time looking for a bypass; the
driver refusing is the correct behavior. Useful discovery command:
`cua-driver describe <tool>` prints the full input_schema.

**Route 2 — browser-use (Python) headed Chrome + owner completes the
ceremony in that window. `/tmp/mro-mint-browser.py`, measured working up to
the ceremony:**
- API shapes: `from browser_use import Browser, BrowserConfig` (NOT
  BrowserSession); `browser.new_context()` → `context.get_current_page()`
  (there is no `new_page`); `BrowserConfig(headless=False, keep_alive=True)`;
  do NOT pass `--user-data-dir` via extra_browser_args (patchright demands
  `launch_persistent_context` instead — hard error).
- Flow: headed window → `page.goto(SIGNIN_URL)` → poll
  `fetch('/v1/session',{credentials:'include'})` every 3 s → when valid,
  run an IN-PAGE fetch of `POST /v1/murakumo/operator-token`
  `{actions:["grok-bots"]}` — the httpOnly `gftd_session` cookie never
  leaves the browser, so 安全床① holds even though the agent drives the
  window. Then verify via the real authorize round-trip and write the
  token to `/tmp/mro-token-live` 0600.
- Measured: window opens and the session poll correctly reports invalid
  before the ceremony. Two 8-minute windows timed out because the owner
  did not interact with the (background) window. Lesson: make the window
  foreground/prompt the owner BEFORE starting the poll, and keep the poll
  window short so the owner isn't waiting on a hidden page. If the owner
  prefers, `/tmp/mint-mro.sh` (cookie paste) remains the simplest path.
- Owner's account DID derivation without a session: passkey username
  `@kotoba-<hex>` → tenant user `u_<hex>` → public doc
  `GET https://kotobase.net/tenant/u_<hex>/did.json` (200 measured; the
  `kotoba.cloud:tenant:*` form 404s — apex is kotobase.net per
  kotobase/org_did.cljc).

**Route 3 — isolated Chrome + raw CDP (measured 2026-09-04, reaches create()):**
When cua-driver's isolated launch is refused (`no vendor-signed system
Chromium executable`) and browser-use is unavailable, launch the system
Chrome manually as an isolated profile with DevTools open and drive it via
CDP WebSocket — this reaches `navigator.credentials.create()` cleanly:

```bash
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  --user-data-dir=/tmp/samu-cft-profile2 --remote-debugging-port=9333 \
  --remote-allow-origins=* --no-first-run --no-default-browser-check \
  --window-position=200,100 --window-size=900,800 "https://auth.kotoba.cloud/"
```

- `--remote-allow-origins=*` is REQUIRED — without it every WS handshake
  403s (`Rejected an incoming WebSocket connection`). In zsh it must be
  QUOTED (`'--remote-allow-origins=*'`) or the glob eats the arg silently
  and Chrome opens with no debugging port. Re-launch against the SAME
  profile while an instance runs is a silent no-op (「既存のブラウザ
  セッションで開いています」) — kill the old one first.
- Drive via python `websocket-client` (`create_connection(...,
  suppress_origin=True)`) + `Runtime.evaluate` with `returnByValue` +
  `awaitPromise`; page WS URL from `GET /json`. In-page clicks and DOM
  reads work; the httpOnly cookie never leaves the browser (安全床①).
- **WebAuthn deadlock pitfall:** a ceremony that timed out once leaves a
  pending request in the renderer — every later `create()` fails instantly
  with `OperationError: A request is already pending` (measured; a page
  reload is NOT enough — open a NEW tab via `window.open`, that clears
  it). Probe the real error with a raw minimal `create()` + catch, not by
  re-reading the page's generic 「パスキーの処理に失敗しました」 text.

**THE HARD LIMIT (measured):** the Touch ID sheet is spawned by
`AuthenticationServicesHelper` as an AX-OPAQUE window — visible in
`cua-driver list_windows` (that is how you detect it fired) but with zero
AX elements, and on this Mac it rendered on the BUILT-IN display's
inactive Space while the owner worked on external displays. Every capture
path returned wallpaper/black: `screencapture -D <n>` per display, CDP
`Page.captureScreenshot`, cua-driver `zoom`. So the owner never saw the
prompt and the ceremony timed out. The agent cannot press Touch ID (安全床①
biometric) and cannot move the sheet (no AXWindow to frame-move). On a
single-display machine this route should complete; on this multi-display
Space-split config, the honest terminal state is: tell the owner to check
the built-in display / switch Spaces, touch, then mint from that session.
Route 3's value over Routes 1–2: it definitively reaches create() and
detects the sheet, so the ONLY missing step is the owner's gesture.

## Launch checklist step 2 in practice (measured 2026-09-03)

The bearer `MURAKUMO_SERVICE_TOKEN` operator copy + kagi item now BOTH
return 401 — the bearer path is dead for fresh sessions, so the launch
POST and post-launch write ops authenticate with `Bearer mro_…` from a
fresh mint. No retry of a 401 with the old bearer; no cross-class reuse
(mrb_ refused at verify-operator).

## ACCEPT SIDE IS LIVE (2026-09-03 evening)

The authorize-route admission (branch `agent/passkey-operator-authority`,
`caf90b7`, written 2026-08-29) was unmerged/undeployed for 5 days — the ADR's
"measured live" table covered authn mint+verify only, not the edge admit side.
Merged `bcc7ba8` → server-merge `38ff4c1` on cloud-murakumo-api main, deployed
as worker version `5e110f43-0cf6-4ab0-90f9-44bf32b721b5` (governed artifact
regenerated: injected shadow-cljs 2.28.20, gzip -9 -n, OS byte 0xff, manifest
rebuilt — `package-release.mjs` reproduced byte-for-byte). Merge-resolution
policy, artifact-refresh recipe, worktree sibling-symlink set, and the known
vitest `operator_registry_auth` case-1 debt are recorded in the SKILL.md
"ACCEPT SIDE IS LIVE" section.

Post-deploy live probes (the discriminator going forward):

| probe | response | meaning |
|---|---|---|
| garbage `mro_` (700 chars) | 401 `invalid or insufficient Murakumo operator credential for grok-bots` | mro_ path live, reached authn verify |
| wrong bearer | 401 `invalid service bearer` | old bearer path unchanged, no regression |
| valid minted `mro_` | 204 via DO → launch proceeds | owner mint + allowlisted DID |

Tests at merge: test:cljs 309/1670 green, origin-auth 23 green,
operator_passkey 8 green (node --test), kotoba 3 green. Known debt: vitest
operator_registry case 1 (models-registry) — compiled `i5` re-calls authn
instead of reading the stamped header; pre-existing on main, unrelated to
authorize.

## Agent-side rules (this is what an agent session must know)

- On sudden 401 `invalid service bearer` mid-session: a parallel session
  rotated the shared secret, OR the operator copy drifted from kagi (compare
  WITHOUT echoing, refresh copy from kagi if kagi is newer). If BOTH hold the
  old value, the bearer is simply dead — go to the mro_ ask, do not burn
  time re-testing the bearer.
- Never present an `mrb_` inference token to verify-operator, or an `mro_`
  operator token to the inference plane (A9).
- The bot itself keeps ticking through credential loss — verify life via the
  PUBLIC activity feed (no auth) before declaring a bot dead. Only the write
  plane goes dark.
- `GET /v1/grok-bots/businesses` became public (200, no auth) 2026-09-03
  evening — watch crons can read it without any credential.

## Cross-skill note: profile-gateway + browser automation lessons

The gateway-stability lessons (launchd plist as the canonical way to keep a
profile gateway alive; ad-hoc `nohup`/`execute_code` gateways die with the
kernel) and the browser-automation lessons (browser-use TUI is not scriptable
headlessly; use the `browser_use` Python API — `Browser` +
`new_context().get_current_page()`, no `BrowserSession`, no `--user-data-dir`
in extra_browser_args; cua-driver deliberately refuses attaching to the
owner's logged-in browser without a profile grant) live in the
`bot-cron-organize` skill and in the mro_ automation section above. When a
future session needs to automate a passkey/WebAuthn ceremony from an agent
session, read the mro_ automation section first: headed Chrome + owner
gesture in-window + in-page fetch keeps the cookie inside the browser
(安全床①) and measured working up to the owner's gesture.
