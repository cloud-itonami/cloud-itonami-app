# ADR-0083: application sign-in is Passkey-only

**Status:** accepted — 2026-08-30

## Context

The visible gate had already become Passkey-first, but the server still exposed
Email magic-link and provider SSO session-start routes. An older deployment
profile could therefore reintroduce a second login root even though the screen
did not present one. The provider callback also selected between sign-in and a
delegated service connection from transaction state.

## Decision

Cloud Itonami issues application sessions only from Passkey ceremonies:

- hosted Passkey through `auth.itonami.cloud`;
- a device-local Passkey assertion or registration;
- invitation enrolment completed by creating a Passkey.

The server does not expose Email authentication or provider SSO start/finish
routes. `/api/oauth/{provider}/callback` is exclusively a delegated service
connection callback and cannot set an application session cookie. Public auth
state advertises neither Email nor SSO even if an older resident configuration
still names them. Legacy implementation and stored records may remain for data
migration; they are not reachable login roots.

## Consequences

- The sign-in document presents one decision and every successful path ends in
  WebAuthn.
- OAuth for Gmail, Drive, Calendar, GitHub, and similar services remains
  available after Passkey sign-in and does not become identity authentication.
- Old Email magic links and provider SSO bookmarks fail closed with 404 and do
  not mint sessions.
- Recovery that does not possess a valid Passkey is an explicit operator or
  invitation workflow, not silent account merging by Email.
