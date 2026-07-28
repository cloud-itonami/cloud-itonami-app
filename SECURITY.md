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

Supported security fixes target the latest release and `main`.
