# ADR-0032: Desktop OAuth is public and sessions are revocable

**Status:** accepted — 2026-08-10

## Context

ADR-0031 made sign-in plural, but its provider readiness check required both a
client ID and client secret. That is a valid confidential deployment and an
invalid distribution contract: anyone can extract a secret shipped in a DMG
or Windows ZIP. Sessions also had only an issue path. A User could neither see
which devices remained logged in nor revoke one, and the UI had no sign-out or
safe unlink operation.

## Decision

Google and Microsoft installed-app registrations may be configured as public
clients with a public client ID and no secret. Their authorization-code flow
keeps S256 PKCE, unpredictable state, the exact loopback redirect URI, and a
single-use ten-minute transaction. Token exchange omits `client_secret` only
for those explicitly declared public clients. GitHub is excluded from that
set: its browser exchange still requires the OAuth app secret; the app does not
pretend that PKCE alone changes that provider contract.

Every signed-in User can list their own live sessions and revoke any one of
them. The response exposes method, provider, timestamps, and whether it is the
current session, never token digests or CSRF material. Sign-out revokes the
server record and expires the HttpOnly cookie. An opaque session ID owned by a
different User is answered as not found.

Linked Email and SSO identities can be removed explicitly. The operation is
CSRF- and session-protected and refuses to remove the last usable login method;
an enrolled Passkey counts as an independent root. Authentication callback
failures append a secret-free audit event, SSO start is rate limited, and
expired/used SSO transactions are pruned before another flow begins.

## Consequences

- DMG and Windows ZIP builds can share Google and Microsoft client IDs without
  embedding a reusable secret.
- A confidential deployment may continue to use credential-store references.
- GitHub public distribution still needs either a hosted broker or an explicit
  device-flow design; the existing confidential flow remains available.
- Account deletion and recovery-code rotation are separate lifecycle changes;
  neither is smuggled into logout or unlink.
