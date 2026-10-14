# ADR-0143: Itonami agent is the Hermes agent; Itonami desktop is the bots-first Hermes desktop

Status: accepted (owner instruction 2026-10-14:「hermes agent = itonami agent /
hermes desktop = itonami desktop（bots 中心）となるように整理して、itonami agent の
bots は hermes の registry, profile, api と互換性を持つ様にして」)

Date: 2026-10-14

## Context

Three things had grown side by side:

- **Hermes Agent** (upstream, `~/.hermes`): profiles, an API server, a skills/agents
  registry, and the Hermes desktop (fathah/hermes-desktop), which Kotoba forked as
  Kotoba desktop.
- **Cloud Itonami**: Bots as the workspace (ADR-0097), a Hermes-shaped transport
  for them (`docs/hermes-bot-mode-compatibility.md`), a Hermes profile import
  (ADR-0088), and its own desktop (the web surface in an application window).
- **itonami-registry**: templates in the Hermes registry layout.

Each piece was partly Hermes-compatible, and nothing said which is which.

## Decision

### Names

| Hermes | Cloud Itonami | Notes |
|---|---|---|
| Hermes agent (API server + profiles) | **Itonami agent**: the Itonami resident (port 1338, JVM or JVM-free) | speaks the Hermes API |
| Hermes profile | **Bot** (one-to-one; an imported profile keeps its id) | ADR-0088 import, this ADR's export |
| Hermes desktop | **Itonami desktop**: the Cloud Itonami app, Bots first | this ADR |
| Hermes registry (fathah/hermes-registry) | read by the Marketplace beside **itonami-registry** | same layout, pinned + verified |

The Itonami desktop is the one desktop. Kotoba desktop (the Hermes desktop fork)
is transitional. It still holds the profile-distribution work (gateway-hy shard
host, placement, kagi fleet secrets), and until that moves it can reach an Itonami
agent as a remote Hermes, since the Itonami agent answers the Hermes API. New
desktop features land in the Itonami desktop.

### Bots first

A Hermes desktop is organized around chats over one agent; the Itonami desktop is
organized around Bots: each Bot is a named colleague with its own conversation,
profile, tools and accounts (ADR-0034, ADR-0097). A Hermes profile appears as a
Bot, not as a hidden configuration of a single agent.

### Compatibility contract

**API.** The Itonami agent answers the upstream Hermes API wire shapes.

| Upstream endpoint | Itonami agent |
|---|---|
| `GET /health` | adds `status`/`platform`/`runtime` to the resident's own health body |
| `GET /v1/health` | `hermes-surface/health` (new) |
| `GET /v1/capabilities` | lists only implemented endpoints (new) |
| `GET /v1/models` | every visible Bot as a model id = its profile id (new) |
| `POST /v1/chat/completions` (+stream) | the last user message is a Bot turn; `model` picks the Bot (new) |
| `GET /v1/skills` | profile skills, imported skills, enabled tools (new) |
| `/api/profiles`, `/api/sessions/*`, `/v1/runs/*`, `/p/<profile>/` | existing (`hermes_gateway`, `hermes_compat`) |
| `GET /api/profiles/{profile}/export` | Bot → Hermes profile (new, Itonami extension) |
| `/v1/responses`, `/api/jobs*`, `/api/sessions/{id}/fork`, `/api/sessions/{id}/model`, `/api/model/options`, `/v1/toolsets`, artifacts, browser control | not yet; not advertised in capabilities |

**Profile.**

- Hermes → Bot is ADR-0088 (credential-free bundle, `rebind-required`).
- Bot → Hermes is `GET /api/profiles/{profile}/export`. It returns SOUL.md and `skills/<id>/SKILL.md` from the Bot's persistent profile (or the brief), plus profile.yaml and config.yaml (murakumo).
- USER.md and MEMORY.md are about the owner and are listed as `not_exported`.
- Accounts, connector grants, the wallet signer and approval policy never leave; they are listed as `rebind_required`.

**Registry.**

- The Marketplace Bot tab offers the agents of itonami-registry and of upstream fathah/hermes-registry.
- Each is generated from one registry commit with every folder checksum verified.
- Hermes registry ids are qualified `hermes-<id>`.
- Every offer installs inert.

### Authority is unchanged

The Hermes surface changes transport and envelopes only. Bot ownership, tool grants,
held writes and approval cards, provider admission (murakumo only, no fallback) and
the turn ledger stay with `cloud.itonami.app.bots`. An OpenAI client choosing a Bot
by `model` gets that Bot's authority, never more.

## Consequences

- A Hermes client (the Hermes desktop or CLI in remote mode, an OpenAI SDK) can use Itonami Bots without an adapter.
- One capability list is honest about the gaps: features that are not implemented are `false` and their endpoints are absent.
- Kotoba desktop's future is integration into the Itonami desktop. Its profile-distribution machinery should move to the Itonami agent's side (a later ADR).

## Not yet

`/v1/responses`, Hermes cron jobs (`/api/jobs*`) over Bot schedules, session fork and
model lock, `/api/model/options`, toolsets, artifacts and browser control; an
`itonami bots export hermes` CLI over the export route; the registry's skills, MCP
definitions and workflows in the Marketplace.
