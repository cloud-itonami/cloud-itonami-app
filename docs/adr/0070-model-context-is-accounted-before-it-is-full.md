# ADR-0070: Model context is accounted before it is full

**Status:** accepted — 2026-08-22

## Context

Bots previously carried the system message, goal, and at most 24 recent
messages. That measured fallback prevented slow repeated prompts, but it also
discarded usable context from a model with a declared 32K window. Replacing it
with one global token number would be unsafe: a Bot selects its model, aliases
can move, and local Ollama tags expose family-specific limits.

NousResearch Hermes Agent provides a useful implementation reference. Its
context engine defaults preflight compaction to 75% of the model window, and
its preflight estimate includes the system prompt and tool schemas. Hermes
protects the head and a recent tail, compacts complete assistant/tool
exchanges, and keeps user messages as source material. Its optional per-turn
micro-compaction is off by default because rewriting an already-sent prefix on
every turn defeats provider prompt caching; its proactive pruning requires a
meaningful reclaim for the same reason.

Sources inspected:

- <https://github.com/NousResearch/hermes-agent/blob/main/agent/context_engine.py>
- <https://github.com/NousResearch/hermes-agent/blob/main/agent/turn_context.py>
- <https://github.com/NousResearch/hermes-agent/blob/main/agent/context_compressor.py>
- <https://github.com/NousResearch/hermes-agent/blob/main/docs/micro-compaction.md>

## Decision

Context belongs to the selected model, not to the Bot type. The maximum window
is resolved in this order:

1. exact operator configuration for the selected model;
2. live provider metadata, cached for five minutes (`/api/show` for Ollama or
   `context_length` from an OpenAI-shaped `/models` entry);
3. the measured 24-message fallback when the provider does not disclose a
   limit.

The shipped Murakumo models currently declare 32,768 tokens, including the
`murakumo-main` alias. Repointing that alias therefore includes updating its
metadata in the same operator configuration change. Grok 4.6 declares its
current official 500,000-token window. Dynamic local model limits are read
instead of inferred from their names. Ollama requests also set `num_ctx` to
that discovered value; accounting a theoretical maximum without allocating it
in the local runtime would otherwise silently truncate the context.

For a known window, Cloud Itonami reserves requested output, tool schemas, and
chat-template safety tokens. Below 75% it sends the stable transcript unchanged.
Above 75% it performs one deterministic batch compaction:

- system and opening goal stay verbatim;
- user messages stay verbatim and in order;
- recent complete tool-call/result units stay verbatim;
- older assistant/tool exchanges become bounded `REFERENCE ONLY` markers;
- raw old tool bodies remain recoverable from the durable run and are not
  copied into the compacted prompt;
- a final hard-window bound never sends an orphan tool result;
- an opening instruction that alone exceeds the available window fails before
  the provider call with `:agent/context-overflow`.

The model phase and saved run carry window, threshold, estimated prompt, and
whether compaction occurred. This makes pressure observable without storing a
second lossy conversation as authority.

## Consequences

- Every Bot selecting a model with known metadata can use that model's maximum
  window; this is independent of Bot identity and per-Bot model selection.
- Compaction is episodic, preserving prompt-cache prefixes between pressure
  events rather than paying an auxiliary model call on every turn.
- Deterministic summaries are less semantically rich than an LLM-generated
  rolling summary, but are fast, auditable, credential-redacted, and cannot
  fail halfway through a durable transcript rewrite.
- Unknown OpenAI-compatible models keep the conservative measured fallback
  until their operator or endpoint supplies exact metadata.

## Verification

- Config tests pin every shipped selectable model's declared window.
- Provider tests prove direct metadata parsing and a real Ollama `/api/show`
  discovery path.
- Bot tests prove 32K history is no longer cut at 24 messages, the 75% preflight
  boundary fires before overflow, user messages and recent tool pairs survive,
  old raw tool bodies do not, and the final estimate stays within the reserved
  model window.
