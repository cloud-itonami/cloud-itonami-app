# ADR-0088: Hermes Bots migrate as one credential-free bundle

Status: accepted (amended 2026-09-03: permission carry-over, owner
instruction「権限・ツール なども引き継いで」)

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
| Source tool permission and approval state | Hermes runtime authority | no artifact | `rebind-required`; never becomes an Itonami grant — except through the explicit carry-over below |
| Itonami Bot identity and grants | no source equivalent | stable profile binding | one inert Bot is created per profile by default; with carry-over, grants reflect the observed source authority |

## Permission carry-over (amendment, 2026-09-03)

Owner instruction:「権限・ツール なども引き継いで」. The default stays inert;
`bots import hermes --provision true --carry-over-permissions true` converts
the source profile's OBSERVED tool authority into destination grants.

What the source actually grants is measured, not assumed (2026-09-03): Hermes
ships every built-in toolset enabled (terminal, files, browser, computer) and
records only the dangerous-pattern approvals a person granted permanently in
`config.yaml` `command_allowlist`. Measured values: the itonami profile
carries `script execution via -e/-c flag` and `recursive delete`; codinator
carries `overwrite project env/config file`.

The mapping, in `hermes-migration/carry-over-grants`:

- non-empty `command_allowlist` → `writes? / coding? / virtual-shell? /
  goal?` ON — the allowlist is evidence the profile ran commands, and each
  destination grant stays bounded by its own governor (workspace root
  admission, approval holds on dangerous commands)
- any observed permission evidence → `browser? / computer?` ON — the source
  toolsets ship enabled, and the destination grants carry the same bounds
  (isolated browser, bounded Computer Use)

What intentionally does NOT cross:

- `omakase?` — a per-pattern approval is not a general delegation. The
  destination reproduces the same shape differently: writes execute,
  dangerous ones hold for approval. Carrying omakase across would upgrade a
  per-pattern grant into a general one.
- `peers?` — no source equivalent; granting it would invent authority.
- the allowlist entries and plugin names themselves — recorded on the Bot
  binding as evidence (`:source-permission-evidence`,
  `:unmapped-authority`). The destination has no pattern-allowlist
  mechanism; inventing one that "works" would be theatre.
- credentials — unchanged; never part of this surface.

Empty evidence yields empty grants: carry-over carries what was observed,
never a default the source did not state. Provision reports
`ready-carry-over` and activation
`interactive-ready-source-tool-authority-carried-over`, and
`:safety.grants-carried-over` records that the reviewed flag was given.

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
