# ADR-0006: Which authenticator may approve, and what that actually proves

## Status

Accepted.

## Context

[ADR-0005](0005-payment-settlement-authority.md) put a Passkey consent gate in
front of settlement records, and its §6 states that an agent cannot approve
because "consent is a WebAuthn user-verifying assertion from an authenticator
the operator holds, and there is no assertion an agent could produce."

That sentence is true only if the authenticator really is one the operator
holds. WebAuthn does not establish that on its own:

- `authenticatorSelection.authenticatorAttachment` in a registration request is
  a **request to the client**, and the client is precisely what the gate exists
  to constrain.
- `authenticatorAttachment` in the *response* is **client-reported and
  unsigned**.
- Chrome DevTools ships a **virtual authenticator**. It reports `platform`,
  performs user verification automatically, and signs whatever it is asked to,
  programmatically, with no human present. An agent that drives a browser can
  turn it on. Its assertions verify perfectly.

So before this ADR, the honest description of ADR-0005's consent gate was: *an
agent that does not think to enable a virtual authenticator cannot approve.*
That is not a security property.

The owner chose (2026-07-30) a **Secure Enclave key requiring Touch ID** —
non-extractable, every signature behind the sensor — precisely so that the agent
cannot approve. This ADR is what makes that choice enforceable rather than
aspirational.

## Decision

### 1. Request a platform authenticator, and direct attestation

Registration now sends `authenticatorAttachment: "platform"`,
`residentKey: "required"`, `userVerification: "required"` and
`attestation: "direct"`. On macOS that resolves to Touch ID / Secure Enclave and
excludes USB keys and phones.

`direct` rather than `none` for one reason: under `none` a browser **zeroes the
AAGUID** for privacy, and the AAGUID is the only model identifier that lives
inside *signed* authenticator data. Without it the strongest grade available is
the client's own word.

**Requesting is not enforcing.** Everything above is what we ask for. What
follows is what we check.

### 2. Grade what the response actually proves

`cloud.itonami.app.credential-assurance` is a pure function over the credential
recorded at enrolment:

| level | rests on | a lying client… |
|---|---|---|
| `:unknown` | nothing recorded | — |
| `:platform-claimed` | `authenticatorAttachment` in the response | …simply says `platform`. **Unsigned.** |
| `:platform-attested` | AAGUID inside signed authenticator data, matched against published hardware-backed platform AAGUIDs | …must forge signed authData |
| `:hardware-attested` | attestation chain verified to a configured root | …must forge Apple's signature |

Only the bottom two rows are evidence. `:platform-claimed` is named *claimed* so
nobody reads it as *verified*.

The AAGUID list deliberately **excludes Windows Hello's software profile**
(`6028b017-…`), which is a genuine platform authenticator whose key is not in a
secure element. That exclusion is the whole reason the list exists rather than
trusting the `platform` label: *platform* and *hardware-backed* are different
properties, and only the second stops an agent signing.

A **zeroed AAGUID is privacy, not suspicion.** It drops a level; it is not
refused.

### 3. Enforce it in the spine, as a third gate

`authority/finish-approval!` now has three checks, and they answer three
different questions:

| check | question |
|---|---|
| `approval-match-issues` | is this assertion for *this proposal*? |
| **`credential-policy-issues`** | **what kind of authenticator signed it?** |
| the actor's Governor (at commit) | is this admissible for the licensed operator? |

The credential is looked up **from the store by id**, not read off the assertion
result — same reason the cross-domain posture is computed server-side: the
enrolment-time evidence is ours, and letting the response describe itself defeats
the point. An assertion that is genuinely for the proposal but came from an
unacceptable authenticator is a refusal (`:authority/credential-not-accepted`,
403), not an approval.

The achieved level is recorded on the proposal as `:passkey-assurance`, so a
reader can see what approved it without re-grading later.

### 4. The shipped floor is `:platform-claimed`, on purpose

`:min-assurance :platform-claimed` with `:require-user-verification? true`.

This is deliberately *not* the strongest rung, and the reason is stated rather
than hidden: whether this machine's browser discloses a real AAGUID under direct
attestation is **measurable, not guessable**. A floor the hardware cannot clear
would enrol successfully and then refuse every payment — which reads as a bug and
invites someone to delete the check.

`GET /api/passkeys/assurance` closes that loop: it reports each credential's
achieved level, its basis, and which authorities currently accept it. Register
once, read it, then raise the floor **with evidence**. Doing it in that order is
the difference between a policy and a guess.

## Consequences

### What is now true, and what is not

**True:** a cross-platform authenticator (USB key, phone) cannot approve a
payment. A credential enrolled without user verification cannot. A credential
enrolled before this change grades `:unknown` and cannot.

**Not yet true:** at the shipped floor, a browser virtual authenticator claiming
`platform` still clears the bar. Raising `:min-assurance` to
`:platform-attested` closes that — and the report exists so the owner can confirm
their real Touch ID enrolment clears it first. Stating this plainly is the point;
a reader who believes the agent is already excluded would be wrong.

**Never true here:** `:hardware-attested` requires an attestation trust source,
and none is configured. `allowUntrustedAttestation` stays `true` so enrolment is
not blocked on a root nobody installed; `isAttestationTrusted` is recorded as
`false` and graded accordingly rather than pretended away.

### Fail-closed in both directions

`at-least?` treats an unrecognised **level** as the bottom rung, *and* an
unrecognised **floor** as unsatisfiable. A misspelt floor in configuration would
otherwise silently disable the gate — the exact failure this namespace exists to
prevent. (Caught by its own test, which initially asserted the permissive
behaviour and was fixed in the code rather than in the test.)

### A synced passkey is surfaced, not hidden

An iCloud Keychain passkey is backed up by design and exists on more than this
Mac. That is not a defect, but it does mean "this device's Secure Enclave" is no
longer the claim being made, so `:backed-up?` appears in the report.

## Alternatives considered

**Trust `authenticatorAttachment` from the response.** It is unsigned. This was
the design until this ADR, and it is what the virtual authenticator walks
through.

**Refuse a zeroed AAGUID.** Would refuse legitimate enrolments in browsers that
withhold it for privacy, and would be read as "this authenticator is
suspicious", which is false.

**Require `:hardware-attested` now.** Needs Apple's WebAuthn root installed and
a real device to confirm the chain validates. Worth doing; not worth blocking
enrolment on before it has been tried once.

**Generate a software passkey and store the key locally.** Considered and
rejected by the owner in the same decision: a key an agent can read is a key an
agent can sign with, and the consent gate becomes a log entry that says a human
approved when none did.
