# ADR-0031: Sign-in is plural and step-up remains explicit

**Status:** superseded by ADR-0083 — 2026-08-30

## Context

Passkey-only onboarding made the strongest available factor the entrance to
every ordinary action. The app already had Email magic-link authentication and
Google, Microsoft, and GitHub OAuth clients, but Email could not create a User
and OAuth grants were only service connections. Treating those connector
grants as login would also ask for mailbox, file, or repository access merely
to identify a person.

## Decision

The app presents, in one sign-in/sign-up surface, the methods **this deployment
has configured** out of Passkey, Email magic link, Google, Microsoft, and
GitHub — and says which those are. Plural is the design, not a promise every
install can keep: the Consequences below already record that a tenant-neutral
install sends no Email and enables no provider, so a surface that named all five
unconditionally described a deployment other than the one in front of the
person. The copy is derived from `auth-methods` at render time, and a method
that cannot start is absent rather than shown disabled. A deployment may allow
a verified Email address or a provider subject to create an active personal
User. Authentication OAuth uses a separate minimal scope set, PKCE S256,
single-use state, nonce where supported, and a ten-minute transaction;
connector scopes and tokens remain separate.

As of 2026-08-28, the product and `itonami` profiles configure no provider SSO:
Web3 identity begins with Passkey/WebAuthn. The SSO transaction implementation
remains available only as an explicit compatibility deployment option; it is
not rendered as a dormant or disabled entrance.

The stable login key is `[provider, provider-subject]`. It can belong to one
local User only. Email equality is display and recovery context, never an
account-merge proof: when an unbound provider returns an Email already held by
a User, the flow stops. The person signs in by an existing method and explicitly
links the provider. That same authenticated link flow is used to add multiple
SSO identities to one User.

Passkey registration is two steps — `/api/identity/register` creates the
account and `navigator.credentials.create` enrols the credential — so a
cancelled system prompt is a reachable, persistent state: a User exists, no
Passkey does. That state is named on the sign-in surface on every load,
together with whatever other entrances the deployment has, because the account
it created has already changed what the surface offers.

Ordinary authenticated sessions may enter the workspace. Operations that
already carry higher authority—payment completion, document signature,
federation, and outward approval—keep their dedicated WebAuthn ceremony and do
not infer Passkey assurance from an Email or SSO session. A User created without
a Passkey still receives a DID at creation (hosted `did:` subject, or a local
Ed25519 `did:key` — ADR-0064). Enrolling a Passkey binds an authenticator to
that DID; it is not the route that invents identity. Step-up for payment,
esign, approval, and `may-act?` remains the Passkey ceremony.

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
- On a default install the surface says the entrance is Passkey alone. Measured
  2026-08-12: it instead promised Passkey, Email and SSO beside a hidden Email
  card and three disabled SSO buttons explained only by a `title` tooltip.
- A browser without WebAuthn is a state of the surface, not an error of the
  click: the Passkey controls are disabled with the reason on the screen.
- `test/browser/signin_gate.cljs` holds this in a real browser. It reaches the
  interrupted state the way a person does — register, then no authenticator to
  finish with — and refuses to run against a store holding a finished
  registration.
- An interrupted ceremony still needs a human at the system prompt. Nothing here
  clears a pending User; the resume ceremony remains the route, and it is the
  only one when no other method is configured.
