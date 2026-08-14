# ADR-0050: Grok is a reviewed model provider, not a second authority

Status: accepted and landed, 2026-08-14

## Context

Cloud Itonami Bots already have the agent properties that matter: a durable
identity and transcript, bounded turns, connector grants, one-effect-at-a-time
execution, and a human approval hold for writes. What they lacked was a way to
bind one Bot to a model provider and model. All Bots silently followed the
deployment-wide default.

xAI exposes Grok through both Chat Completions and Responses APIs. It also
offers server-side tools. Treating those server-side tools as interchangeable
with Cloud Itonami connector tools would move effects outside the local
admission, approval, account-selection, and audit boundary.

## Decision

Grok R1 is an `:xai` model provider over
`POST https://api.x.ai/v1/chat/completions`.

- A Bot may store `:bot/provider-id` and `:bot/model`. Older Bots with neither
  field continue to follow deployment routing.
- A stored provider id is only a preference. Every turn resolves it through
  `policy/select-provider`; enabled, reviewed, cloud-egress, HTTPS, and exported
  credential gates remain authoritative.
- The shipped xAI provider is disabled and unreviewed. It appears in the Bot
  picker only after the deployment admits it.
- xAI receives the Bot id in `x-grok-conv-id` for request correlation. It is an
  opaque local identifier, not a user DID, account id, token, or transcript.
- Agent turns set `parallel_tool_calls` to false. The response boundary also
  rejects more than one tool call, rather than silently executing the first.
- The configured reasoning effort and output-token limit are explicit. Grok's
  visible answer must have room after reasoning.

Chat Completions is intentional for R1. Responses API state, xAI web/X/code
search, file collections, and remote MCP tools are not admitted by this ADR.
They require separate data-retention and authority designs before use. All
tools visible in this implementation remain Cloud Itonami's local connector or
isolated-browser tools, and all effects keep the existing approval semantics.

## Evidence and boundary

Contract tests cover xAI request shape, the correlation header, per-Bot routing,
provider/model rendering, and fail-closed multiple tool calls. The full JVM
suite and JavaScript syntax check are the release gate.

A green contract suite does not prove a live xAI account. Live qualification
additionally requires an operator-reviewed deployment with `XAI_API_KEY` and a
successful real Bot turn; absent that evidence this is implemented and tested,
not claimed live.
