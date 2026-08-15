# ADR-0052: Omakase is a human-set delegation, not a wider grant

**Status:** accepted — 2026-08-15

## Context

Bots could already edit one admitted Git workspace, run a networkless per-Bot
shell, and send through an explicitly bound Gmail account. Every write stopped
on a browser approval card. That is a useful default, but it prevents a CLI,
MCP client, or another agent from completing an owner-delegated coding or mail
task while the person is away.

Letting an agent edit the Bot grant would solve the wait by also letting it
manufacture authority. Letting omakase cover every write would silently include
browser clicks and Calendar or Drive mutations that the owner did not delegate.

## Decision

A Bot stores a boolean `:bot/omakase?`. Only the existing human
`/api/bots` create/update surface may change it. The agent surface
`/api/agent-bots` can list owned Bots, read their transcript, submit a task,
cancel a run, and decide an already-held eligible operation. It has no
create/update route.

Omakase replaces the approval wait only for:

- workspace writes and local Git commits inside the exact admitted Git root;
- commands in that Bot's networkless OCI shell; and
- `gmail_send_message` through an explicitly admitted, bound account.

It does not add a tool, account, workspace, network path, credential, browser
domain, or remote Git operation. Browser writes and all other connector writes
still require a human approval even when omakase is enabled.

Every automatic execution writes the same approval card into the durable
conversation, already decided with `decision-mode=omakase`,
`decided-by=bot`, and a timestamp. An external agent deciding a previously
held eligible card records `decided-by=agent-session`. Admission is evaluated
before either path.

This narrows the blanket approval statements in ADR-0050 and ADR-0051; normal
mode retains their human hold unchanged.

## Verification

Contract tests prove that an admitted Gmail send executes immediately and leaves
an answered receipt, that a human-enabled Bot accepts an agent-session decision,
and that Calendar and browser writes are refused. Route-registry tests prove
the Bot configuration route remains human-only. CLI and MCP adapter tests prove
task submission reaches the resident HTTP owner of the store with a bounded
long-running timeout.
