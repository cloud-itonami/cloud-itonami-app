# Security Policy

## Reporting

Do not open a public issue for a suspected vulnerability, credential leak, or
privacy incident. Use GitHub private vulnerability reporting for this
repository.

Include affected revisions, reproduction steps, impact, and any suggested
mitigation. Do not include real access tokens, mailbox content, or Passkey
private material.

## Security boundaries

- The default server binds only to loopback.
- Cloud inference is denied unless both routing and privacy policy allow it.
- Passkey ceremonies validate challenge, RP ID, exact origin, signature,
  user verification, and signature counters on the server.
- OAuth uses PKCE S256 and single-use, expiring state.
- Runtime state, credentials, generated artifacts, and logs are excluded from
  source control.
- A `did:web` setting is not proof of domain control. Deployments must verify
  DNS/HTTPS ownership and publish the DID document before enabling it.

## Workspace human-authentication boundary (ADR-2608302125)

This project is a declared first-party human-authentication client. The
workspace root `SECURITY.md` and ADR-2608302125 are mandatory and this file may
not weaken them.

- The only permitted active human-authentication method is a WebAuthn Passkey.
  Email, password, SMS/voice, OAuth/OIDC/SAML/social/enterprise SSO, support
  decisions, operator resets, and administrator overrides must not become
  login, bootstrap, step-up, credential registration, or recovery authority.
- OAuth with PKCE is an authorization transport after Passkey authentication;
  it must not authenticate a human, mint or upgrade a human session by itself,
  or enable an upstream identity-provider fallback.
- If Passkey authentication is unavailable, fail closed. Provider secrets,
  legacy records, flags, or tenant settings must not enable a fallback.
- Recovery replaces a credential and never directly creates a session. It
  requires a one-time offline recovery secret, verifier-only storage, at least
  48 hours of server-enforced delay, and a fresh Passkey. Operators may freeze
  an account but cannot bypass the delay or grant identity.
- Closed legacy routes return 404 or 410 without ceremony, redirect, token,
  session, or credential issuance. Source, built application, and live-route
  negative tests must include plausible legacy configuration.

Policy alignment is not runtime proof. This client remains **unverified** until
its current source, built application, and live authorization path have all
been negatively verified against prohibited fallback methods.

Supported security fixes target the latest release and `main`.
