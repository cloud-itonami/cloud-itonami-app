# ADR-0026: Chronicle is opt-in, device-local memory

Status: Accepted

## Context

Cloud Itonami needs continuity across chats and optional awareness of work shown
on the Mac screen. Screen capture, OCR and model context all cross a sensitive
privacy boundary. Agent sessions must not inherit a human user's ambient screen
history, and text found on screen may contain prompt-injection instructions.

## Decision

Chronicle is a per-user, device-local capability with three independent,
default-off switches: chat memory, screen context, and tool-assisted memory.

- Screen capture requires macOS Screen Recording permission and an explicit
  user opt-in. A daemon captures at most once per minute.
- Frames and OCR stay below the configured Cloud Itonami data directory in a
  user-hashed `0700` folder; image files are `0600`. Frames expire after six
  hours and are also capped at 360.
- OCR runs locally with Tesseract. OCR text is labelled untrusted and inserted
  only as optional reference context to a local model; it can never supply
  model instructions or cross into a cloud-provider request.
- Derived memories are scoped by user and optionally carry project and session
  identifiers. Agent sessions cannot read or write personal Chronicle data.
- “Delete local memory” removes frames, OCR and derived memories, and resets
  Chronicle switches. It deliberately does not delete chat history.
- The UI exposes permission state, capture state, counts, recent derived items,
  manual capture, the macOS settings shortcut, and destructive deletion.

## Consequences

Chronicle is useful only after consent and may show no screen context until the
OS permission is granted. Local OCR quality depends on installed language data.
The separation from chat history gives deletion predictable scope and avoids
silently erasing the conversation record.
