# ADR-0041: hosted auth.itonami.cloud is the sign-in entrance

**Status:** accepted — 2026-08-14

## Context

Sign-in on the installed app did not progress. The screen led with local
Passkey registration that mints a `did:key` on this device. The Clerk-shaped
path — Authorization Code + PKCE against `auth.itonami.cloud` (ADR-0035) —
lived inside `#registered-auth`, which ships `hidden` until script runs.

Separately, opening the bind address `http://127.0.0.1:1338` made every
cookie-borne POST fail with `invalid-origin`. Measured 2026-08-14 on the live
process: `Origin: http://127.0.0.1:1338` → 403; `Origin: http://localhost:1338`
→ 200 and `https://auth.itonami.cloud/authorize` with
`redirect_uri=http://localhost:1338/api/auth/itonami/callback`.

ADR-0035 named the callback as `http://127.0.0.1:1338/...`. That string is
wrong for this app. WebAuthn RP ID cannot be an IP. `cloud-itonami/app-auth`
`oauth-client` therefore pins `localhost`, not `127.0.0.1`. The two halves
disagreed, and a tab on the IP could not complete hosted sign-in even if it
reached `/authorize`.

`authn.kotobase.net` does not resolve. The hosted identity Worker that does
is `auth.itonami.cloud`. First-time passkeys are enrolled at
`https://itonami.cloud/signin/` — that Worker does not enrol, by construction
(no KEK).

## Decision

1. The sign-in entrance is `auth.itonami.cloud`. The HTML shows that card
   first, unhidden. New accounts go to `itonami.cloud/signin/`. Local Passkey
   registration remains as a this-device path, not the first-time story.
2. `GET /` on Host `127.0.0.1` (or `::1`) redirects to `localhost` on the
   same port when `:public-origin` is localhost. `/health` and other API
   paths do not redirect, so bind-address probes keep working.
3. ADR-0035's loopback redirect is `http://localhost:1338/api/auth/itonami/callback`.

This does not invent an identity service. Authority stays
`did:web:kotobase.net:*`. The desktop app is a public native client of the
existing Worker.

## Consequences

- A person who bookmarks `127.0.0.1:1338` lands on `localhost:1338` before
  they click sign-in, so Origin, cookie jar, WebAuthn RP ID and OAuth
  callback agree.
- Local Passkey DID minting is still implemented. It is no longer the card
  a first-time person meets.
- Isolated servers on other ports still derive `redirect_uri` from origin.
  Production `app-auth` accepts only the 1338 localhost callback; a 1348
  isolate cannot complete live OAuth, which was already true.
