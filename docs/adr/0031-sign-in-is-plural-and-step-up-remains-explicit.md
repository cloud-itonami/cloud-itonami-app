# ADR-0031: Sign-in is plural and step-up remains explicit

**Status:** accepted — 2026-08-10

## Context

Passkey-only onboarding made the strongest available factor the entrance to
every ordinary action. The app already had Email magic-link authentication and
Google, Microsoft, and GitHub OAuth clients, but Email could not create a User
and OAuth grants were only service connections. Treating those connector
grants as login would also ask for mailbox, file, or repository access merely
to identify a person.

## Decision

The app presents Passkey, Email magic link, Google, Microsoft, and GitHub in one
sign-in/sign-up surface. A deployment may allow a verified Email address or a
provider subject to create an active personal User. Authentication OAuth uses a
separate minimal scope set, PKCE S256, single-use state, nonce where supported,
and a ten-minute transaction; connector scopes and tokens remain separate.

The stable login key is `[provider, provider-subject]`. It can belong to one
local User only. Email equality is display and recovery context, never an
account-merge proof: when an unbound provider returns an Email already held by
a User, the flow stops. The person signs in by an existing method and explicitly
links the provider. That same authenticated link flow is used to add multiple
SSO identities to one User.

Ordinary authenticated sessions may enter the workspace. Operations that
already carry higher authority—payment completion, document signature,
federation, and outward approval—keep their dedicated WebAuthn ceremony and do
not infer Passkey assurance from an Email or SSO session. A User created without
a Passkey receives no invented DID; enrolling a Passkey remains the route to
Passkey-backed identity and step-up.

## Consequences

- Windows and macOS use the same loopback UI and authentication endpoints.
- Sign-in and delegated connections share the already-registered
  `/api/oauth/{provider}/callback`; single-use state selects the transaction
  partition after return, while scopes and stored grants remain separate.
- A tenant-neutral install does not send Email or permit sign-up by default.
- A deployment can enable several providers without coupling sign-in to access
  to the provider's mail, files, or repositories.
- Losing all linked methods remains an operator recovery concern; silently
  merging on Email is not accepted as recovery.
