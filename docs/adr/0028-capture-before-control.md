# ADR-0028: Capture before control

## Status

Accepted and implemented.

## Context

Cloud Itonami already had three nearby surfaces, but none was a capture inbox:

- Chat persisted a prompt, but only by sending it to a model;
- Drive accepted free text, but only after creating and editing a document;
- governed Kanban required organization, project, capability, yakuwari and a
  content hash before a WorkItem could exist.

Those are useful forms of control. They are the wrong first question while a
person is freewriting or speaking a thought aloud. Requiring the person to name
the action, project or authority before recording it makes clarification a
precondition of capture and loses the distinction GTD depends on.

## Decision

Add a human-only Capture surface with two separate acts.

1. **Capture** accepts raw text and a recording mode only. It preserves the text,
   including whitespace and line breaks, records owner, active organization and
   time, and does not invoke a model, executor, Project adapter or WorkItem.
2. **Clarify** later assigns one outcome: `next-action`, `project`, `waiting-for`,
   `someday-maybe`, `reference`, or `trash`. A title defaults from the first
   nonblank line; project, context, due date and waiting-for are optional.

The raw text is immutable. Clarification adds a projection beside it rather than
rewriting what the person actually wrote or said. An item may be reviewed,
completed, or returned to the Inbox explicitly; all three acts are audit events.

Capture data is stored under `:capture` in the existing atomic local
`state.edn`. Reads and writes are scoped to both the human user and active
Organization. Agent sessions are refused: private pre-clarified thought is not
agent context or delegated authority merely because it is in the application.

The browser may offer explicit speech-to-text through `SpeechRecognition` when
available. It stores only the resulting text, never audio. The surface warns
that a browser may use an external recognition service; recording never starts
implicitly.

Chronicle integration follows the same capture-before-control boundary. The
composer may preview recent frames only after Chronicle screen context has been
enabled. A person selects a frame and may edit the OCR excerpt before saving.
The durable Capture keeps only bounded source attribution; it does not copy the
screen image, local image path, OCR digest or full OCR. See ADR-0029.

The organized lists do not automatically create governed WorkItems. A GTD next
action is a person's statement of intended work; a governed WorkItem is an
organization-bound execution request carrying capability, performer and
approval semantics. Promotion between them requires a separate explicit design.

## Consequences

- A person can record without deciding what the record means and without model
  egress.
- Inbox, Next Actions, Projects, Waiting For, Someday/Maybe, Reference and a
  weekly-review projection are available from the same immutable source.
- Capture records remain ordinary local state and therefore inherit its backup,
  retention and secure-erasure limitations.
- Voice availability and privacy depend on the browser implementation; the text
  path is the canonical function.
- This ADR deliberately does not add automatic extraction, prioritization,
  reminders, calendar scheduling, or Kanban execution.

## Implementation

- `src/cloud/itonami/app/capture.clj`
- `GET/POST /api/captures`
- `GET /api/captures/chronicle`
- `POST /api/captures/chronicle/capture`
- `POST /api/captures/{id}/clarify`
- `POST /api/captures/{id}/review`
- `POST /api/captures/{id}/complete`
- `POST /api/captures/{id}/reopen`
- Capture view in `web.clj` and `interaction.js`
