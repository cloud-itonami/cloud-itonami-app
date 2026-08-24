# ADR-0075: Conversation context is an ordered set of references

## Status

Accepted — 2026-08-24

## Decision

A Chat session and a Bot each carry an ordered `context-refs` vector. A
reference has only a `kind` (`project`, `folder`, `document`, or `dataset`) and
a `target`. Up to twelve references may be selected in one conversation.

The UI uses one shared Context drawer. The operational Project picker remains
separate: choosing a Project for boards, Git, or Sites does not silently add it
to a conversation, and choosing conversation Context does not change an
operational destination.

Every reference is resolved through its source's existing read boundary when
it is saved and again immediately before a model turn. Provider input is
bounded to 12,000 characters per source and 48,000 characters in total. Each
resolved source produces a receipt containing its kind, target, visible label,
version, SHA-256 digest, and transmitted character count.

Context is reference data, never authority. The resolver cannot return tools,
accounts, credentials, filesystem handles, workspaces, grants, or write
permission. Bot tool admission remains solely in the existing Bot authority
path. Instructions embedded in a selected source are explicitly untrusted.

The former singular `context-project-id` remains a compatibility input. It is
normalized to one Project reference and exposed as the first selected Project
for older clients, but `context-refs` is the durable source of truth.

## Consequences

- One conversation can combine a Project, a Drive folder, and selected data.
- Chat sessions no longer split their transcript merely because a Project
  Context changed; the Context set belongs to the session.
- A revoked share stops resolving on the next turn even if its reference is
  still saved.
- Receipts establish what bounded snapshot was supplied to a turn without
  retaining another unrestricted copy of the source.
- Adding URLs, connectors, uploads, or external collections later means adding
  a new resolver behind the same bounded, read-only contract.
