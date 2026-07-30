# 0007 — A document is shown as the thing it is

- Status: accepted
- Date: 2026-07-30

## Context

The Drive can create four kinds of document, and `documents/kinds` is the
closed table that says which: a workbook, a document, a form and a deck. Until
now selecting one showed a metadata list and a row of buttons; the content
appeared only after pressing 編集, and then as a field editor — a list of
`ID / 種類 / 本文` rows for a document, `ID / ラベル / 種類 / 必須` rows for a
form, a card per slide for a deck. Those are the fields of the value. They are
not the value.

The consequence was that the answer to 「何を作ったのか」 was two steps away and
then arrived in a shape nobody writes documents in. A form did not look like a
form, a deck did not look like slides, and a workbook was the only one whose
editor happened to also be its natural shape.

## Decision

### The rendered surface is the default view

Each kind gets a read-only render of itself, and it is what the pane opens on:

- `docs` — a page. Real heading levels, paragraphs, blockquote, code block,
  lists, the first table row as its header, `:docs/text-runs` applied, and
  references resolved against what this principal can see (unresolved ones are
  marked rather than hidden, which is the same answer the server gives as a
  save-time warning).
- `forms` — the form as a respondent sees it: one card per question, the
  control for its `:forms/field-type`, required marked. Disabled, because
  answering is `answerPanel`'s job and a preview that submitted would be a
  second way to answer.
- `sheets` — an A1 grid with column letters, sticky headers, numbers right
  aligned and formulas shown as `=expr`. Nothing is evaluated: `sheets` has no
  evaluator on this path and a made-up result is worse than the formula.
- `slides` — the 10 × 5.625in stage `slides.pptx` writes, shapes positioned in
  inches as percentages and text sized in points via container query units, so
  the same builder makes both the stage and the filmstrip thumbnails.

Three modes, not two: プレビュー / フォーム表示 / JSON 表示. All three read and
write one projected payload, so this is a third view and not a third format —
the preview does not write at all, the fields write into the payload, and only
the JSON textarea can be ahead of it.

### A selected document opens itself

Selecting a document — including the one just created — fetches its content
without being asked. Guarded on payload, in-flight and failed, because the
detail pane is rebuilt on every keystroke in the search box and an unguarded
fetch there is a request per keystroke. A document that cannot be read is
marked failed so the next render does not retry; 編集 is the way to ask again.

### An image in a deck is not rendered, and the CSP is not widened for it

`slides.model/image` stores base64 and would render from a `data:` URI. This
page is served with `default-src 'none'` and no `img-src`, so it does not. The
frame says what is there and the `pptx` export carries the bytes.

Widening `default-src` is a decision about what this app may load, and this app
is a credential and consent surface. It is not a decision to make as a side
effect of adding a preview. If deck images should render, that is its own ADR.

## Consequences

- Creating a form, a document, a workbook or a deck now shows that thing.
- `surfacePreviews` and `surfaceEditors` must both cover every key of
  `documents/kinds`; `core_test` asserts it, because a kind added to the server
  table alone is a document the app creates and then declines to show.
- The surface roots must be styled by `app-css`; `core_test` asserts that too,
  since a class typo renders unstyled markup and fails nothing.
- The previews are not renderers of record. `slides.pptx` and the Markdown and
  CSV writers are on the server and export goes through them; nothing here is
  offered as a preview of what a file will look like.
- Deck images stay invisible in the app until someone decides about the CSP.
