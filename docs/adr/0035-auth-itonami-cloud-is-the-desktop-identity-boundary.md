# ADR-0035: auth.itonami.cloud is the desktop identity boundary

Status: accepted — 2026-08-12

## Decision

The installed Cloud Itonami app authenticates through
`https://auth.itonami.cloud` using Authorization Code + PKCE (`S256`). The app
is a fixed public client named `cloud-itonami-app-native`; it has no distributed
client secret. Its exact loopback redirect is
`http://127.0.0.1:1338/api/auth/itonami/callback`.

The central service returns a one-minute, one-use code. A successful exchange
returns an opaque five-minute access token which is used once at `/userinfo`
and never persisted by the desktop app. The local server validates issuer,
client id, scope, DID subject, phishing-resistant ACR, and WebAuthn AMR before
minting its own revocable local session.

An unbound central DID may create a User only on an empty installation. On an
installation that already has Users, it must be linked from an authenticated
local session. Email equality is never an account-linking signal.

The existing local Passkey, Email, and provider SSO entrances remain available
as compatibility and recovery paths. Operations whose policy requires a fresh
local WebAuthn ceremony continue to require that step-up; central authentication
does not silently satisfy an operation-specific approval challenge.

## Consequences

- The browser session at `auth.itonami.cloud` is a host-only `__Host-` cookie.
  No parent-domain authentication cookie is shared with the desktop app.
- Authorization codes and access tokens are stored centrally only by SHA-256
  digest and have short TTLs. A code is consumed before PKCE verification, so a
  wrong verifier spends it too.
- The old `https://app.itonami.cloud/auth` route remains a compatibility alias
  while clients move to the dedicated origin.
- The app persists only the central DID binding and its own session metadata;
  it does not persist the central access token.
