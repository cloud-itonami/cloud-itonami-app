# ADR-0059: The desktop is driven without taking the cursor

**Status:** accepted — 2026-08-18

## Context

`agent-control` has had a `computer_*` tool family since it had a device
capability at all: `computer_screenshot`, `computer_key`, `computer_type`,
`computer_click`, `computer_scroll`. All four write tools drove **the frontmost
application**, with `osascript` System Events keystrokes and `cliclick`. Their
safety rested on `require-frontmost!`, which re-read the frontmost application
after approval and refused if it had changed.

Two things are wrong with that, and the second is the reason this ADR exists.

The first is that the guard is weaker than it reads. "The same application is
still in front" does not mean the same window, the same document or the same
field is in front. A person who switched tabs, or an autosave that moved a
sheet, leaves the check satisfied and the target different.

The second is that the tools take the machine. `cliclick` moves the real
cursor. `keystroke` goes to whoever holds the key window. On this machine that
is not a theoretical cost: it runs many concurrent agent sessions in parallel
terminal panes that compete for OS focus, and the workspace's own
`computer-use` skill already carries a warning that blind keystroke automation
has typed into the wrong pane. A capability whose precondition is "nobody is
using the computer" is one that cannot run while somebody is.

Nous Research's Hermes agent describes the shape this should have instead:
"Your cursor doesn't move, keyboard focus doesn't change, and your virtual
desktops / Spaces don't switch on you" — the agent reads the accessibility tree
of a window and acts on it without bringing it to front. Hermes reaches that on
macOS with private SkyLight APIs. This application will not: a security-first
build whose entire argument is that every provider was reviewed cannot rest a
capability on undocumented system internals.

So the question was what the *public* accessibility API can actually do, and
that had to be measured rather than assumed.

## What was measured

macOS 26.3.1, 2026-08-18, against a background TextEdit while the frontmost
application and the cursor position were recorded before and after each call.

| operation | mechanism | result |
|---|---|---|
| read an application's tree | `AXUIElementCopyAttributeValue` walk | works |
| read its menu commands | `kAXMenuBarAttribute` walk | works — 249 items, 94 enabled |
| act on an element | `AXUIElementPerformAction` `AXPress` | **works** — window count 1 → 0 |
| act on a menu command | `AXUIElementPerformAction` on `AXMenuItem` | **works** — window count 0 → 1 |
| write a text element | `AXUIElementSetAttributeValue` `AXValue` | writes the widget |
| press a chord | `CGEvent.postToPid` `cmd+s` | **no effect** — file stayed 0 bytes, Save stayed disabled |
| type text | `CGEvent.postToPid` unicode | **no effect** — value byte-identical |

Frontmost and cursor were unchanged across every one of them, including the
ones that worked.

Synthesised events fail for a structural reason: AppKit routes key events to
the key window, and a background application does not have one, so the events
are enqueued and dropped. There is no flag that fixes this, which is what
private SkyLight is for.

One further measurement matters and is easy to miss: setting `AXValue`
succeeded and the text appeared, **and the document was not marked edited** —
Save stayed disabled and the file on disk did not change. Writing a widget and
editing a document are not the same act.

## Decision

The `computer_*` family is replaced by tools that address **elements and menu
commands by name**, through the accessibility API only.

    computer_tree         read an application's accessibility tree + a digest
    computer_menu         read its menu commands, each with its shortcut
    computer_screenshot   capture ONE window of a named application
    computer_press        perform an element's action (default AXPress)
    computer_menu_press   perform a menu command by path
    computer_set_value    write a text element's value
    computer_scroll       scroll a scroll area by page, via its AX action

`computer_key`, `computer_type` and `computer_click` are **removed, not
reimplemented**. The first two cannot exist in a focus-free form — they were
measured not to work — and shipping them would have shipped two tools that
report success and change nothing, which is worse than not having them because
a caller cannot tell the difference. The third was a screen coordinate, which
addresses whatever has since moved into that spot rather than the thing the
model saw.

A keyboard shortcut is expressed as the menu command it stands for.
`computer_menu` reports each command's shortcut precisely so a model asked for
`cmd+s` can find `ファイル>保存`.

**`require-frontmost!` is replaced by `--expect`.** `computer_tree` returns a
SHA-256 over every node's ref, role, subrole, title and frame; every write
quotes it back and the helper refuses if the tree has moved. The digest
deliberately excludes `value`, so text changing under an agent's own typing
does not invalidate its own snapshot, while a button that moved, was
relabelled or disappeared does. `expect` is a **required** parameter: an
optional guard would be omitted exactly when the screen was busiest.

The helper is one Swift binary, `bin/cloud-itonami-desktop-macos.swift`, built
by `bin/cloud-itonami-build-desktop-macos` — the same shape `kotoba-lang/shell`
already uses for its EventKit and Passkey helpers. Three calls are absent from
it by construction, and their absence is the contract:
`NSRunningApplication.activate`, `CGWarpMouseCursorPosition`, and every
`CGEvent` post. `cloud.itonami.app.desktop` is the only place that builds its
argv.

`computer_screenshot` becomes window-scoped. A whole-screen capture was
focus-free already — that was never the problem with it — but it hands the
model every other window on the display.

Finding the window needs both APIs, because neither answers alone.
CoreGraphics knows window ids, which is what `screencapture -l` takes;
accessibility knows the window and its frame but has no id, and the call that
would give one is undocumented SPI. So the frame comes from accessibility and
is matched against CoreGraphics. A match captures the window through anything
overlapping it; no match still captures the rectangle, including whatever is on
top of it. **Which one ran is part of the result**, because they are different
pictures. Measured 2026-08-19: this application's own window has no matching
CoreGraphics entry at all — the only layer-0 entries it owns are eight menu-bar
strips — so it is captured as a region.

Window enumeration stopped filtering for on-screen windows for the same reason.
The filter could not tell "this application has no window" from "this window is
not being composited right now", and answered the first for both; with the
display asleep, every application on this machine reported zero windows,
Terminal in front included. Each window now carries `onscreen` rather than
being removed by it.

Trust is **reported, never prompted for**: a helper invoked from a background
server has no business raising a system dialog. `diagnostics` returns three
separate facts (helper built, Accessibility granted, Screen Recording granted)
because they need three different answers from a person.

## What this does not do

- `computer_set_value` fails closed on an element that does not accept a value
  — a `contenteditable`, a canvas, a terminal. There is no focus-free fallback
  for those on the public API, and the only fallback available was synthesised
  keys, which do not work. A Bot that needs a web surface has the isolated
  browser (ADR-0036), which is a different and better answer.
- Nothing here gives a Bot the desktop. ADR-0036's dispatch is unchanged:
  `:bot/browser?` opts into the isolated browser, and `computer_*` stays on
  agent-control's own loop. That the tools no longer steal focus does not
  decide who may call them.
- **A marker, added 2026-08-19.** Acting without taking the cursor means acting
  INVISIBLY, and that is a cost the first version did not pay for. The tools
  this replaced were at least honest by accident: the pointer jumped, so a
  person knew. Now a translucent marker appears over the element for 900ms
  while the action runs, labelled with what is being done to it. It is a
  `.nonactivatingPanel`, ordered in with `orderFrontRegardless` and never made
  key, and it ignores mouse events, so it cannot take from anybody the focus
  the rest of this helper exists to preserve.

  **No tool can switch it off.** The duration is a constant in
  `cloud.itonami.app.desktop` and appears in no schema: a parameter would
  eventually be passed, and the one call where it mattered would be the silent
  one.

## Verification

Suite: 1641 tests, 9730 assertions, 0 failures (`clojure -M:test`); `clojure
-M:lint` reports nothing new for the files this touched.

Measured on macOS 26.3.1, 2026-08-18/19, with the frontmost application and the
cursor position recorded before and after every call. Both were identical
across all of it.

- `AXUIElementPerformAction` on a background TextEdit's close button took its
  window count 1 -> 0. `menu-press ファイル>新規` took it 0 -> 1.
- `AXValue` write landed and read back byte-identical.
- `CGEvent.postToPid` did nothing, twice: `cmd+s` left the file at 0 bytes with
  Save still disabled, and eighteen characters left the text area unchanged.
  This is why there is no `key` and no `type`.
- The tree digest was identical across five consecutive reads, and a stale
  digest was refused with `tree-changed`. A guard that always fires would be
  the same defect as one that never does, so both directions were checked.
- Bundle ids resolve case-insensitively (`com.apple.Finder`,
  `com.apple.finder`, `COM.APPLE.FINDER`, `Finder`, `finder`) while
  `com.example.nope` is still refused.
- The deployed installation was exercised from `~/.cloud-itonami/app` after its
  own build, not only from a source tree.
- The marker was shown over a background Terminal window and observed from
  another process: exactly one window owned by the helper, at exactly the
  target's frame, composited, with the frontmost application and the cursor
  unchanged. `the-marker-cannot-take-focus-either` was shown to go RED when
  `orderFrontRegardless` is swapped for `makeKeyAndOrderFront`.
- Both capture modes produce an image whose pixels match the reported frame at
  this display's scale: `window-id` gave 788x1794 for a 394x897 window, and
  `region` gave 860x1784 for a 430x892 one.
- `the-helper-cannot-take-the-cursor-focus-or-the-front-window` was shown to go
  RED when a real `CGWarpMouseCursorPosition` call is added to the helper, and
  green when it is removed. `the-absence-check-can-fail` guards the substring
  search itself against passing on an unreadable or over-stripped file.

Two things are NOT verified, and saying which is the point:

- **No test forces a real activation** to prove the live focus assertion would
  catch a regression. Doing so means taking a person's focus on a machine
  somebody is using. The assertion is known to be evaluated against two real
  readings; it is not known to be sufficient.
- **The live focus test skips when System Events cannot name a frontmost
  application** (-1719, which it answers whenever nothing is frontmost). It
  skipped on 2026-08-18 and ran on 2026-08-19. Before this landed it did not
  skip in that state -- it compared `""` to `""` and reported a pass, which is
  the defect this ADR's own thesis is about, found in its own test.

## Open

- **No focus-free path for an element that does not accept a value** -- a
  contenteditable, a canvas, a terminal. `set-value` fails closed. The isolated
  browser of ADR-0036 is the answer for web surfaces; there is none for a
  native text view that only takes keystrokes.

## Consequences

- The capability can run while somebody is using the machine, which is the
  only condition under which it was ever going to be used here.
- An approval card can now say what will be pressed — `@a12 AXButton 保存` —
  instead of a screen coordinate, and the thing it names is the thing that
  executes.
- Applications that publish no accessibility tree cannot be driven at all.

  **A claim that used to stand here was wrong and is worth keeping visible.**
  It said this application's own window publishes zero AX windows and was
  therefore not drivable by its own capability. It does publish them. What was
  actually measured was a DIFFERENT window: opening `Cloud Itonami.app` from
  the Dock started `kotoba-shell-host-macos-window` with no arguments, and it
  came up with its defaults -- 720x512, titled `Kotoba`, reading `No kotoba:dom
  root`. That window was read, and its emptiness was written down as a fact
  about the application. Launched through `bin/cloud-itonami-app` the real
  430x892 surface appears and reports one AX window, a main window and a
  focused window. Fixed upstream in `kotoba-lang/shell` a8da7ad, where the
  bundle now records the arguments it should open with.

  The lesson is the one this ADR keeps arriving at from different directions:
  the reading was taken, it was internally consistent, and it was about
  something else.
- Bundle ids are the reliable way to name a target. This machine is
  Japanese-localized, where TextEdit answers to `テキストエディット` and not to
  `TextEdit`; the helper reports what IS running when a name does not resolve.
