# ADR-0030: Desktop updates are signed, staged, and rolled back

**Status:** accepted — 2026-08-10

## Context

The first DMG was downloadable but had no update authority. GitHub Release
transport alone cannot answer whether bytes should replace a local application:
a compromised release session, truncated upload, wrong-platform asset, or
stale package all look like ordinary downloads. Windows had no application
artifact at all, while the generic shell repository's MSIX vocabulary had
never been executed on Windows.

## Decision

One release publishes `update-manifest.edn` beside its packages. Its Ed25519
signature covers the version, source commit, release URL, and every platform
asset URL, size, and SHA-256. The private key is the targeted kagi item
`cloud-itonami-app-updater-ed25519` in `personal`; only the X.509-encoded public
key is shipped. GitHub transports the signed statement but cannot create one.

The app checks the configured release channel in the background and from
Settings. A background check automatically stages a newer package when
`:auto-download?` is enabled; manual downloading requires a same-origin POST.
Either path stages only an asset whose URL remains under this repository's Release origin,
whose size is below the fixed limit, and whose signed size and digest match.
There is no silent replacement while the process owns its store.

After staging, the platform launcher applies the staged ZIP when the update
window closes; an already-staged update is also applied before the next server
start. It keeps the prior install, starts the new one,
waits for the loopback `/health` contract, and rolls back if health does not
arrive. A same-user process can already edit the local store and install, so the
supply-chain boundary is the signed remote manifest and package digest, not a
second local secret.

The launcher-time helper rechecks the staged ZIP SHA-256, exact semantic
version, forward-only version transition, and `cloud.itonami.app` bundle ID.
It atomically claims `updates/pending` before opening the replacement bundle,
so the replacement launcher cannot start a second helper for the same package
while the first helper is still checking health. A failed claim is archived
with a timestamp instead of being retried in a launch loop.
For compatibility with helpers shipped before this claim protocol, a
replacement launcher also starts normally when the pending version already
equals its own bundle version; the older helper can then observe health and
archive its pending package.
Settings exposes one action: check, verify, and stage; once ready the same
action closes the window so the launcher can replace, health-check, and reopen
the app. A two-button check/download surface looked like an updater but left
the final apply step outside the button.

After health succeeds the helper writes a bounded `last-applied.edn` receipt
with the before/after version and application time. Settings may display that
receipt, but it is never update authority and malformed receipt data is simply
omitted; discovery still depends only on the signed release manifest.

Windows is initially a real portable x64 package rather than a claimed MSIX.
Its small Go launcher starts the same Java server JAR and opens Edge application
mode or the default browser. It is cross-built and structurally checked on
macOS; Windows execution and Authenticode remain explicit qualification gaps.
Publishing the ZIP is not a claim that the unexecuted shell MSIX scaffold has
become qualified.

## Consequences

- A release without a valid signed manifest is invisible to automatic update.
- A valid manifest cannot redirect packages to an arbitrary host.
- Configuration or user choice gates download; restart gates mutation; health gates promotion.
- The previous installation is retained for rollback rather than deleted.
- macOS Developer ID/notarization and Windows Authenticode are independent
  trust layers still required for a non-preview channel.
