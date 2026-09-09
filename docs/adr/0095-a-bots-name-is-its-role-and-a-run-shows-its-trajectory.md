# ADR-0095: A Bot's name is its role, and a run shows its trajectory

**Status**: accepted · 2026-09-09 · owner instruction「bot name は header をダブル
クリックすると変更できるようにして。また name が role を表すように、別の bot が
name を更新できるようにして」「必要な段階で trajectory を見れるようにして,
deepseek harness を参考に」

## Context

**The name could not be changed after creation.** `bots/update!` had taken
`:name` since it was written and no surface sent one: the field existed in the
create form and nowhere else. So a Bot kept the name of the errand that made
it. Measured on the owner's own workspace: the two Bots in the rail were called
`cloud-itonami/cloud-itonami-isic-7320 / pricing` and `… / research` — a repo
path and a task, which is what somebody types while starting one piece of work
and not what they want to read a week later in a list of nine.

**The steps of a run existed and nothing showed them.** `:job/events` has
carried `plan/recorded`, `action/started`, `action/finished`, `action/failed`,
`verifier/step-passed`, `verifier/goal-passed`, `subagent/*` and the `run/*`
lifecycle since the goal machinery landed. The one surface that read them
counted a single kind and printed `N execution receipts`. A number cannot say
which tool ran third, how long it took, whether its output was ever verified,
or which of two failures came first — and it cannot say what the Bot is doing
*right now*, which is the question somebody watching a run actually has.

## Decision

1. **The title is the rename control.** Double-click `#bots-titlebar-name`;
   Enter and F2 do the same from the keyboard, because a gesture only a pointer
   can reach is a control only some people have. Escape abandons, blur commits.

2. **A peer Bot can rename a Bot, through a route that can only rename.**
   `POST /api/agent-bots/:id/name`, MCP tool `bot_rename`, CLI `bots rename`.
   The handler builds the update itself — the shape `/api/agent-bots/:id/model`
   established — so no extra key in the body can reach `writes?`, `omakase?`,
   `tools`, `accounts` or a workspace. There is an HTTP test that sends exactly
   that body and asserts the grants did not move.

3. **A name is a claim about a role, never a grant of one.** `:bot/role` is the
   governed role from the reviewed registry and is not reachable from either
   rename door. A Bot that names a peer "compliance reviewer" has said what it
   thinks that peer does and has given it nothing.

4. **Who named it is recorded, and provisioning respects it.**
   `:bot/name-source` is `:person`, `:bot`, or absent. Workforce provisioning
   rebuilds every field from the catalog on each reconcile, so before this a
   rename lasted until the next tick and then reverted — to a *plausible* name,
   which is why it would have said nothing. Now it keeps a chosen name, the
   same contract `:bot/omakase?` already had, and keeps the registry's own name
   beside it in `:bot/projected-name` so `restore` has somewhere to go back to.

5. **A trajectory is one entry per step, joined.** `GET
   /api/bots/:id/runs/:run/trajectory` (and the agent twin, and `bot_trajectory`)
   projects the ledger into ordered steps, each `action/started` folded together
   with its own `action/finished` or `action/failed` by `:action/id`. This is
   the one respect in which it follows the published agent harnesses the owner
   named: an action and its observation are one row. Two events joined by an id
   are right for an append-only ledger and wrong for a reader, who otherwise
   holds the start in their head until the finish scrolls past.

6. **An unfinished action reads `running` rather than being dropped.** That row
   *is* the necessary stage: opening this mid-run is asking what the Bot is
   doing, and the answer is the step with no observation yet.

7. **A run that kept no ledger says so.** Only a Goal run records steps, and a
   plain chat turn answering `[]` would render as a Bot that took no steps —
   indistinguishable from one whose ledger was never written. `available?
   false` carries the reason, and an unknown run and a run without a ledger are
   two different answers.

8. **Nothing a model said appears.** The ledger is host-observed
   (`append-goal-event!`: "provider prose never enters it"), so a row means a
   call that ran and `:output-sha256` is the digest of the tool's real output.
   The bound is reported too: a run at the 200-event cap has lost its oldest
   steps, and a trajectory that quietly begins in the middle is worse than one
   that says where it begins.

## Consequences

- A renamed workforce Bot stops following its registry role name until somebody
  restores it. That is the intended trade and it is visible: the settings panel
  names the source and shows the registry's name beside the chosen one.
- The four new routes spell their session gate inside the clause as well as at
  the handler boundary. `route-scan` reads clauses, so a gate left only at the
  boundary records a protected route as UNAUTHENTICATED in the registry an
  audit reads — the defect `commands-test` already records for the group routes.
- `web-script-test` gained an invariant this change made worth having: every id
  the script binds at load must exist in the page. `$` is `querySelector`, so
  one typo throws at load and takes the entire interaction layer with it —
  every button inert, nothing said. 98 bindings, checked, with a floor that
  fails if the scan stops matching.
