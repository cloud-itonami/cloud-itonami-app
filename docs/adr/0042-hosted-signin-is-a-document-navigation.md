# ADR-0042: hosted sign-in is a document navigation to auth.itonami.cloud

**Status:** accepted — 2026-08-14

## Context

ADR-0041 made hosted `auth.itonami.cloud` the entrance card and redirected
`GET /` from `127.0.0.1` to `localhost`. That still left a class of mismatch:
sign-in itself was `POST /api/auth/itonami/start` gated by `require-origin!`.
A tab on the bind address never reached the hosted page — Origin
`http://127.0.0.1:1338` is 403 before `location.assign`. Telling a person to
open `localhost` instead is the operational workaround, not a structural one.
They asked to sign in **on** `auth.itonami.cloud`, and for the
127.0.0.1 / localhost disagreement to be impossible.

Ceremony already lives on the hosted Worker. `app-auth` accepts exactly one
`redirect_uri`: `http://localhost:1338/api/auth/itonami/callback`. WebAuthn RP
ID cannot be an IP. Accepting both names as OAuth callbacks would split the
cookie jar. The session must still land on `localhost`. What must not depend
on the tab's Host is **starting** the ceremony.

## Decision

1. The browser sign-in control is a link to `GET /api/auth/itonami/start`.
   That route does not require Origin. It 303s to
   `https://auth.itonami.cloud/authorize` with
   `redirect_uri` taken from `:server :public-origin` (localhost), never from
   the request Host. After hosted sign-in the callback mints the cookie on
   localhost. A person who clicked from `127.0.0.1` still signs in on the
   hosted page and returns on the agreed name.
2. An already-authenticated GET start 303s to `/`. Linking stays
   `POST /api/auth/itonami/start` with Origin + CSRF. Native webview
   (`?surface=native`) still intercepts the link and POSTs with
   `:handoff?` — it cannot do WebAuthn.
3. Do not register `127.0.0.1` as a second OAuth redirect_uri.

## Consequences

- Opening the bind address and clicking sign-in opens `auth.itonami.cloud`.
  The person does not have to choose `localhost` first.
- `GET /` still redirects the document to localhost (ADR-0041). Sign-in no
  longer depends on that redirect having already happened.
- POST start from Origin `127.0.0.1` remains 403. That is the native/fetch
  path, not the hosted ceremony.
- Isolated ports still cannot complete live OAuth against production
  `app-auth`, which pins 1338.
