# ADR-0073: A scheduled job is a demonstrated Bot routine

Status: accepted, 2026-08-24

## Context

ADR-0034 kept routines out of the human surface so the conversation remained
the primary way to work with a Bot. That made sense while routines were an
internal capability. Once a person depends on recurring work, hiding its
schedule, enabled state and failures makes the resident indistinguishable from
a scheduler that is not running.

Hermes Bots establishes the useful interaction pattern: scheduled work belongs
to the selected Bot, opens from a compact titlebar action, and exposes creation,
enable/disable, run-now, deletion and recent execution. Cloud Itonami cannot
copy its arbitrary instruction model without also bypassing this application's
authority model.

## Decision

The Bots view exposes a **定期ジョブ** panel scoped to the selected Bot. A job
can be created only from the tool calls that Bot actually executed in its most
recent demonstration. The person names the job, states its intent and selects a
bounded interval. The saved steps remain content-addressed and are not editable;
changing the work requires demonstrating and saving a new job.

Each job shows its schedule, enabled state, current admission state, last run,
run-now action and a bounded recent execution history. Manual and scheduled
runs are distinguished. The conversation remains the complete audit trail.

The existing gates remain authoritative:

- a schedule never widens the Bot's grant or account bindings;
- missing tools make the saved job stale and refuse execution;
- writes still stop for approval unless the person already granted autonomous
  execution;
- only one run occupies a Bot at a time;
- schedules run only while the resident and its session are live;
- every mutation retains the same origin and CSRF checks as other Bot changes.

Bot-to-Bot handoff remains outside this control surface. This decision
supersedes only ADR-0034's statement that routines are not human controls; the
conversation remains the primary body of the Bots view.

## Consequences

The UI uses friendly interval presets rather than accepting a cron expression.
Calendar-specific requests such as a particular weekday and wall-clock time
continue to belong to the scheduler/calendar domain. This avoids introducing a
second scheduling language while still covering recurring resident checks.

Deleting a job removes the shortcut and its compact run summary. Conversation
messages and tool receipts are not deleted with it.
