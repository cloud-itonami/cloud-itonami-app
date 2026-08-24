# ADR-0074: Projects are conversation context, not ambient authority

Status: accepted

## Context

The titlebar Project selector had two unrelated meanings. It partitioned Chat
history, but also changed the Project board and the destination used by Sites.
That made a conversational choice look and behave like a global workspace
switch. Bots had no equivalent context attachment at all.

## Decision

A Project may be attached to a Chat conversation or to a Bot's direct
conversation as optional, read-only reference context. “No context” is valid.
The provider receives a bounded projection of Project metadata, repositories,
and at most twenty issue summaries, labelled as untrusted reference data.

Selecting context does not grant tools, accounts, filesystem access, a coding
workspace, write approval, or permission to modify the Project. Bot context is
stored separately from every authority field. Chat history remains partitioned
by its selected Project so changing context reveals that conversation's own
thread.

Project boards and Sites keep an independent, screen-local Project selection.
Changing conversation context therefore cannot silently change an operational
target or a save destination. Mutating APIs continue to require an explicit
Project identifier, origin validation, CSRF validation, and their existing
session gate.

## Consequences

The titlebar selector can honestly say “context”: it appears only for the
conversation surface it affects. A selected Bot remembers its own Project
context, while another Bot may select a different one. Existing Chat users are
migrated once from the former selector value; after that, conversation context
and Project-screen selection use distinct local keys.

The context is a live bounded projection, not a full Project archive or an
authority-bearing handle. Adding Project files or instructions later requires
another explicit admission and size/provenance decision.
