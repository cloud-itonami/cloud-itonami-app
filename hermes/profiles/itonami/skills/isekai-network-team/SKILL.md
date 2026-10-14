---
name: isekai-network-team
description: Use when planning or coordinating cross-functional business, marketing, sales, brand, engineering, operations, or game-development work for isekai.network from the Itonami profile.
---

# isekai.network cross-functional team

Use this charter when the owner explicitly asks Itonami to coordinate work for
isekai.network. Organize around a player or business outcome; do not start every
role on every request.

## What exists today

The current `itonami` profile is the cloud-itonami Business OS bot. The existing
`itonami-dispatch`, `itonami-po`, `itonami-mktg`, `itonami-sales`,
`itonami-eng`, `itonami-maint`, and `itonami-qa` profiles have cloud-itonami
scopes. They are not dedicated isekai.network team members and must not be
assumed to have authority or access to network-awai resources.

The separate `isekai` profile is the current isekai.network operations bot;
`isekai-support` handles its support inbox. These do not provide the full
business, brand, and game-development team requested here.

The roles below are a proposed roster, not registered or activated Bots. A role
name in this file does not create a Hermes profile, credentials, account access,
or permission to act.

Until a dedicated program profile exists, the invoking `itonami` profile is the
temporary intake coordinator and synthesizer for an owner-authorized request.
It may structure work and escalate gaps; it does not act as network-awai's
agent of record or grant itself external write authority.

## Proposed roster

| Role | Proposed profile | Owns | Partners |
|---|---|---|---|
| Program lead | `itonami-isekai-program` | Intake, goal, priority, cross-team dependencies, synthesis, and escalation | All active roles |
| Business and product | `itonami-isekai-business` | Business model, player/customer value, pricing hypotheses, partnerships, and evidence-backed priorities | Program, sales, marketing |
| Marketing | `itonami-isekai-marketing` | Audience research, channel plans, campaign drafts, funnel hypotheses, and measurement plans | Brand, business, sales |
| Sales and partnerships | `itonami-isekai-sales` | Qualified partner/customer research, discovery plans, pipeline evidence, and outreach drafts | Business, marketing |
| Brand | `itonami-isekai-brand` | Positioning, voice, visual direction, naming, and asset provenance | Marketing, game design |
| Engineering | `itonami-isekai-engineering` | Web, service, engine, build, and integration changes with reproducible evidence | Operations, game development, QA |
| Operations | `itonami-isekai-operations` | Health, incidents, release readiness, runbooks, and operational evidence | Engineering, program |
| Game director | `itonami-isekai-game-director` | Game vision, core loop, design decisions, and playable acceptance criteria | Business, brand, gameplay, world/content |
| Gameplay and systems | `itonami-isekai-gameplay` | Mechanics, progression, economy rules, input, and engine integration | Game director, engineering, QA |
| World and content | `itonami-isekai-world-content` | Worlds, quests, characters, items, narrative, and content pipelines | Game director, brand, gameplay |
| Game QA | `itonami-isekai-game-qa` | Reproducible playtests, compatibility, regressions, and release evidence | Gameplay, world/content, operations |

Until these profiles are registered, use only currently verified profiles for
scoped work. Route existing isekai.network repository and Pages context through
the `isekai` profile. If a task needs a missing specialist, record the gap and
prepare the role brief; do not claim a specialist ran.

## Self-organizing workflow

1. **Intake one outcome.** State the intended player or business result, the
   evidence available, the affected surface, and what is unknown. If the request
   contains several outcomes, propose a parent goal with separate child cards.
2. **Select roles by need.** The program lead dispatches only roles needed for
   the outcome. Workers may propose a split, dependency, or missing capability
   on the task board. The lead accepts or rejects that proposal and records why.
3. **Create resumable work cards.** Use the existing Hermes Kanban/dispatcher
   path when available. Each card has one accountable profile, one deliverable,
   explicit dependencies, evidence, acceptance checks, a restart point, and an
   authority limit. Share findings through the board, not ambient conversation.
4. **Let workers pull and refine.** Eligible workers claim ready cards using
   their role fit and measured fleet capacity. A worker can propose follow-up
   cards and handoffs but cannot grant itself another role's authority. Do not
   exceed capacity reported by current fleet probes.
5. **Integrate at dependency gates.** Business evidence precedes growth claims;
   brand direction precedes campaign assets; approved design precedes game
   implementation; QA evidence precedes release readiness. Sales feedback goes
   back to business and product as evidence, not as an automatic roadmap change.
6. **Close with evidence and next work.** The lead records completed, blocked,
   unmeasured, and proposed work separately. Repeated work may justify a
   persistent role or routine; one request alone does not.

### Work-card fields

```text
goal:
owner_profile:
repository_or_surface:
source_evidence:
deliverable:
dependencies:
acceptance:
authority_limit:
restart_point:
```

Never put credentials, private customer data, or approval tokens in cards,
comments, or attachments.

## Authority and evidence boundaries

- The coordinator organizes work; it does not own network-awai accounts,
  repositories, domains, game assets, or release decisions.
- Context and evidence may pass between profiles. Approval, credentials, write
  access, spending authority, and deployment authority do not pass with them.
- Marketing and brand roles prepare research and drafts. Public posts, campaign
  launches, and brand changes require the applicable owner approval.
- Sales prepares prospect research and outreach drafts. It does not send
  messages, commit terms, or represent a partnership as agreed.
- Engineering may prepare code and reviewable changes within an authorized
  scope. Merge, production deployment, store publication, and account changes
  follow the authority already granted for that exact surface.
- Operations reports measured status and prepares runbooks. DNS, billing,
  authentication, and destructive changes require their own scoped authority.
- Game design and development preserve source and asset provenance. Automated
  checks, a generated build, or a local playtest do not alone prove a public
  release or commercial readiness.
- Use dated source evidence for metrics and product claims. Missing evidence is
  `not measured`; do not fill gaps with estimates presented as facts.

## First-pass success criteria

The team is ready for profile implementation when every proposed role has a
reviewed SOUL, a bounded tool scope, a source-of-truth repository, and an owner
for approvals. The team is ready for activation only after those profiles are
registered, materialized, and individually read back. Neither state proves
runtime execution or a live isekai.network result.
