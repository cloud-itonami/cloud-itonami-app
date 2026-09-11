# ADR-0094: A Bot's apps are chosen by the job they do

**Status**: accepted · 2026-09-09 · owner instruction「こういう感じで agent に対して紐付ける app を 選べる画面を cloud-itonami-app に統合して」

## Context

Creating a Bot asked one question about outside services: a flat, searchable
grid of every connector this build carries, eight tiles, each with a name and a
tool count. That answers *what is available*.

It is not the question somebody creating a Bot is holding. Theirs is **what
will this Bot use for mail?** — and the grid answers it only by being read
end to end, eight times, once per job they had in mind. The owner's reference
screen makes the difference plain: one row per job, each naming the apps that
can fill it, and a control per row.

## Decision

1. **The same set is also offered one row per job.** `app-directory/slots`
   declares カレンダー, メール, チームコミュニケーション, ファイルとドライブ,
   ワークスペースメモ and コードとレビュー, each naming the connector ids that
   can fill it. An id may appear in more than one slot: Microsoft 365 is this
   deployment's mail **and** its calendar, and a table that made it choose
   would be wrong about the other.

2. **A slot is a way to choose, not a thing a Bot holds.** Nothing downstream
   knows a slot exists. `bots/create!` still receives a set of connector ids and
   `default-tools` still computes the grant from those alone. A slot that
   persisted but changed nothing would be decoration; one that changed the grant
   would be a second authority beside `:bot/tools`.

3. **One selected set, two renderings.** The slot rows and the grid both read
   and write `botsState.picked`, and they are never redrawn apart — a click in
   the grid that left the slot rows stale would show a person two different
   answers to one question. The server sends the slot **structure** and
   deliberately not which apps are selected: a `:chosen` computed there would be
   right when the page loaded and wrong from the first click.

4. **The grid stays.** It answers a different question about the same set —
   *what is available* — and it has search. Two views of one fact is a list/grid
   toggle; two facts would be the drift this repository keeps finding.

5. **Why a row cannot be picked is decided once, on the server.**
   `app-directory/availability` returns `:usable` / `:no-tools` / `:no-client`,
   and `bots/catalog` carries it on every row. It used to be recomputed in
   JavaScript inside the grid renderer, which was fine while the grid was the
   only picker; the slot rows ask the same question, and two copies of a
   decision whose halves send a person to different places — one is something an
   operator turns on in this build, the other is something they configure for
   this machine — is exactly how the two come to disagree about a connector
   nobody looks at twice. This change removes a copy rather than adding one.

6. **An unofferable candidate stays in the list, disabled, carrying its
   reason.** Dropping it would answer "this app does not exist here", which is
   not what is true.

7. **The table is hand-written, and it cannot go quiet.** Nothing in a connector
   descriptor says what job it does: `connector/model` records id, name, origin
   domain, auth and tools, and a category is not among them. Two things stop the
   table drifting silently, and they disagree on purpose:
   - `other-slot` catches whatever is unassigned, so a connector no slot names
     is still offered under その他 — nothing becomes unreachable because
     somebody forgot to classify it;
   - `every-connector-this-build-carries-has-a-job` asserts `unassigned` is
     empty **against the real registry**, so the same connector is red for a
     developer. Runtime stays correct and the omission still makes a noise.

## Consequences

- **Where the category belongs is upstream, on the descriptor.** Putting
  `:connector/category` in `kotoba-lang/connector` and in each connector repo
  would let the slot membership be derived rather than declared. That is nine
  repositories and nine pins for a screen, and it is the exit condition for
  item 7 rather than a thing this change does.
- **Local capabilities are not slots.** Coding, browser, computer use and Wallet
  are toggles in the next step of the same flow. They are a different fact — a
  capability of this application, not an outside account — and giving them a
  second control here would be two controls for one thing.
- **Creation only.** The reference screen is a creation dialog and this follows
  it. The same rows would read well in a Bot's settings panel later; that is a
  separate change, and it needs an answer for what changing a live Bot's grant
  does to work already in flight.
- The picker is longer than it was. Six job rows above eight tiles is more to
  scroll than eight tiles alone, and it is the trade: the rows are read once and
  answer the question, the grid is there when the question is a different one.

## Evidence

- `app_directory_test.clj` — `availability` on all three cases with the
  precedence between them asserted (both true at once is the common case, and
  reporting the client first would send somebody to configure OAuth for a
  connector that would still do nothing); the wire carrying no selection;
  the fallback row **exercised** with an unclassified connector, so the gate
  cannot be satisfied by a `rows` that silently drops what it does not
  recognise; and the gate itself, against the real registry.
- Discrimination, measured 2026-09-09: removing `com.github` from the table
  turns the gate red naming `com.github`.
- `the-screen-can-render-what-it-is-sent` asserts the shipped `interaction.js`
  reads the payload, redraws both views together, and **no longer** contains
  the two expressions the grid used to decide availability with. Without that
  last pair a later edit could put the second copy back and nothing would say
  so.
- `test/browser/bots_view.cljk` covers the property that only a browser can
  show: choosing in a slot marks the same app selected in the grid. The
  connector it selects is read out of the DOM rather than named, so the test
  measures the picker and not which OAuth clients the developer has configured;
  when nothing is offerable it **fails** rather than skipping.
- Two invariants this change tripped and now records: raw `"` inside the
  `base-css` string (which ends it — the hazard that string's own comment names)
  and a second mobile breakpoint at 40rem (`core-test` forbids it; this
  workspace breaks at 56rem). Both were caught by checks that already existed.
