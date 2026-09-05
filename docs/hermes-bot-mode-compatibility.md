# Hermes Agent Bot Mode compatibility

## Full profile migration

`itonami bots import hermes` now uses the resident API's
`cloud.itonami.app.hermes-bot-migration.v2` manifest. It inventories the
default and every named Hermes profile together. `--stage true` stores
Hermes-native credential-free profile archives and forced-redacted JSONL
session exports under one migration id; the CLI posts the preview manifest
back unchanged.

Credentials, provider/account bindings and source tool authority are listed as
`rebind-required`. Staging does not create or enable a Bot and does not widen a
grant. See ADR-0088.

Cloud Itonami exposes a Hermes-shaped transport for its resident Bots. The
identity mapping is one-to-one:

| Hermes | Cloud Itonami |
|---|---|
| profile | Bot id |
| canonical `Bot Chat` session | that Bot's durable conversation |
| run | durable Bot turn |
| steer | queued follow-up at the next safe model/tool boundary |
| stop | native cancellable Bot turn |
| approval response | native held approval card |
| `message_agent` | isolated asynchronous teammate turn and attributed reply; peer chains stop at depth 2 |

## HTTP surface

All routes require the same application session as `/api/agent-bots`. A CLI
uses `Authorization: Bearer`; cookie requests retain Origin and CSRF checks.
The optional `/p/<profile>/` prefix follows Hermes gateway multiplexing.
`default` resolves `:bots :hermes :default-bot-id` when configured, otherwise
the first pinned visible Bot, then the oldest visible Bot.

| Method | Route | State |
|---|---|---|
| GET | `/api/profiles` | implemented discovery extension |
| GET/POST | `/api/sessions` | canonical Bot Chat list/create-existing |
| GET/PATCH/DELETE | `/api/sessions/{id}` | read; fixed-title patch; canonical deletion refused |
| GET | `/api/sessions/{id}/messages` | implemented Hermes message projection |
| POST | `/api/sessions/{id}/chat` | implemented synchronous Bot turn |
| POST | `/api/sessions/{id}/chat/stream` | implemented SSE through a run |
| POST | `/v1/runs` | implemented asynchronous start (`202`) |
| GET | `/v1/runs/{id}` | implemented; terminal status survives process restart |
| GET | `/v1/runs/{id}/events` | implemented live SSE (`data: <json>`) |
| POST | `/v1/runs/{id}/approval` | implemented through Itonami approval authority |
| POST | `/v1/runs/{id}/steer` | implemented through bounded follow-ups |
| POST | `/v1/runs/{id}/stop` | implemented through native cancellation |

The `itonami hermes ...` CLI covers profile/session discovery and run start,
status, steer, stop and approval.

## Authority and compatibility boundary

Compatibility does not replace Itonami semantics. It does not let a Hermes
client create or disable a Bot, widen tools/accounts/workspace access, bypass
provider admission, approve a write without the Bot's existing human-enabled
delegation, or turn a transport profile into wallet/deploy authority.

Hermes arbitrary multi-session creation/forking and generic cron-job mutation
do not have an honest one-to-one mapping to a canonical Itonami Bot. They are
not silently emulated. Itonami routines remain demonstrated tool traces, and
Bot creation/grant changes remain on their existing human surfaces. This is a
Bot Mode compatibility claim, not a claim that every generic Hermes API-server
resource is interchangeable.
