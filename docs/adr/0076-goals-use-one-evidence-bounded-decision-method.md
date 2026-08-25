# ADR-0076: Goals use one evidence-bounded decision method

**Status:** accepted — 2026-08-25

## Context

Cloud Itonami had three adjacent capabilities that did not form one operating
loop. Businesses could bind and run an OASIS XMILE model; Bots could execute a
durable Goal plan; and repositories could carry ontology- and scenario-shaped
EDN. A Bot was not required to join those views before choosing an effect.

Calling the method “Maven” without a boundary would also overstate what is
known. The useful public architectural idea is a shared operational ontology of
objects, relations, actions and scenarios. Proprietary Maven internals are not a
dependency or a compatibility claim.

## Decision

Every Bot Goal uses this default loop:

1. **Evidence and ontology** — observed facts retain their evidence; assumptions
   do not become facts. Actors, assets, obligations and constraints are explicit
   entities and relations connect only declared entities.
2. **System dynamics** — use a business-bound XMILE model when one fits. Without
   one, record a stock-flow sketch. `not-material` is an explicit exception with
   a reason, not an omitted analysis.
3. **Scenarios** — compare the baseline with at least one feasible alternative.
   Scenarios fork assumptions without rewriting the baseline.
4. **Inference** — score expected value, evidence confidence, reversibility,
   authority fit, time efficiency, cost efficiency and dependency independence
   in `[0,1]`. The host, not model prose, applies the versioned weights.
5. **Governed action** — the selected scenario is advice. Existing capability,
   approval, money and external-effect gates remain authoritative.

The model records this through `decision_frame`. The host validates and bounds
the frame, computes scenario scores, and stores it with the durable Goal. A Goal
may gather read evidence before the frame exists, but a write is refused and
`goal_complete` cannot pass until a valid frame is present. `goal_blocked`
remains available because an inaccessible prerequisite must not be disguised as
a fabricated analysis.

## Consequences

- The method is the default for business work submitted as a Goal, including
  resident workforce ticks.
- Ordinary conversational answers are not forced through a heavyweight frame.
- XMILE is used when dynamics can change the choice; it is not ceremonial XML
  generated for a one-step reversible read.
- The durable event records the selected scenario, dynamics mode and
  host-computed ranking. It grants no new tool or authority.
- A future score-weight change requires a schema/version decision rather than a
  silent prompt edit.

## Verification

- Pure validation tests cover score computation, evidence requirements,
  ontology references, dynamics modes and scenario selection.
- Bot Goal tests exercise `decision_frame` in both interactive and detached
  Goal execution before the host verifier accepts completion.
