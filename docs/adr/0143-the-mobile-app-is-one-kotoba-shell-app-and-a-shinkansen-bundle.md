# ADR-0143: the mobile app is one kotoba-shell app and a shinkansen bundle

**Status**: accepted / **Date**: 2026-10-11
**Related**: ADR-0087 (amends), ADR-0083, ADR-2608081500 (superproject),
kotoba-lang/shinkansen `docs/SHELL-BINDING.md`

## Context

ADR-0087 put an iOS / Android app on kotoba-lang/shell: `app.mobile.kotoba.edn`,
id `cloud.itonami.app`, a web bundle the app carries (`mobile/dist`), reading the
edge. It was scaffolded for both targets, built for Android and run on an iOS
Simulator against the live edge.

It was not the only itonami mobile app. network-awai/cloud-itonami carries a
second one, on a different host and under different identities:

| file in network-awai/cloud-itonami | what it declares |
|---|---|
| `mobile/src-tauri/tauri.conf.json` | a Tauri app, `"identifier": "jp.co.gftd.itonami.mobile"` |
| `mobile/fastlane/{Appfile,Fastfile}` | store lanes for that Tauri app |
| `docs/adr/0005-itonami-mobile-app.md`, `docs/adr/0007-mobile-uiux.md` | the decisions behind it |
| `app.kotoba.edn` | `:ios/bundle-id "dev.kotoba.itonami"` (beside `:macos/bundle-id`) |

Three ids for one product on one phone. And the bundle this repository ships
was written before kotoba-lang/shinkansen named a contract for "a phone that
carries the bytes": `docs/SHELL-BINDING.md` (declared 2026-10-11), the mobile
sibling of `HOST-BINDING.md`, which lists this app as its first consumer.

## Decision

### 1. One app, one id

The owner decided that itonami's iOS and Android app is built with kotoba only,
with kotoba-shell as the native host. No Tauri, Capacitor, React Native or
Flutter host.

- **`cloud.itonami.app` is the one itonami app id** on iOS (`:ios/bundle-id`)
  and Android (`:android/application-id`). `app.mobile.kotoba.edn` in this
  repository is the single itonami mobile manifest.
- The Tauri app (`mobile/src-tauri`, `mobile/fastlane`, ADRs 0005 / 0007) and
  the `:ios/bundle-id "dev.kotoba.itonami"` line of `app.kotoba.edn` in
  network-awai/cloud-itonami are **superseded** by this manifest. They are
  retired in that repository, not here; this ADR is the record from this side.
  The `:macos/bundle-id` beside it is a desktop question and is not decided here.
- **Keychain.** The bundle id is also the keychain service the in-app bridge
  scopes items to (`kotoba.shell.native-bridge/keychain-service`). Items stored
  under `jp.co.gftd.itonami.mobile` or `dev.kotoba.itonami` are unreachable from
  `cloud.itonami.app`, by design. Nothing migrates: this app's policy grants no
  `keychain/*` today, so it has stored nothing, and a device that held a token
  in the retired app pairs again. Changing `cloud.itonami.app` later would
  orphan whatever this app stores from then on, which is why it is now fixed.

### 2. The bundle adopts SHELL-BINDING

Section numbers are SHELL-BINDING's. Each line says what it means for
`mobile/` concretely.

- **§1 publish gate.** Every document in `mobile/dist` goes through
  `shinkansen.adapter/adapt` before `app scaffold`, and the bundle carries
  `/.well-known/shinkansen/receipts.json`. The receipt set becomes the
  allowlist the browser check already approximates ("`dist/` holds only the
  app"): a file with no receipt and no asset entry fails the build. The version
  shown to a reader is the entry document's CID beside `:app/version`.
  `dds.css` and the compiled runtime stay in the bundle; nothing is fetched
  from a CDN.
- **§2 viewport.** `shinkansen.viewport/audit` passes over every shipped
  document, and a check asserts `env(safe-area-inset-top)` by name — the side
  this app's own iOS run found missing (ADR-0087 README, jp-go-dds `3950c1a`).
- **§3 theme.** `shinkansen.theme` as on the desktop web (`kotoba-theme` in
  storage, `<html data-theme>`, `theme/head-script` in `<head>`). The bundle
  declares `color-scheme: light` today; adopting the contract is what lets it
  follow the OS through `prefers-color-scheme` (ADR-0096 dark ramp).
- **§4 locale.** The bundle is Japanese only. When a second locale ships, every
  variant is built with `locale/document-variants` and carried, and the entry
  becomes a boot document that negotiates through the runtime `negotiate` that
  SHELL-BINDING §4 says the first consumer adds to `locale/runtime-fragment` —
  not a copy in this bundle.
- **§7 interaction.** `shinkansen.interaction/runtime` (and the theme / locale
  fragments) inlined once; controls use `data-action` and `shinkansen.on`, the
  contract `src/cloud/itonami/app/web.cljk` already uses on the desktop. The
  reagent mount stays the view's mount point, not a second event layer.
- **§5 reads.** Unchanged from ADR-0087: plain `fetch` to `services/app-edge`,
  `Access-Control-Allow-Origin: *` on credential-less reads, and an explicit
  two-origin allowlist (`kotoba-webbundle://app`,
  `https://appassets.androidplatform.net`) on the first route that reads a
  session.
- **§6 writes.** Only when slice 2+ of ADR-2608081500 puts a write on the edge:
  a `shinkansen.invoke` envelope POSTed to the edge's `/v1/invoke`, never with
  `:principal` or `:grant` in `:input`. The edge has no `/v1/invoke` today.
  **Passkey-gated writes are declined on mobile, by the client first**, with
  their own sentence — the pattern the コマンド pane already uses for
  `bots decide`, `bots provision` and approvals (ADR-0083, sign-in is Passkey-only). kotoba-shell's
  Passkey is macOS-only, and a phone lacking the ceremony is not a reason to
  allow the write.
- **§8 bridge.** `mobile/shell-policy.edn` stays `{:allow [] :deny ["*"]}`.
  `keychain/*` arrives only with the slice that stores a principal key, as one
  string plus its reason.

### 3. Manifest

`app.mobile.kotoba.edn` says it is the single itonami mobile manifest and names
the contract in `:shinkansen/binding`. kotoba-shell validates only the keys
each target requires (`missing-manifest-keys` in `kotoba.shell.launcher`) and
does not interpret this one, as with `:local/contracts`. It copies the whole
manifest, this key included, into the app's `Resources/kotoba-shell.edn`; the
scaffold and `app check` below were run with the key present.

### 4. Signing is deferred

The owner deferred store signing. The manifest carries no `:apple/team-id` and
no Gradle keystore, and `app package` is **expected to refuse**. That refusal
is the correct state, not a defect to work around.

## What is done, and what is not

The original 2026-10-11 change recorded the decision, manifest note and key,
and README pointer. The follow-up Amu/Kotoba migration replaces the original
Reagent/Shadow CLJS mobile bundle with an Amu guest and a small JavaScript
browser host. The mobile directory no longer has a Clojure/Shadow build or
test path.

Measured 2026-10-11 from this repository's root, with kotoba-lang/shell
`8c01e97`, whose `bin/kotoba-shell` now dispatches `app` subcommands:

| | result |
|---|---|
| `app scaffold --target ios --target android` | `ok? true`, `ready-count 2`, `web-assets {:placeholder? false :source "mobile/dist"}` |
| `app check --target ios --target android` | `code app-ready`, `ready-count 2 / 2`, no missing files |
| `npm run build` on 2026-10-11 | **not run**: `amu` was not installed on that workstation, so `mobile/dist` held only the committed `index.html` (no `js/main.js`, no `dds.css`). This historical result proves the manifest and layout only. |
| `shinkansen.viewport/audit` over `mobile/dist/index.html` (ad hoc, not committed) | first run: **`{:ok false :problems [{:id :no-xs-band}]}`**, with and without `dds.css` as `:stylesheets`. After the phone band below, against shinkansen `426ecc6`: `{:ok true :bands [480 768]}` by default, with `:host :kotoba-shell`, and with `dds.css` |
| safe-area insets in the same document | all four, including `top` |

The first audit result was a real finding: the document's only width query
was `@media (max-width:48rem)` and `dds.css` has none, so the gate refused it
although the layout is fluid. **Resolved (2026-10-11)** on the bundle side, by
the band shinkansen added for this case rather than by loosening the gate:
`mobile/scripts/gen_index.cljk` now emits `<style data-shinkansen="phone-band">`
carrying `shinkansen.viewport/phone-band-css` at shinkansen `426ecc6` —
16px form controls, 44px targets, no horizontal scroll — **copied as text**,
since this build cannot require shinkansen yet. The band's body
safe-area padding is the one rule left out: `jp-go-dds.page` already reads all
four insets (body top/bottom, `.dds-ext-container` left/right with
`max(1rem, inset)`), and the band's body left/right padding would have inset
the content twice. Regenerating also took in a pending `jp-go-dds` change to
`.dds-ext-grid` (`minmax(min(…, 100%), 1fr)`) that `--check` had been
reporting as stale. `npm run index:check` itself does not run: the script
names `scripts/gen_index.cljs` (the file is `.cljk`) and calls `kbb -Spath`,
which kbb does not have; the generator was run and `--check`ed directly with
kbb over an explicit classpath (jp-go-dds, html, css, text).

### Amu/Kotoba bundle (2026-10-17)

`mobile/src/cloud/itonami/mobile/app.kotoba` now owns the fleet, actor detail,
terminal, pairing, and information panes. `mobile/src/main.mjs` owns DOM events,
HTTPS requests, local pairing storage, and scroll behavior. The command screen
embeds a pinned normalized command-table snapshot; it issues GET requests and
the single allowlisted `bots task` message write. The host checks the HTTPS
origin and exact write template again before sending.

The build inputs are pinned in `mobile/amu.lock`, `mobile/engine.lock`,
`mobile/dds.lock`, and `mobile/commands.lock`. On 2026-10-17, `npm run build`
completed with Amu `c3b1ef6a01ca5f0dd2357c89b92f6aef65b0479e`, its engine
`95ff779a659dbb344ab87017908c0acba4300b87`, and DADS
`fb279e1c18fe86abe2ca6d815b2c3f5d0c51c347`: the guest compiled to
`js-kotoba-v1` with no effects (230,134 bytes), the host JavaScript syntax
check passed, the committed HTML matched its template, and the viewport audit
reported bands `[480, 768]` with `safe-area-inset-top`. This build does not run
a browser interaction suite or launch an iOS/Android simulator. A separate
`mobile-bundle` workflow now rebuilds this bundle from the pinned inputs.

Next, in order:

1. **Done:** replace the Clojure/Shadow bundle and generator with the pinned
   Amu/Kotoba compiler, browser host, and Node scripts; add a reproducible
   mobile bundle workflow.
2. **Done:** regenerate/check the HTML from its template and require a phone
   viewport band plus the top safe-area inset in the local viewport audit.
3. Run `adapter/adapt` in `npm run build` and write receipts into `dist`.
4. Add the theme contract and `data-action` / `shinkansen.on` runtime.
5. Add locale variants and the runtime `negotiate` when a second locale exists.
6. Add `/v1/invoke` on the edge and the client envelope with slice 2.
7. Signing remains deferred until the owner decides it.

## Consequences

There is one itonami app on a phone, and one place to change it. A contract
change in shinkansen reaches this app through SHELL-BINDING rather than through
a second host that would have to learn it separately. The bundle now uses the
pinned Amu/Kotoba guest and passes its local viewport audit, but it remains a
partial SHELL-BINDING adoption until the receipt gate, theme and interaction
runtimes land. Its maturity table should reflect that scope.
