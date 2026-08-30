# ADR-0045: the desktop sign-in ceremony matches app-auth

**Status:** accepted — 2026-08-14, refined 2026-08-28 and 2026-08-30

## Context

Three surfaces asked a person to sign in, and they did not agree.

| Surface | Repo | What they saw first |
|---|---|---|
| Assertion | `cloud-itonami/app-auth` at `auth.itonami.cloud` | 「パスキーでサインイン」 / 「パスキーを作る」 — DADS, one document |
| Enrolment | `network-awai/cloud-itonami` at `itonami.cloud/signin/` | メールアドレス → 初期パスワード → パスキー — kotoba-ui |
| Desktop gate | this app, `#signin` | 「auth.itonami.cloud でサインイン」 plus local Passkey / Email / SSO / this-device register as equal cards |

`cloud-itonami-auth` was proposed as a place to unify them. That name already exists as `app-auth`. Enrolment cannot move there: the Worker has no KEK, and that is the boundary (app-auth README, ADR-0035).

Latest kotoba addresses apps and graphs by content, not by a path name:

- L2 graph snapshot is `:kotoba.graph/cid`
- L5 app is `:kotoba.app/bundle-cid` plus `ipfs://` / `ipns://` embed-url (`kotoba.protocol.app`)
- kotoba-lang SPA views are fragments of one document (`#/name`, kami-app-nle, ADR-2608080100)

This desktop app addressed views as `#signin`. The hosted ceremony is an HTTPS Worker, not a CID-addressed bundle. Pretending the button label is a CID would be theater.

## Decision

1. **Ceremony UI authority is `app-auth`.** The unauthenticated desktop panel uses the same verbs and order: 「パスキーでサインイン」 (GET `/api/auth/itonami/start`, ADR-0042) and 「パスキーを作る」 (`https://itonami.cloud/signin/`). The hostname is not the verb.
2. **Every application entrance completes with a Passkey.** Device-local Passkey, this-device registration, and invitation enrolment live in a closed DADS accordion. Email and provider SSO expose no session-start route. An interrupted owner ceremony (`passkey-required?`) opens the recovery accordion. Organization invite stays a separate local flow — it is not hosted identity.
3. **Views are addressed as `#/name`.** Nav items are real links (`href="#/signin"`, `#/settings`, `#/chat`, …). `hashchange` applies them; `showView` writes the slash form. The legacy `#signin` form still resolves so old bookmarks and `/#bots` keep working. This is the kotoba-lang SPA fragment (kami-app-nle, ADR-2608080100), not a content hash — `kotoba.protocol.ref` refuses fragments as identity (ADR-2608145100).
4. **Do not create `cloud-itonami-auth`.** Do not enrol on `app-auth`.
5. **CID / IPNS identity of the auth and app *bundles* is not this slice.** `auth.itonami.cloud` and this desktop document remain HTTPS embed-urls (`:verifiable? false` in `kotoba.protocol.app/resolve-embed-url`). Workspace facts already go through kgraph. Publishing those two surfaces as `:kotoba.app/bundle-cid` is a later slice, on the repos that actually ship the bytes.

## Consequences

- A person who has used `auth.itonami.cloud` meets the same two controls here.
- Enrolment at `/signin/` is still kotoba-ui and email-first. Restyling that page to DADS is a change in `network-awai/cloud-itonami`, not here.
- `#/signin` and `#signin` both open the gate. New redirects from the server use the slash form. Back/forward and pasted `#/name` links switch the view.
