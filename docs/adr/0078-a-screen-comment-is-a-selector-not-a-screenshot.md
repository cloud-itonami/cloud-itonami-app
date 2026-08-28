# ADR-0078: A screen comment is a selector, not a screenshot

**Status:** accepted — 2026-08-27

## Context

Noticing that something on a screen is wrong, and getting it changed, were two
unconnected acts in this application. A person saw a panel that did not say why
a run failed, and then had to leave the screen, describe the panel in prose to a
Bot, and hope the description named something the Bot could find in the
repository. The screen is where the observation is precise and the prose is
where it stops being precise.

The obvious design — capture a picture of the region and send it — does not
survive contact with how this application actually reaches a model. A Bot
receives images only through a **tool result**: `run-tool!` returns `:images`
and `tool-messages` attaches them to the following user turn. `bots/send!`
takes text and nothing else. A comment that shipped a screenshot and said
「添付した画像を見て」 would produce a Goal that reads like evidence and contains
none — the failure mode ADR-2608136000 names, arriving through a new door.

What a Bot can actually act on is a **string it can search for**. This
application renders its own screens from `web.clj` hiccup and drives them from
`interaction.js`, so a CSS selector, an element's class list, and the element's
own text are all literals in the two files a fix would edit.

## Decision

Comment mode is a mode of the application's own UI. Turning it on puts a fixed
overlay over the viewport; a drag selects a rectangle and a right-click selects
the element under the pointer whole. Both produce the same two things:

1. **A DOM descriptor** — selector, tag, id, classes, `data-` attributes, and up
   to 400 characters of the element's own text. This is the evidence.
2. **A region** — x/y/width/height in CSS pixels relative to the viewport, with
   the viewport size and device pixel ratio beside them.

The comment becomes a **bounded Goal**. It is not a new kind of work item, a
queue, or a second inbox: 「修正をさせる」 is planning and tool use, which is what a
Goal already is.

**The POST records; it does not run the turn.** `POST /api/bots/comments`
validates the request, checks the session owns the named Bot, stores the crop,
and answers with the composed Goal text. The client then fills the Bots
composer with that text, applies a one-run Goal override, and submits it — so the run opens through
`#bots-form` and the existing streaming path. A Goal is minutes long (a resident
tick on this deployment has been measured at ninety-five), so dispatching from
the POST would have held an HTTP request open for the length of the run and left
the popover disabled with one spinner, no phase, no elapsed time and no Cancel.
The Bots view already solves all of that. This also leaves **one** dispatch path
in the client rather than two.

A **picture is stored when the client can make one**, served back at
`/api/bots/comments/<id>/image`, and described in the Goal text in these words:
「人が後から見るための記録で、あなたはこの画像を見ていません」.

**The crop is an SVG, not a PNG, and that is forced rather than preferred.**
This page's CSP is `img-src 'self'` — no `data:`, no `blob:` — which ADR-0007
chose deliberately: 「`data:` would let any string in the page become an image」.
Rasterising a DOM subtree in a browser means loading an SVG into an `<img>`, and
that load is exactly what the policy refuses. Measured 2026-08-27 against the
running app: under this CSP a ten-pixel `<rect>` served from a blob URL fails to
load, and `createImageBitmap` cannot decode SVG in Chrome at all. There is no
rasterising path left that does not weaken the policy, so the crop stays vector:
the client serialises the selected subtree with a curated set of computed styles
into an SVG document, the server stores it, and it is viewed at its own URL — a
navigation, which `img-src` does not govern. **The application page's policy is
unchanged.**

The crop is best-effort by design — a very large subtree defeats it — and every
failure path returns a **reason the popover shows**, so "there was no picture"
and "the picture failed" never arrive looking the same.

The routes live under `/api/bots/comments` inside `handle-bots!`, which gates
every route behind `require-human-session!`; the POST additionally requires
Origin and CSRF.

## Consequences

- **Recording and dispatching are two outcomes and are reported as two.** If the
  Goal cannot be started after the comment is stored, the person is told the
  comment was recorded and named by its id — saying 「送れませんでした」 over a
  comment that is on disk would send them to write it again.
- **The Goal names a place in this repository**, not a feeling about a screen.
  The selector, the class list and the quoted text are all searchable with
  `workspace_search`, and the Goal text says which two files render the view.
- **The picture is honest about what it is.** Making it model-visible would mean
  either extending `bots/send!` to carry images or giving Bots a tool that reads
  the comment store. Both are real options and neither is taken here; until one
  is, the text says the model has not seen it.
- **The crop is the selected element, cropped by `viewBox` to the selection.**
  Content from other elements that merely overlaps the rectangle is not in it.
  The intrinsic size is the selection, so opening the file shows what was
  selected rather than the element it was taken from.
- **A stored crop is a clone of the live DOM**, which shows mail, Bot messages
  and repository text this application did not write. Three locks, not one: the
  client strips `script`/`iframe`/`object`/`embed` and every `on*` attribute,
  the server refuses a script-shaped document with 413, and the response that
  serves it carries `sandbox; default-src 'none'` and `nosniff` — the same shape
  `send-site-html!` already uses for authored markup.
- **The inline thumbnail is the one thing lost.** An `<img>` in the popover is
  precisely what `img-src 'self'` refuses. The selection is cut out of the scrim
  behind the popover, which is the live version of the same picture, so the
  popover says the record is being kept rather than showing it twice.
- **The selector is only as good as the element.** `commentSelectorFor` prefers
  `id`, then a `data-` attribute this application actually renders, then class,
  and only then `nth-of-type` — the one part that says nothing about what the
  element is. `test/browser/comment_mode.cljs` asserts the property that matters:
  the selector the client wrote **resolves to a real element**. A selector that
  matched nothing would still serialise, still post, and still send a Bot to
  search for a string that was never on the screen.
- **Refusals keep their own names.** An empty comment, a comment about nothing on
  the screen, and a comment with no destination are three different 400s. A
  drag that never moved is refused rather than normalised into a zero-area
  rectangle, and a crop is measured in UTF-8 **bytes** rather than characters —
  a Japanese UI counted by character would admit three times the cap.
- **`page-html` had to give up two panels.** It compiles to a single JVM method
  and was already at the 64 KB ceiling — adding this markup inline produced
  `Method code too large!`. `comment-layer` and `comment-mode-toggle` are
  functions for that reason, which is the same limit `server.clj` names for its
  `handler`.
- **The document identity moves.** The comment layer is in the page whatever the
  identity is, so `kotoba.app.edn` must be republished before landing, as
  `bundle_test` requires.
- **The decisions are runtime-free; only the plumbing is JVM.** Everything that
  judges anything — the rectangle, the DOM descriptor, the Goal text, whether a
  crop is admissible, and the byte cap — is in `issue_comment.cljc` and is run
  by BOTH `test-runner` (JVM) and `portable_nbb.cljs` (ClojureScript). That
  second runner is what makes the extension mean something: `utf8-length` is
  `String.getBytes` on one runtime and `TextEncoder` on the other, so only
  running both shows the cap means the same thing in a browser as on the server.
  What stays JVM is the route wiring in `server.clj` and the test that starts
  that server — both because `com.sun.net.httpserver` is what serves this app
  today. They move when it moves (ADR-0065, ADR-2608081500); nothing in this
  feature holds them there, and it added no new `.clj`.
- **`scripts/jvm-exit-report.cljs` came out of this.** Asked how far the app is
  from running without a JVM, the only available answer was a file count by
  extension, and that count is wrong in both directions. The script walks the
  require graph instead and reports three sets: portable today, blocked by an
  in-repo namespace (with what is blocking), and *unmeasured* because a
  dependency lives outside this repository. Measured 2026-08-27: 150 `.clj`, of
  which 11 are clean but waiting on a short queue headed by
  `cloud.itonami.app.store`, and 11 more cannot be judged from here at all.
  Its own first version reported four files as portable; two of them would not
  load, because it counted external libraries as harmless. That is why the
  third set exists, and why a rename is only finished when the namespace runs
  under `bin/test-portable-cljs`.
- **Scope is this application's own screens.** Pages shown in the Sites view are
  cross-origin and would yield coordinates without a DOM, which is the half of
  the evidence that does not survive. Not taken.
