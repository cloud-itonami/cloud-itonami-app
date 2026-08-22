# ADR-0069: Bots own the conversation; context capture is ambient

**Status:** accepted — 2026-08-22

## Context

The desktop exposed Chat, Rooms, Capture, and Memory as peer destinations.
That navigation described implementation stores rather than the work a person
wants to do. A direct conversation and a room also looked equally actionable,
although only a Bot has tools and authority. Chronicle was available, but its
disabled-by-default profile and separate surfaces made device context feel
like a manual import step.

Periodic screenshots with OCR are not the same mechanism as a semantic app
snapshot. They can recover visible words and the foreground application, but
not document structure, selection, or an application event stream. The UI must
not imply that stronger claim.

## Decision

**Bots is the only top-level conversation destination.** Its thread is the
place where a person gives work to a Bot. Bot-to-Bot rooms are an attributed,
read-only trail opened from Bots. The desktop provides no room create or send
control; a room cannot become a second tool-bearing command surface.

Capture is ambient for a new local profile:

- local memory, periodic screen context, and bounded Bot tool receipts start
  enabled;
- the authenticated bootstrap materializes that profile so the background
  scheduler can observe it;
- Settings shows each control, permission state, recent captures, immediate
  capture, and deletion;
- legacy `#/chat`, `#/rooms`, and `#/capture` links resolve to Bots, while
  `#/memory` resolves to Settings.

Implicit device context is attached only when the selected Bot provider is
local. It is labelled untrusted context and cannot supply instructions. No
image, credential, or complete tool output is stored in a Bot transcript or
silently sent to a cloud model. Tool receipts are centrally recorded with the
Bot and tool names and the existing bounded Chronicle projection.

The old GTD/freewriting Capture API remains compatible, but Capture is not a
primary desktop tab. A later semantic snapshot adapter may use Accessibility
APIs or application integrations; that would be a separate capability with
its own permission and provenance.

## Consequences

- Navigation matches the authority model: people ask Bots; rooms explain what
  Bots said to one another.
- Context is useful without a dedicated workflow, and is still reversible in
  Settings.
- Default-on capture increases the importance of the local-only boundary and
  explicit macOS Screen Recording status.
- Current context quality is OCR plus foreground-app and bounded tool receipts,
  not a claim of parity with semantic app-content capture.

## Verification

- Chronicle tests prove default-on, per-user isolation, explicit disable,
  scheduler-visible profile materialization, bounded retention, and deletion.
- Bot tests prove a local provider receives device context and a cloud provider
  does not.
- Web tests prove Bots is the only conversation destination, room controls are
  read-only, legacy routes converge, and capture controls live in Settings.
