# ADR-0087: the mobile app carries a bundle and reads the edge

**Status**: accepted / **Date**: 2026-08-31
**Related**: ADR-2608072000, ADR-2608081500 (superproject), ADR-0035, ADR-2608311000

## Context

The desktop application is a JVM server on `localhost:1338` with a native
window pointed at it. There is no JVM on iOS and none on Android, so the shape
does not travel: the bundle installs, the server does not. `local-manimani`
measured that on an iOS Simulator — real UI, `TypeError: Load failed` written
across it.

`kotoba-lang/shell` is not the missing piece. It has had verified iOS and
Android `app scaffold` / `app build` since 2026-08-07, an in-app native bridge
with ten policy-gated commands, and a `app package` half that refuses to emit
an unsigned artifact. What was missing was a **surface that does not need a
local server** — and ADR-2608081500 had already decided where the server goes.

## Decision

The mobile surface is a web bundle the app **carries**, at the app's own origin
(`kotoba-webbundle://app` on iOS, `https://appassets.androidplatform.net` on
Android), reading over HTTPS from `services/app-edge`.

1. **A second manifest, `app.mobile.kotoba.edn`.** The desktop manifest's
   `:window {:web-url "http://localhost:1338/…"}` is precisely the line that
   cannot travel, so the two are separate files rather than one file with a
   conditional.
2. **`mobile/` is its own project**, as `services/app-edge` is. The 100+ `.clj`
   files of the JVM application never reach a ClojureScript classpath.
3. **The view is `.cljc` and pure**, rendered by reagent in the browser and by
   `clojure -M:test` on the JVM. It is a list of cards and not the edge's
   four-column table: those are different screens over the same data, and the
   data cannot drift because both read `cloud.itonami.app.fleet-core` through
   one API.
4. **The bridge is granted nothing** (`:allow []` / `:deny ["*"]`). This
   surface reads a public catalog and stores nothing. In particular it does not
   take the bridge's `http/fetch` to avoid CORS: that grant names a command,
   not a destination, so it would let the page reach any host — a much larger
   authority than the one header it would save.
5. **The JSON surface answers `Access-Control-Allow-Origin: *`.** The app is a
   cross-origin caller by construction. A wildcard on a public read-only
   surface with no credentials grants no read that `GET /` already granted, and
   it avoids guessing what `Origin` a WKWebView sends for a custom scheme —
   a guess that fails only on a device, after packaging. When a route here
   starts reading a session (slice 2), that route needs an explicit allowlist.

## What was verified, and what was not

Measured 2026-08-31:

| | result |
|---|---|
| view, every phase, on the JVM | 6 tests / 21 assertions, and each control breaks exactly the assertion it should |
| the bundle in a real headless Chromium at 390×844 | 10/10 — mounts, renders actors the API returned, search narrows, and an aborted API gives the failure screen, not the empty one |
| `app scaffold --target ios --target android` | both ready, real bundle (`:placeholder? false`), policy asset deny-all |
| **Android `app build --execute`** | **`app-debug.apk`, 1,290,818 bytes, carrying `assets/index.html`, `assets/js/main.js`, `assets/dds.css` and a deny-all policy asset** |
| edge parity + route smoke | 14/14 and 13/13 against the deployed Worker |

**Not verified: iOS compiles.** Not because of anything in this repository —
Xcode 26.6 on this workstation has no iOS platform installed
(`xcodebuild: error: … iOS 26.5 is not installed`), and the volume has 12 GiB
free against a download of roughly that size. `xcodebuild -downloadPlatform
iOS` is the whole remedy. The scaffold itself succeeded, including `xcodegen`.

**Not verified: an actual store upload.** Unchanged from ADR-2608072000.

## The defect the browser found, which the JVM test could not

The screen said something false, intermittently, and only in a browser:

    1294 件が一致しました（全 1294 件中）。条件: finance

1,294 unfiltered results were on screen, `finance` had been typed, and **no
search had been issued yet**. The summary keyed on `:query` — the search field
— rather than on the query the results actually came from. Every keystroke
re-labelled an old result set with a new question.

`:applied-query` is now a separate key, written only when a read succeeds, and
a JVM test asserts the summary never describes results with a query that has
not been applied. It took six green browser runs in a row to believe the flake
was gone; before the fix it was two failures in five.

Two smaller things fell out of chasing it, both of the shape this workspace
keeps finding — **a wait that cannot wait reads exactly like a wait that
succeeded**:

- A string handed to Playwright's `waitForFunction` is evaluated as an
  expression, so a source string holding an arrow function is truthy and the
  wait returns on its first poll. Two assertions then read a screen that was
  still loading and reported the app broken while a screenshot taken moments
  later showed it working.
- `waitForSelector` with a `text=` selector waits correctly, but it returns as
  soon as *some* node matches — which, given the defect above, was before the
  search had happened. The wait now names the applied query.

## Two things this found that nothing else would have

- **Everything in `mobile/dist/` ships.** `app scaffold` copies the directory
  wholesale into the `.ipa` and the `.apk`. The browser check's own screenshots
  shipped in the first Android build; the scaffold reported success and the app
  worked. The check now asserts the directory holds only the app.
- **`app scaffold` does not clean its output.** A file removed from `dist/`
  stays in the previously scaffolded app until the output directory is deleted.

## Consequences

The mobile path is now structural rather than hypothetical: as slices 2–5 of
ADR-2608081500 land on the edge, they arrive on the phone without further
mobile work. What is on the phone is exactly what the edge serves, which today
is the fleet directory and nothing else — and the app says so rather than
implying otherwise.
