# ADR-0003: Stream `/v1/chat/completions`, and defer its response headers

## Status

Accepted.

## Context

`GET /v1/models` and `POST /v1/chat/completions` are the OpenAI compatibility
slice, and they are the only routes that do not require a session — the
loopback bind is what protects them. That makes them the app's one surface a
third-party client can use as-is.

The completion endpoint was non-streaming, and the OpenAI clients worth being
compatible with send `stream: true` by default. A client that asks for a stream
and receives a single JSON body either hangs waiting for frames or fails
parsing, so the compatibility claim held for almost none of them in practice.

Streaming itself was not missing: `service/run-chat-stream!` and
`provider/chat-stream!` already stream both `:ollama` and `:openai-compatible`
providers, and `POST /api/chat/stream` already serves the first-party UI as
NDJSON. What was missing was the OpenAI wire format on the compatibility route.

## Decision

Serve `stream: true` as Server-Sent Events in the `chat.completion.chunk`
format: a role chunk, one chunk per provider delta, a `finish_reason: "stop"`
chunk, the usage-only chunk when `stream_options.include_usage` asked for it,
then `data: [DONE]`. Chunks repeat the completion id, and it is the same id the
store records for the turn, so a streamed completion is traceable to its
persisted response rather than carrying a transport-local identifier.

**Write the response headers on the first frame, not when the request
arrives.** Once `200` and `text/event-stream` are on the wire the status cannot
change, so any failure has to be reported inside a stream the client already
believes is succeeding. Deferring keeps the exchange untouched until something
is actually there to send, which leaves failures before the first delta — a
denied provider, a refused local model — on the handler's existing `ex-data`
status mapping. A cloud provider behind a shut gate is a `403` under
`stream: true` exactly as it is without it.

After the first delta the status is spent, and a failure there is an `error`
frame followed by `[DONE]`, with no `stop` chunk. That contract is weaker and
it is the honest one available: the alternative is closing a `200` stream on a
truncated answer, which a client cannot distinguish from a short success.

## Consequences

- Editors and agent tools that speak the OpenAI API work against
  `http://localhost:1338/v1` without per-client accommodation.
- The provider policy is unchanged. Streaming reuses `run-chat-stream!`, so a
  streamed turn cannot reach a provider a non-streamed turn could not, and the
  fail-closed gate is enforced before the first byte rather than mid-stream.
- `send-openai-stream!` is longer than the header-first version it replaces,
  and the deferral is the reason. Sending the headers up front is the obvious
  simplification and it silently converts every pre-stream refusal into a
  `200` empty answer.
- `test/cloud/itonami/app/openai_compat_test.clj` reads the frames over real
  HTTP — content type, chunk order, `[DONE]`, and the `403`-under-`stream`
  case — because a wire format is not testable through the Clojure API.
- Function calling, embeddings and the Responses API remain unimplemented, and
  no part of this change claims otherwise.
