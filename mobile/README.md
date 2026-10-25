# mobile — the iOS / Android surface

The web bundle a [`kotoba-lang/shell`](https://github.com/kotoba-lang/shell)
iOS or Android app carries, plus the manifest and policy that turn it into one.

## Why this exists rather than "run the app on a phone"

The desktop application is a JVM server on `localhost:1338` with a native
window pointed at it. **Neither iOS nor Android has a JVM**, so that shape does
not travel: the bundle installs and the server it fetches from does not exist.
`local-manimani` measured exactly that on an iOS Simulator — its real UI
rendered, with `TypeError: Load failed` across it (ADR-2608072000).

So the mobile app is the other shape: a **web bundle the app carries**, served
by the shell host from the app's own origin, reading over HTTPS from the edge
Worker that ADR-2608081500 moved the server surface onto.

| | desktop | mobile |
|---|---|---|
| manifest | `app.kotoba.edn` | `app.mobile.kotoba.edn` |
| surface | window onto `localhost:1338` | bundle at the app's own origin |
| server | this repository, on the JVM | `services/app-edge`, on Workers |
| origin | `http://localhost:1338` | `kotoba-webbundle://app` (iOS) / `https://appassets.androidplatform.net` (Android) |

## What is on it today

The single document has three panes: the public fleet directory, the paired
command screen, and app information. `src/cloud/itonami/mobile/app.kotoba`
owns screen state, transitions, and the rendered document. `src/main.mjs` is
the browser host for DOM events, HTTPS requests, local pairing storage, and
scrolling. The guest runs as Amu/Kotoba JavaScript; the host does not grant it
ambient browser capabilities.

### コマンド — the itonami CLI, on a phone

The command screen resolves the pinned command-table snapshot in
`data/command-tables.json`. `commands.lock` pins the upstream revision and the
snapshot hash; the source file hashes are carried in the snapshot metadata.
The Amu/Kotoba guest handles tokenization, longest-prefix matching, flags,
path parameters, and query parameters. This is a mobile implementation of the
table format, not a call to the desktop resolver namespace, so table-format
changes must be reviewed against both front ends.

What this pane can do is bounded in two places and on purpose:

- **it offers reads and the ingress's three conversation write templates.**
  The current command table exposes only `bots task` as a write; decisions
  and approvals are declined because this device has not presented a Passkey
  (ADR-0083). The host checks each exact template again before sending.
- **an unpaired device sends nothing.** The ingress and the token are entered
  on this pane and stored in this app's WebView local storage. Until both are
  set, a valid command is built, shown, and not issued.

The three refusals are three sentences, never one: a command that does not
exist, a command this surface withholds, and a request that went out and did
not come back are different facts about the world.

The default command ingress is `https://agent.itonami.cloud`. Pairing requires
an HTTPS origin and a token. Requests use that origin only; credentials,
paths, query strings, and fragments are rejected in the base value.

### フリート — the actor directory

Search the cloud-itonami actor directory, filter by role, and inspect each
actor's declared metadata. The fleet endpoints are read-only; the separate
command pane is the only screen that sends a request to the paired ingress.

## One app, and a shinkansen bundle (ADR-0143)

This is **the** itonami iOS / Android app: `cloud.itonami.app` on both stores,
kotoba-shell as the only host. The Tauri app and the `dev.kotoba.itonami` iOS
declaration in network-awai/cloud-itonami are superseded by this manifest.

The bundle follows parts of `docs/SHELL-BINDING.md` in
[`kotoba-lang/shinkansen`](https://github.com/kotoba-lang/shinkansen): the
viewport audit with the top safe-area inset named and a deny-all native bridge.
The publish receipt gate, theme and interaction runtimes, locale variants, and
`shinkansen.invoke` writes are not implemented. Signing is deferred, so
`app package` refusing is expected.

## Build

```bash
cd mobile
npm ci
AMU_ROOT=/path/to/pinned/amu \
KBB_ENGINE_ROOT=/path/to/pinned/amu-engine \
JP_GO_DDS=/path/to/pinned/jp-go-digital-design-system \
npm run build
```

`amu.lock` pins the Amu compiler and browser host runtime. `engine.lock` pins
the engine Amu uses to run its compiler. `dds.lock` pins the DADS source
revision and stylesheet hash. `commands.lock` pins the mobile command-table
snapshot. The build copies the DADS stylesheet, checks the committed HTML
template, compiles `app.kotoba` with `--target js-kotoba-v1 --jvm-free`, copies
the pinned browser host runtime, and audits the generated bundle and viewport.
It does not invoke the Clojure CLI/JVM or Shadow CLJS.

Then scaffold and build the native apps, **from this repository's root**:

```bash
# the output directory is not cleaned between runs, and `app build` does NOT
# re-copy the web bundle — a rebuilt mobile/dist reaches the app only through
# `app scaffold`. Measured 2026-09-06: a fixed index.html was in mobile/dist,
# absent from ios/Resources/WebBundle, and absent from the installed .app,
# while every step reported success.
rm -rf target/kotoba-shell/app

SHELL_ROOT=../../kotoba-lang/shell   # the west checkout of kotoba-lang/shell

kbb -Sdeps "{:deps {io.github.kotoba-lang/shell {:local/root \"$SHELL_ROOT\"}}}" \
  -M -m kotoba.shell.launcher app scaffold \
  --target ios --target android \
  --manifest app.mobile.kotoba.edn --policy mobile/shell-policy.edn \
  --output-dir target/kotoba-shell/app

kbb -Sdeps "{:deps {io.github.kotoba-lang/shell {:local/root \"$SHELL_ROOT\"}}}" \
  -M -m kotoba.shell.launcher app build --target ios --execute \
  --manifest app.mobile.kotoba.edn --policy mobile/shell-policy.edn \
  --output-dir target/kotoba-shell/app

# Android needs JDK 17: AGP 8.5.0's androidJdkImage transform picks the newest
# JDK on the machine, not the one on PATH, and fails on a too-new one.
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
kbb -Sdeps "{:deps {io.github.kotoba-lang/shell {:local/root \"$SHELL_ROOT\"}}}" \
  -M -m kotoba.shell.launcher app build --target android --execute \
  --manifest app.mobile.kotoba.edn --policy mobile/shell-policy.edn \
  --output-dir target/kotoba-shell/app
```

### Not `bin/kotoba-shell`

This README used to document `orgs/kotoba-lang/shell/bin/kotoba-shell app
scaffold …`. That wrapper **does not dispatch subcommands**: until
kotoba-lang/shell `5c5a14e` it printed a note, ran its own compile+run demo,
wrote nothing, and **exited 0**. It now exits 2 and names the command above.

It could not have served the command even if it dispatched: it `cd`s into
kotoba-lang/shell, while the library resolves `--manifest` and `:web/dist-dir`
against the working directory. Run from there, iOS fails with `xcodegen`
`missing source directory … Resources/WebBundle`; run from here, both targets
scaffold (`ready-count 2`).

## Measured on a device, 2026-09-06

| | |
|---|---|
| Android | `app-debug.apk`, 1.3 MB, carrying `assets/js/main.js` (453 KB) with the ingress host and the command table |
| iOS | `BUILD SUCCEEDED`, `CloudItonami.app` for the simulator |
| iOS 26.5 Simulator (iPhone 16) | boots, WKWebView serves the bundle over the custom scheme, and the fleet screen shows **1,294 actors read from the live edge** |

The first launch screenshot was blank — taken 6 s in, while first-meaningful
paint took 13 s on a loaded machine. That is worth writing down: on this
surface, an empty screenshot is as likely to be an early one as a broken app.
These device measurements predate the Amu/Kotoba bundle migration; the updated
bundle has not yet been launched in an iOS simulator or Android emulator.

**It also found a real bug that no browser check would have.** `jp-go-dds`'s
`a11y-css` documented safe-area on 上下左右 and implemented three sides; its
test iterated `["left" "right" "bottom"]` under the name 「左右下の全辺」. With
`viewport-fit=cover` the nav row ran under the Dynamic Island, clipped and
untappable. Fixed upstream (jp-go-dds `3950c1a`), test renamed to four sides
and shown to go red on `top` alone.

`app build` is the development loop (simulator SDK, `assembleDebug`, unsigned).
`app package` is the distribution half and **refuses** rather than emitting an
unsigned `.ipa` or an `.aab` Play will reject; it needs `:apple/team-id` or a
Gradle keystore, neither of which this manifest carries yet.

## Store readiness (2026-10-14)

Draft listing, privacy answers and export compliance: `store/metadata.edn`.
Screenshots: `store/screenshots/{ios,android}/` (iPhone 6.9" 1320×2868,
Android phone 1080×2160 — Play allows at most 2:1), taken on simulator/emulator against the live edge.
Store art: `store/art/`, generated by `scripts/make-store-art.swift` from the
same gradient and い as the desktop icon, but full-bleed and opaque (the
desktop `icon.png` is a macOS plate with transparent corners).

Without an account the app is a usable read-only directory: search, a role
menu built from `/api/fleet/facets`, a detail screen per actor, and an 情報
pane with support and privacy addresses (Guideline 4.2).

**Depends on kotoba-lang/shell PR #36** (branch `claude/mobile-store-blockers`,
"Fix App Store / Google Play blockers in scaffolded iOS and Android apps").
`app.mobile.kotoba.edn` uses the manifest keys that PR adds — `:ios/icon`,
`:android/icon`, `:android/adaptive-icon`, `:ios/device-family [:iphone]`,
`:ios/orientations [:portrait]`, `:ios/build-number`,
`:ios/uses-non-exempt-encryption? false`, `:ios/privacy-accessed-api-types []`,
`:android/allow-backup? false`, `:android/cleartext-traffic? false` — and an
older shell ignores them, giving back every blocker below. Until it merges,
scaffold with that branch (`SHELL_ROOT=<checkout of claude/mobile-store-blockers>`).

What it fixed, measured 2026-10-14 on an iPhone 17 Pro Max simulator (iOS
26.5) and a Pixel 7 API 35 emulator (`google_apis`), `app scaffold` + `app
build --execute` on both:

- the い icon on the iOS Home screen and in the Android launcher (adaptive);
- iOS `UIDeviceFamily [1]`, portrait, `CFBundleVersion 1`,
  `ITSAppUsesNonExemptEncryption NO`, `PrivacyInfo.xcprivacy` in the bundle —
  so no iPad screenshots are required;
- Android: no ActionBar, the WebView sits between the status and gesture bars
  (the screenshots are no longer scrolled past a hidden strip), no
  notification prompt and no `POST_NOTIFICATIONS` at all (the policy grants
  no `notify/show`), `usesCleartextTraffic` false, `allowBackup` false.

**Android Back.** Panes and the detail screen are state, not URLs, so there
is no WebView history for the shell's Back to walk. `app.kotoba` handles the
shell's cancelable `kotobaShell:back`: detail → list, コマンド / 情報 →
フリート, and only on the fleet list does Back leave the app (measured).

## Verify

```bash
npm ci
AMU_ROOT=/path/to/pinned/amu \
KBB_ENGINE_ROOT=/path/to/pinned/amu-engine \
JP_GO_DDS=/path/to/pinned/jp-go-digital-design-system \
npm run build
```

The build verifies compilation, host JavaScript syntax, the checked-in HTML,
the pinned inputs, and the viewport/layout contract. It does not run a browser
interaction suite or a native simulator launch.

## Constraints worth knowing before changing anything here

- **No app CSS.** Every class is DADS or `dds-ext-*`. The `--hig-*` bridge
  carries no `--hig-spacing-*`, so `padding: var(--hig-spacing-4)` compiles to
  `padding: ;` — a build that passes and a layout that is quietly wrong
  (ADR-2608060000).
- **One document** (ADR-2608080100). `dist/index.html` is generated from
  `jp-go-dds.page` and committed; `npm run index:check` fails if the design
  system would now render it differently.
- **The bridge is granted nothing.** `shell-policy.edn` is `:allow []` /
  `:deny ["*"]`. This surface reads a public catalog and stores nothing, so it
  needs no clipboard, no keychain, no notifications, and not even the bridge's
  `http/fetch` — that grant names a command, not a destination, and would
  reach any host.
- **Everything in `dist/` ships.** `app scaffold` copies the directory
  wholesale into the `.ipa` and `.apk`. `scripts/audit-viewport.mjs` rejects
  files outside the generated page, stylesheet, JavaScript bundle, and runtime.
