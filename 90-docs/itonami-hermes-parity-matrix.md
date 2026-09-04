# hermes CLI ↔ itonami CLI parity matrix (2026-09-04)

Source: hermes_cli/commands.py (101 CommandDefs) mapped onto bin/itonami +
the hermes-compat server surface (server.clj, hermes_compat.clj,
cli-aliases.edn).

Status legend:
  ok             live in itonami today (REPL slash or top-level command)
  gap-client     server route EXISTS; only CLI surface missing (stage-4 work)
  gap-server     needs new server capability (decision required per item)
  gap-by-design  intentionally absent — canonical Bot Chat / ADR-0088
  n/a            host-only, TUI-only or gateway-only; meaningless for itonami

Counts: ok 17 / gap-client 16 (4 landed 2026-09-04, 3d206c9) / gap-server 18 / gap-by-design 6 / n/a 42

Stage-4 landed (2026-09-04, commit 3d206c9, released): /whoami
(GET /api/operator — profile + fleet summary), /model
(GET/POST /api/bots/model-routing; human-session 403 renders a hint),
/version (GET /api/update — installed version + update status), /context
(GET /api/session/context/sources — conversation-context catalog).
Remaining gap-client: retry prompt handoff timestamps verbose approvals
tools skills bundles browser plugins image.

## ok

| hermes | category | itonami equivalent |
|---|---|---|
| /history | Session | /history |
| /stop | Session | /stop |
| /approve | Session | /approve |
| /deny | Session | /deny |
| /agents | Session | /bots |
| /steer | Session | /steer |
| /goal | Session | run goal flag (itonami run --goal) |
| /plan | Session | prompt-only, works via chat |
| /status | Session | /status + itonami status |
| /profile | Info | /profile + itonami profile list |
| /sessions | Session | itonami sessions |
| /diff | Info | bot git tools in-workspace |
| /skin | Configuration | /skin + skins/*.edn |
| /commands | Info | itonami commands |
| /help | Info | itonami (no args) + /help |
| /usage | Info | token usage shown per turn |
| /quit | Exit | exit |

## gap-client

| hermes | category | itonami equivalent |
|---|---|---|
| /retry | Session | re-send last message |
| /prompt | Session | $EDITOR compose |
| /handoff | Session | bots handoff route exists |
| /context | Info | /context — GET /api/session/context/sources |
| /whoami | Info | /whoami — GET /api/operator (profile + fleet summary) |
| /model | Configuration | /model — GET/POST /api/bots/model-routing |
| /timestamps | Configuration | REPL display |
| /verbose | Configuration | tool progress display |
| /approvals | Configuration | work-governance approval-policies route |
| /tools | Tools & Skills | bot tool routes |
| /skills | Tools & Skills | bot skills (hermes import, ADR-0088) |
| /bundles | Tools & Skills | skill bundles |
| /browser | Tools & Skills | bot browser tools |
| /plugins | Tools & Skills | hermes import |
| /image | Info | workspace upload |
| /version | Info | /version — GET /api/update |

## gap-server

| hermes | category | itonami equivalent |
|---|---|---|
| /save | Session | hermes sessions export→itonami import (ADR-0088) |
| /pause | Session | bots cancel / fleet ops |
| /bg | Session | no background session surface |
| /btw | Session | side question |
| /queue | Session | no queue surface |
| /refine | Session | no memory surface |
| /review | Session | spawn review subagent |
| /subgoal | Session | goal flag only |
| /resume | Session | session resume |
| /config | Configuration | server-side |
| /personality | Configuration | bot identity server-side |
| /yolo | Configuration | approval policy — cards are the model |
| /reasoning | Configuration | server-side model config |
| /fast | Configuration | server-side |
| /memory | Tools & Skills | memory provider — runtime-context import |
| /curator | Tools & Skills | skill lifecycle server-side |
| /kanban | Tools & Skills | server work-governance |
| /insights | Info | usage insights |

## gap-by-design

| hermes | category | itonami equivalent |
|---|---|---|
| /new | Session | n/a — Bot Chat is canonical forever-chat (ADR-0088) |
| /clear | Session | n/a — canonical session |
| /undo | Session | n/a — server owns history |
| /title | Session | n/a — server rejects session PATCH |
| /branch | Session | n/a — canonical session |
| /compress | Session | n/a — server context mgmt |

## n/a

| hermes | category | itonami equivalent |
|---|---|---|
| /start | Session | gateway ping ack |
| /topic | Session | gateway-only |
| /redraw | Session | TUI |
| /worktree | Session | host-side |
| /rollback | Session | host-side checkpoints |
| /snapshot | Session | host-side |
| /heartbeat | Session | cron resident (ADR-0053) |
| /loop | Session | cron (hermes cron ≈ itonami cron residents) |
| /moa | Session | no MoA |
| /egress | Session | host proxy |
| /sethome | Session | messaging |
| /codex-runtime | Configuration | n/a |
| /statusbar | Configuration | TUI |
| /battery | Configuration | TUI |
| /focus | Configuration | TUI |
| /footer | Configuration | messaging |
| /indicator | Configuration | TUI |
| /voice | Configuration | n/a |
| /wake | Configuration | n/a |
| /busy | Configuration | n/a |
| /toolsets | Tools & Skills | n/a — itonami has no toolset split |
| /pet | Tools & Skills | TUI |
| /hatch | Tools & Skills | TUI |
| /learn | Tools & Skills | journey |
| /init | Tools & Skills | host setup |
| /cron | Tools & Skills | cron residents |
| /suggestions | Tools & Skills | n/a |
| /blueprint | Tools & Skills | n/a |
| /reload | Tools & Skills | cli-only |
| /reload-mcp | Tools & Skills | cli-only |
| /reload-skills | Tools & Skills | cli-only |
| /palette | Info | TUI |
| /restart | Session | host |
| /subscription | Info | n/a |
| /topup | Info | n/a |
| /platforms | Info | gateway |
| /platform | Info | gateway |
| /copy | Info | TUI |
| /paste | Info | TUI |
| /update | Info | release flip process (skill) |
| /debug | Info | host logs |

