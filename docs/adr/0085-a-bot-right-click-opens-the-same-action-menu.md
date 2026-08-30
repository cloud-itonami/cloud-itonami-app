# ADR-0085: A Bot right-click opens the same action menu

**Status:** accepted — 2026-08-30

**Landed on default branch:** `bb21694e4305e9617f6a83b7ebbf6ffcffc157e5`
(`Show a Grok-style action menu when right-clicking a Bot.`). This ADR records
that merge; it does not re-decide it.

## Context

The Bots rail is a vertical list of avatars. Left-click already selected a Bot.
There was no way to pin, hide, mark unread, duplicate, copy the conversation id,
or archive one Bot without opening the thread and hunting for a control. The
menu a person already knows — Grok's Bot context menu — was the request, not a
new information architecture.

Sidebar placement (`:bot/priority?`, `:bot/pinned?`) was already presentation
state, not execution authority. Section, unread, and hidden belong in that same
class. They must not widen tools, writes, browser, or computer.

## Decision

Right-click (and Shift+F10 / the ContextMenu key) on a `.bots-rail__item` opens
a portal menu on `document.body`. The rail clips overflow on a phone, so a menu
inside the rail would be cut off.

The labels, in order:

1. ピン留め
2. 1個のBotを新しいセクションに移動
3. 未読にする
4. プロフィールを編集
5. 複製
6. テンプレートとして共有
7. 会話IDをコピー
8. サイドバーから非表示
9. 1個のBotを削除 — danger colour (`--color-semantic-error-1`)

Left-click still selects. It does not open the menu.

Pin, unread, hidden, and section persist on the Bot record through the existing
`POST /api/bots/:id` update. Delete is the existing archive path
(`enabled? false`). Duplicate is the existing create path. None of these fields
are consulted by tool admission.

Selecting a Bot clears `unread?`. Hidden Bots stay out of the rail unless the
search box has a query. A custom section group sits after 優先度 / ピン留め and
before the date groups.

## Evidence

- JVM: `cloud.itonami.app.bot-test` + `web-script-test` — 48 tests, 433
  assertions, 0 fail / 0 error. `node --check` on `interaction.js` OK.
- Live `http://localhost:1338/#/bots` (release
  `81a29545a81ae9054993028f1ca05818c26d6c89`, patched in place and restarted):
  150 rail items; left-click does not open the menu; right-click labels match
  the list above byte-for-byte; delete is `rgb(236, 0, 0)`.
- Playwright `test/browser/bots_view.cljs` asserts the same labels. It was not
  re-run against this live process: the demo identity cookie is unauthenticated
  here.

## Not done

- West pin for `cloud-itonami-app` remains
  `7b22495c19c4b736495642801587fc72a7111db6`. Default-branch tip is `bb21694`.
  Fleet nodes still see the old pin until `west-pin-put` advances it.
- The running LaunchAgent is not a new packaged release. CSS and JS are slurped
  at namespace load; a restart was required after the patch.
- A packaged release at tip, and a west pin at tip, are the next concrete
  commands — not more menu items.
