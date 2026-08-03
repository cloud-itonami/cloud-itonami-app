# ADR-0012: Email is a session proof, not an identity root

Status: accepted

Date: 2026-08-03

## Context

Cloud Itonami originally had one browser entrance: WebAuthn. That made the
User's P-256 public key both the stable `did:key` identity root and the proof
used to open an ordinary application session. A returning User also needs an
email entrance, but treating possession of an inbox as a replacement signing
key would erase the distinction the authority and e-signature designs rely on.

Email addresses already have three meanings in the model: the managed address,
an optional contact address, and an address reported by a delegated OAuth
connection. Only the first two belong to the local User record. An OAuth profile
is a connection after login and is not a login authority.

## Decision

Email login is available only to an active User who has already enrolled a
Passkey. It is an alternate session proof, not registration or recovery of the
identity root.

Starting authentication always returns the same accepted response. For one
unambiguous matching User, the server creates a random 256-bit token, stores
only its SHA-256 digest, and sends a link through a deployment-owned delivery
adapter. The link carries the token in its URL fragment so an HTTP server does
not receive it in an initial GET. It expires after ten minutes, is consumed
once, supersedes an older outstanding link, and is rate-limited to one live
delivery per User per minute. Unknown, ambiguous, inactive, and unrooted Users
receive the same public response and no delivery.

Consuming the token creates a session with `:kind :email` and
`:issued-via :email-magic-link`. `kotoba-lang/authentication` owns the common
challenge lifecycle and produces an `:email` factor; its decision records
`:single-factor` assurance on the session. Session authorization reads those
proofs explicitly. It does not infer assurance from the User merely having a
Passkey; otherwise any weaker session for a Passkey-owning User would silently
become a Passkey session.

The final `may this session use the app?` decision is evaluated by
`kotoba-lang/authorization` with a default-deny rule bundle. Passkey, email, and
local agent sessions each have a separate allow rule and evidence context;
unknown session kinds do not acquire authority by falling through a `case`.

The delivery adapter accepts only HTTPS, except for a loopback HTTP test
adapter. Its bearer credential is read from a named environment variable and
is never stored in configuration or `state.edn`. The adapter receives only the
destination, link, expiry, and template identifier—no User, Membership, Tenant,
or session record.

## Shared-library boundary

The application depends on the common `kotoba-lang` contracts rather than
defining a second authentication vocabulary:

- `identity` owns User and directory identity concepts.
- `authentication` owns factor, request, decision, assurance level, and the
  provider-neutral email challenge lifecycle. Both email and Passkey ceremonies
  are converted to those common factors and decisions.
- `authorization` evaluates the default-deny session policy.
- `oauth` owns delegated provider connection protocols; an OAuth profile is not
  silently promoted to a login factor.

The application still owns mechanisms that depend on its runtime and domain:
the Yubico WebAuthn ceremony adapter, token generation/digest comparison, HTTPS
mail delivery, cookie and CSRF handling, local persistence, active Membership
selection, and operation-bound authority approvals. Those are adapters and
domain policy around the common contracts, not alternate authentication or
authorization models.

## Authority boundary

An email session may use the ordinary workspace as that User. It is a human
session, so it may prepare or review a governed proposal. It cannot finish a
payment, e-signature, or outward-authority approval by itself. Those routes
remain bound to fresh WebAuthn challenges and verify the authenticator response
against the immutable operation. Email possession is not promoted to signing
authority.

## Consequences

- First registration and invited-User enrollment remain Passkey ceremonies.
- Losing all Passkeys is not solved by email login; recovery requires a
  separate identity-root rotation decision.
- Deployments must provide the HTTPS delivery adapter before the email form is
  shown. The shipped default remains disabled.
- Duplicate local contact addresses fail closed. Existing ambiguous data gets
  the generic accepted response but no login mail.
- Delivery failures are recorded as events while the public response stays
  indistinguishable from an unknown address.
