# ADR-0033: Stable desktop releases require native publisher trust

**Status:** accepted — 2026-08-10

## Context

The signed update manifest authenticates bytes chosen by Cloud Itonami, but it
does not make those bytes a platform-trusted application. Preview 0.4.1 was
ad-hoc signed on macOS and unsigned on Windows. Gatekeeper therefore required a
manual first-open exception, while Windows could not identify a publisher.
Checking only that an update has *some* code signature would also allow a
different certificate to become the updater's authority.

## Decision

`preview` remains an explicit development channel. `stable` is a fail-closed
release profile and produces nothing unless both platform identities exist.

On macOS, every Mach-O and the app bundle are timestamped with a `Developer ID
Application` identity under Team ID `3A5CBTEBFP`. The app is submitted to Apple,
stapled, and assessed before packaging; the signed DMG is independently
submitted and stapled. The updater requires a valid Developer ID authority,
the same Team ID, and a passing Gatekeeper assessment before replacement.

On Windows, the portable launcher is SHA-256 Authenticode-signed and RFC 3161
timestamped before ZIP creation. The build downloads jsign 7.5 only from its
upstream release and verifies SHA-256
`602a51c3545a6dc4fb99bd2ea7152b26d1345916d0c93ddfbd5936cb735af91c`.
The PKCS#12 password is read from a mode-600 file, never placed directly in the
process arguments. The installed package carries its publisher certificate's
SHA-256. Before applying an update, Windows must report a valid Authenticode
chain and the new signer fingerprint must equal the current installation's.

## Consequences

- A stable release cannot silently fall back to ad-hoc or unsigned artifacts.
- Ed25519 update authority and OS publisher trust remain independent gates.
- Developer ID issuance is an Apple Account Holder operation; release tooling
  consumes the identity but does not revoke or replace unrelated certificates.
- Public Windows distribution remains blocked until a publicly trusted code
  signing identity has completed its provider's identity-verification process.
