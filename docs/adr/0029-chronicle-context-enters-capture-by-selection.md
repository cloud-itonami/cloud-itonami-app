# ADR-0029: Chronicle context enters Capture by selection

## Status

Accepted and implemented.

## Context

Chronicle already keeps opt-in, user-scoped screenshots and local OCR for a
short rolling window. Capture already accepts an unclassified thought without
calling a model. Connecting them can preserve what a person was looking at
while thinking, but ambient screen history is more sensitive than an ordinary
note and OCR may contain hostile instructions.

Automatically copying Chronicle into Capture would also change retention: an
ephemeral six-hour frame would become part of durable local state without the
person taking an explicit act.

## Decision

Chronicle and Capture are connected through preview and explicit selection.

- Only a human Passkey session can list recent Chronicle candidates, take a new
  frame, or save a Chronicle-attributed Capture.
- Chronicle's existing per-user, default-off screen-context switch and macOS
  Screen Recording permission remain preconditions. Capture does not turn them
  on.
- The composer shows at most eight recent candidates. Each candidate contains
  only frame ID, capture time, application, a maximum 4,000-character OCR
  preview, and an `untrusted-reference` label.
- Selecting a candidate adds an editable quotation to the note and selects its
  attribution. The person can remove the attribution before saving.
- On `POST /api/captures`, the server resolves the frame ID again inside the
  authenticated user's Chronicle namespace. It never trusts application, time,
  or OCR supplied by the browser.
- The durable Capture stores a bounded attribution snapshot beside its immutable
  raw text. It never stores the screenshot, image path, OCR digest or full OCR.
- OCR remains data, never instructions. This path calls no model, agent,
  executor, Project adapter or WorkItem promotion.

The attribution snapshot remains when the rolling Chronicle frame expires or
Chronicle is deleted. This is deliberate: selecting and saving it is a separate
retention decision. The UI states that the selected excerpt is being preserved;
the person can remove the source before submitting or later move the entire
Capture to Trash under the normal Capture lifecycle.

## Consequences

- Freewriting and think-aloud records can preserve enough visible context to be
  understood during clarification and weekly review.
- A web client cannot forge another user's frame or smuggle an arbitrary local
  file path into Capture.
- A Capture is not a replayable screenshot archive. Visual evidence remains in
  Chronicle only for its configured rolling retention.
- Chronicle deletion and Capture deletion are intentionally separate acts.

## Implementation

- `chronicle/capture-candidates` and `chronicle/capture-source`
- optional `:capture/source` attribution in `capture/create!`
- `GET /api/captures/chronicle`
- `POST /api/captures/chronicle/capture`
- Chronicle selector and source display in the Capture view
