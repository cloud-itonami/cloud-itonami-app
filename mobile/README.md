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

Two panes, one document, one bundle, one mount (ADR-2608080100). The nav is
generated from `view/panes`, so a pane with no button and a button with no pane
are both structurally impossible. There is no hash router: this bundle runs in
a WKWebView with no URL bar, so a fragment addresses nothing anyone can type or
share, and the workspace already carries five copies of the same
`fragment->view` (measured 2026-09-06). Moving between panes is a state change,
which is what the ADR asks for; addressability is what it offers, and this
surface has nowhere to offer it.

### コマンド — the itonami CLI, on a phone

The same commands the desktop `itonami` runs, resolved by the same namespace.
`cloud.itonami.app.commands` owns the tables, the longest-prefix match, the
path templating and the query/body split; this bundle requires it and hands it
the two EDN tables at compile time (`shadow.resource/inline`), exactly as
`bin/itonami` hands them in at runtime. There is no second resolver, for the
reason `bin/itonami` gives in its own header: two answers that agree until the
day they do not, with no test that would notice (ADR-2609061500).

What this pane can do is bounded in two places and on purpose:

- **it offers reads, plus the three write templates that ARE the conversation**
  (`terminal/offered-methods` / `offered-writes`). `bots decide`, `bots
  provision` and every approval resolve fine and are declined here, because
  approving a write needs a Passkey present and a device paired by token has
  not presented one (ADR-0083). The ingress refuses them too; the client
  refuses first so the screen can say *this surface declined* rather than
  showing a 404 that reads as *no such command*.
- **an unpaired device sends nothing.** The ingress and the token are entered
  on this pane and stored only on the device. Until both are set, a valid
  command is built, shown, and not issued — asserted in a real browser by
  recording every request the page makes and requiring the list to be empty.

The three refusals are three sentences, never one: a command that does not
exist, a command this surface withholds, and a request that went out and did
not come back are different facts about the world.

**The ingress does not exist yet.** `agent-base` compiles in as empty because
`agent.itonami.cloud` was NXDOMAIN when this landed (measured 2026-09-06); a
hostname that does not resolve, compiled in, would make an unreachable ingress
look like a configured one. Device pairing and the ingress Worker are steps 2
and 3 of ADR-2609061500 and are not in this commit.

### フリート — the actor directory

 search the ~1,200 cloud-itonami actors, see what each
declares, see which have an address. That is slice 1 of ADR-2608081500 and it
is **all the edge serves** — chat, mail, drive, calendar, Passkey and every
write surface are still JVM-only, so they are not on the phone. They arrive
here as the remaining slices land; nothing in this directory has to change for
them to.

## Build

```bash
cd mobile
npm install
npm run build          # index:check → dds.css → shadow-cljs release
```

Then scaffold and build the native apps, from the repository root:

```bash
# the output directory is not cleaned between runs — a file you removed from
# mobile/dist stays in the app until you do (measured 2026-08-31)
rm -rf target/kotoba-shell/app

orgs/kotoba-lang/shell/bin/kotoba-shell app scaffold \
  --target ios --target android \
  --manifest app.mobile.kotoba.edn --policy mobile/shell-policy.edn \
  --output-dir target/kotoba-shell/app

# Android needs JDK 17: AGP 8.5.0's androidJdkImage transform picks the newest
# JDK on the machine, not the one on PATH, and fails on a too-new one.
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
orgs/kotoba-lang/shell/bin/kotoba-shell app build --target android --execute \
  --manifest app.mobile.kotoba.edn --policy mobile/shell-policy.edn \
  --output-dir target/kotoba-shell/app
```

`app build` is the development loop (simulator SDK, `assembleDebug`, unsigned).
`app package` is the distribution half and **refuses** rather than emitting an
unsigned `.ipa` or an `.aab` Play will reject; it needs `:apple/team-id` or a
Gradle keystore, neither of which this manifest carries yet.

## Verify

```bash
clojure -M:test                                   # the view, every phase, on the JVM
npm run build && npx http-server dist -p 8099     # or python3 -m http.server
MOBILE_URL=http://127.0.0.1:8099/index.html npx nbb scripts/verify-browser.cljs
```

The browser check is the one that matters: it mounts the real bundle in a real
headless Chromium at phone size, asserts actors the API actually returned reach
the screen, that searching narrows them, and — with the API route aborted —
that an unreachable directory produces the **failure** screen and not the empty
one. The JVM test can only prove the view *can* say that; only the browser
proves the app *does*, on the path a phone with no signal takes.

## Constraints worth knowing before changing anything here

- **No app CSS.** Every class is DADS or `dds-ext-*`. The `--hig-*` bridge
  carries no `--hig-spacing-*`, so `padding: var(--hig-spacing-4)` compiles to
  `padding: ;` — a build that passes and a layout that is quietly wrong
  (ADR-2608060000). A JVM test asserts no class outside the design system and
  no inline style reaches the document.
- **One document** (ADR-2608080100). `dist/index.html` is generated from
  `jp-go-dds.page` and committed; `npm run index:check` fails if the design
  system would now render it differently.
- **The bridge is granted nothing.** `shell-policy.edn` is `:allow []` /
  `:deny ["*"]`. This surface reads a public catalog and stores nothing, so it
  needs no clipboard, no keychain, no notifications, and not even the bridge's
  `http/fetch` — that grant names a command, not a destination, and would
  reach any host.
- **Everything in `dist/` ships.** `app scaffold` copies the directory
  wholesale into the `.ipa` and `.apk`. The browser check asserts the directory
  holds only the app, because this script's own screenshots once shipped in it.
