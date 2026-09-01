# ADR-0088: Hermes Bots migrate as one credential-free bundle

Status: accepted

Date: 2026-09-01

## Context

The former `bots import hermes` read only `~/.hermes/cron/jobs.json` and
produced a `loop-yakuwari` role proposal. It did not migrate named profiles,
persona, configuration, model references, memories, skills, plugins, scripts,
MCP configuration, sessions, messages or tool results. It also had no HTTP
contract: the CLI read the source filesystem itself.

Calling that a Hermes Bot import was therefore too broad. It was a scheduled
job-to-role proposal and remains available as schema
`cloud.itonami.app.bot-import.v1` for that narrow conversion.

## Decision

The full migration contract is
`cloud.itonami.app.hermes-bot-migration.v2`. The HTTP API is the source of
truth and the CLI sends and receives that exact manifest without translating
it.

The source-native Hermes exporters plus the destination projection produce
three content-addressed artifacts for every profile:

1. `hermes profile export` produces the credential-free portable profile
   archive and force-redacts secret-shaped text. Opaque SQLite/database files
   are removed from the staged archive after export because Hermes explicitly
   cannot scrub secrets inside binary files.
2. `hermes sessions export --format jsonl --redact` produces every session,
   message, run record and tool result in a neutral, redacted stream.
3. `hermes-runtime-context` projects the bounded top-level persona and memory
   files used by the destination inference loop. It is explicitly untrusted
   context and cannot grant tools, accounts or approval.

The default profile and every directory under `profiles/` are included in one
migration id. Preview inventories all portable source files and records one
source revision over the control plane (persona, configuration, skills, cron
and other non-volatile files). Stage accepts that revision as an optimistic
lock and rechecks it before and after export. Live session databases, logs and
caches are not control-plane lock inputs: Hermes's session exporter takes the
consistent history snapshot, and the finished artifacts are content-hashed.
Stage discards the temporary bundle if the control plane changed.

The local resident API is:

- `POST /api/agent-bots/imports/hermes/preview`
- `POST /api/agent-bots/imports/hermes/stage`
- `POST /api/agent-bots/imports/{migration-id}/provision`
- `GET /api/agent-bots/imports`
- `GET /api/agent-bots/imports/{migration-id}`

`itonami bots import hermes` calls preview. Adding `--stage true` posts the
returned manifest unchanged to stage. `--provision true` stages and then
provisions every profile. Filesystem import is refused when the server is not
loopback-only.

## Information matrix

| Information plane | Hermes source | v2 artifact | Itonami treatment |
|---|---|---|---|
| Profile identity and persona | profile id, `SOUL.md`, prompts/rules | profile archive + runtime context | source id remains a Hermes API alias and context enters the Bot prompt |
| Runtime configuration | `config.yaml`, model/provider references, desktop settings | profile archive | retained as data; destination admission is still required |
| Memories and knowledge | `MEMORY.md`, `USER.md`, `memories/`, `knowledge/`, preferences | profile archive | retained |
| Skills, scripts, plugins and MCP | profile directories and config | profile archive | retained as source material; no capability is granted by presence |
| Cron, schedules and health | `cron/`, job state | profile archive | retained; activation remains stopped until review/provision |
| Sessions, messages, runs and tool results | Hermes session store | redacted JSONL session export | source session ids/messages remain readable; a bounded latest user/assistant transcript seeds live chat |
| Provider/account credentials | `.env`, `auth.json`, OAuth/API tokens | no artifact | `rebind-required`; values never migrate |
| Source tool permission and approval state | Hermes runtime authority | no artifact | `rebind-required`; never becomes an Itonami grant |
| Itonami Bot identity and grants | no source equivalent | stable profile binding | one inert Bot is created per profile; destination grants remain empty |

## Safety and lifecycle

Preview and stage do not mutate Hermes. Stage writes a new immutable bundle
directory below the Itonami data directory and a durable manifest record.
Provision creates an idempotent destination Bot for every staged profile, with
writes, browser, computer, peers, coding, shell, goal mode, omakase, accounts
and tools all off. Interactive inference is ready immediately; source cron is
not activated and every external capability remains a later Itonami grant.

The API accepts an agent session only on the local resident server. A staged
manifest, not request-supplied Bot attributes, determines provisioning. The
path can create only those inert imported Bots; it cannot copy credentials,
approve authority or widen a grant.

## Consequences

"All information" now means all portable operational and user-authored
profile data plus complete redacted session history. It explicitly does not
mean credentials or authority. Those omissions are visible per profile rather
than silently dropped.

The old v1 report and the v2 bundle have different purposes. v1 can propose a
role from a cron objective. v2 preserves a whole Hermes Bot for staged review
and can bind it to an interactive Itonami Bot. The provision result includes
an explicit capability-denominator compatibility matrix: execution-model,
semantic-system, zero-adjustment runtime, core API and exact-model coverage.
