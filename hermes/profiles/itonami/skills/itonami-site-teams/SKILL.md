---
name: itonami-site-teams
description: Use when Itonami coordinates business, marketing, sales, brand, engineering, operations, or game work for aozora.app, x402.nexus, shinshi.club, oppai.fans, babiniku.net, yataverse.com, or murakumo.cloud.
---

# Itonami site teams

Itonami coordinates work across seven separate services. The service lead owns
its product decisions; Itonami organizes evidence, priorities, dependencies,
and handoffs. A profile name or Kanban assignment does not grant the recipient
ownership, repository access, account access, or authority to take an external
action.

## Current team map

Use only profiles whose current SOUL and source repository cover the task. The
names below were present in the local Hermes profile inventory on 2026-10-14;
presence does not prove an active gateway, running job, or successful turn.

| Service | Board | Lead / current profiles | Coverage and routing notes |
|---|---|---|---|
| aozora.app | `site-aozora` | `aozora`, `web3-marketer` | Route product, engineering, and operations to `aozora`; use `web3-marketer` for audience and campaign research. Aozora includes manga and browser games, so use a game-design lane for game-specific outcomes. |
| x402.nexus | `site-x402` | `x402`, `x402-growth`, `x402-mktg`, `x402-ops`, `x402-pnl` | `x402` owns facilitator and catalog context; growth, marketing, operations, and P&L have dedicated profiles. No dedicated sales profile is listed; the lead may prepare an internal sales brief, but may not contact prospects. Payment and settlement are separate from marketing or sales work. |
| shinshi.club | `site-shinshi` | `shinshi`, `shinshi-chat-ops`, `shinshi-eng`, `shinshi-mktg`, `shinshi-sales`, `shinshi-support` | Use the matching specialist for chat operations, engineering, marketing, sales research, and support. The service lead owns the adult-content safety and age-gate boundary. |
| oppai.fans | `site-oppai` | `oppai-producer`, `oppai-scheduler`, `oppai-gen` | Preserve the producer's existing hourly quota, zero-cost generation, visual review, and guarded publication contract. Dedicated business, sales, marketing, and brand profiles are not listed; keep those as proposals owned by the lead until staffed. |
| babiniku.net | `site-babiniku` | `babiniku`, `babiniku-support` | The lead and support profile are present. There are no dedicated business, marketing, sales, brand, engineering, or game-design profiles in this team map; log and prioritize gaps instead of assigning unrelated Itonami bots. |
| yataverse.com | `site-yataverse` | `yataverse-maint`, `yataverse-sd` | `yataverse-maint` owns the bytes plane for `*.ipfs.yataverse.com` and `*.ipns.yataverse.com`; it is propose-only for deploy, merge, and DNS. `yataverse-sd` analyzes `wiki.yataverse.com`, not the bytes plane. `com-yataverse-kotobase-net` owns Kotobase and wiki surfaces; do not treat it as the yataverse.com service owner. A dedicated business/brand/sales lead is not listed. |
| murakumo.cloud | `site-murakumo` | `murakumo-cloud`, `murakumo-po`, `murakumo-actions`, `murakumo-mktg`, `murakumo-sales`, `murakumo-support`, `murakumo-eng`, `murakumo-qa`, `murakumo-review` | The public-site lead coordinates product, operations, growth, sales research, and support. Keep public Workers separate from inference fleet/GPU work; route those to the profiles whose SOUL explicitly owns that layer. |

The `itonami` profile is the portfolio coordinator, not the agent of record for
these services. For each board, the named service lead is accountable for
triage and synthesis. When a specialist is absent, the lead may draft a scoped
role brief or request owner staffing; it must not say that a missing bot ran.
The full isekai.network game and business team is specified separately in
`skills/isekai-network-team/SKILL.md`.

## Team responsibilities

Choose only the lanes needed to reach the requested outcome. Record the
accountable profile for each selected lane:

- **Program and operations lead:** translate an owner goal into a measurable
  service outcome, order dependencies, keep the board current, and report
  completed, blocked, proposed, and unmeasured work separately.
- **Business and product:** describe customer value, current offer, supported
  evidence, and a small set of product priorities. Treat pricing, revenue, and
  costs as unknown until supported by dated receipts.
- **Marketing and brand:** research audience and positioning, maintain voice
  and provenance, and prepare campaign or brand drafts. A draft is not a public
  post or campaign launch.
- **Sales and partnerships:** qualify organizations from public evidence,
  maintain an internal pipeline, and prepare outreach drafts. Never send a
  message, make a commitment, or imply a partnership without the applicable
  owner's approval.
- **Engineering:** make a scoped, reviewable change in the authoritative
  repository and report reproducible evidence. Read that repository's
  `AGENTS.md` and profile SOUL before work.
- **Operations and finance:** report measured health, incidents, service cost,
  and release readiness. Do not invent usage, revenue, savings, or capacity.
- **Game design and development:** instantiate only when the service has a
  named game outcome. Aozora's browser-game surface qualifies; unrelated
  infrastructure or content services do not get a fictional game team.
- **QA and safety:** check the service's stated acceptance criteria and
  content/accessibility boundaries. Do not convert a generated draft or local
  check into a public-release claim.

The table is a coverage snapshot, not a guarantee that every function has a
dedicated bot. A missing specialty is an explicit staffing gap. Do not route a
task to a bot solely because its name looks relevant.

## Self-organizing Kanban cycle

1. **Intake:** create one parent task per owner outcome on that service's board.
   State the user/customer result, surface, date, source evidence, unknowns,
   and the boundary on allowed actions.
2. **Decompose:** add child cards only for real parallel work or dependencies.
   Each child has one accountable profile, one deliverable, acceptance
   evidence, a restart point, and an authority limit. Link blockers explicitly.
3. **Select and claim:** assign ready cards only to a profile whose current
   SOUL covers them. Workers may propose a split or handoff; the service lead
   resolves overlap and keeps one owner per result.
4. **Execute one bounded pass:** read the current source once, make the smallest
   useful contribution, and write evidence plus the next restart point to the
   card. A worker may open at most one follow-up card per pass unless the lead
   approves a larger dependency graph.
5. **Integrate:** product evidence precedes marketing claims; approved brand
   direction precedes campaign assets; a reviewed implementation precedes
   release readiness; customer feedback informs but does not automatically
   change the roadmap.
6. **Close honestly:** distinguish `done`, `blocked`, `proposed`, and
   `unmeasured`. Close a task only when its stated acceptance evidence exists.

Use this card shape:

```text
outcome:
service_and_surface:
owner_profile:
source_evidence:
deliverable:
dependencies:
acceptance_evidence:
authority_limit:
restart_point:
```

Run one bounded, read-only baseline per service before proposing a standing
routine. Do not add a cron job or start the shared host gateway as part of
ordinary decomposition. A recurring cadence requires a named owner, a
measurable purpose, a cost/capacity limit, a failure policy, and a confirmed
gateway scope.

## Authority and evidence

- Keep all existing service-specific SOUL rules. This charter neither grants
  new permissions nor removes prior approvals.
- A handoff between profiles does not authorize sending its context to an
  external inference provider. Before sending profile, repository, or business
  context to a nonlocal inference endpoint (including a Murakumo inference API),
  obtain explicit owner approval naming the provider/endpoint, purpose, and
  context scope. A configured provider or ordinary task request is not consent.
- Starting the shared host gateway for recurring work requires explicit owner
  authorization for the affected profiles and channels, named operational
  owner, cadence, cost/capacity limit, failure policy, and stop procedure. The
  gateway can activate profiles and channels outside the requested service, so
  approval for a one-time board task does not authorize gateway startup.
- Public posting, outbound sales, campaign launch, spend, payment/settlement,
  credential changes, DNS changes, merge, deployment, and destructive actions
  require the authority already defined for that exact service and action.
- Never use the Itonami Grok Business Bot launch, wallet, credit, or x402
  purchase path to staff a Hermes team. Those are distinct systems with
  separate approval and financial boundaries.
- For `shinshi.club`, preserve 18+ age controls and NSFW labels; never create or
  publish content involving minors or age ambiguity.
- For `oppai.fans`, preserve the producer's one-per-hour, zero-cost, reviewed
  generation rules and its stricter non-explicit content scope. Do not route
  around its scheduler, receipt, image review, or publication guard.
- For `x402.nexus`, do not confuse catalog work with a payment, transfer,
  settlement, or trade. Preserve the endpoint and catalog evidence required by
  its profile.
- For `yataverse.com`, preserve the bytes-plane ownership boundary. DNS and
  deployment remain operator actions; `yataverse-sd` findings are not bytes
  plane health evidence.
- For `murakumo.cloud`, keep the public Worker, model registry, and GPU fleet
  responsibilities distinct. Do not call a client page or API health check
  proof of inference capacity, deployment, or paid usage.
- Keep secrets, customer records, and approval tokens out of cards and
  comments. Attach only the minimum evidence needed to resume the work.

## Activation evidence labels

Report these states separately:

1. **Designed:** this charter defines the team and handoffs.
2. **Source updated:** the charter and profile instructions are in a reviewable
   repository change.
3. **Materialized:** the installed profile reads back the intended source.
4. **Registered:** the named profile appears in Hermes profile inventory.
5. **Executed:** a bounded task has a successful runtime attempt and result.
6. **Recurring:** the intended schedule is registered and a gateway capable of
   firing it is running.
7. **Live outcome:** the actual public or business result was independently
   verified.

Never report a later state on the evidence of an earlier one.
