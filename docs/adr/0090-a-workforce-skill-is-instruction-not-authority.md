# ADR-0090: a workforce Skill is instruction, not authority

**Status**: accepted / **Date**: 2026-09-01
**Related**: ADR-0034, ADR-0056, ADR-0060, ADR-0070, ADR-0089

## Context

The resident Disk Maintainer became useful only after four surfaces agreed:
its job in `network-awai/loop-yakuwari`, four semantic capabilities, four
concrete host tools, and observed scheduled runs from the deployed release.
Before that, a Bot profile could exist while cleanup was absent, or a healthy
process could hide that no useful tool ran.

That method should be reusable by Cloud Itonami's own Engineer. Copying it into
an objective would make the catalog verbose and would lose the standard Skill
shape. Letting a Skill declare tools or approvals would be worse: instruction
text would become a second authority plane beside capabilities and host tool
admission.

## Decision

`network-awai/loop-yakuwari` owns governed workforce Skill packages under
`skills/<id>/SKILL.md`. A role may declare up to four package IDs with
`:bot/skills`. Its workforce projection resolves each ID to an exact package,
computes SHA-256, and carries the bounded instructions with the role. Missing,
duplicate, malformed, oversized, or name-mismatched packages fail catalog
validation rather than disappearing silently.

Cloud Itonami validates the projected ID, digest and size, persists them on the
stable Bot identity, exposes only ID and digest in the human/API projection,
and includes the instructions in that Bot's system context. Re-provisioning
refreshes the package while retaining Bot identity, conversation, omakase
delegation, cadence state and pending repair triggers.

Skills are never consulted by tool admission, account binding, workspace
scope, capability decisions or approval. Executable authority remains the
intersection of the role's capability policy, the host's tool-to-capability
mapping, the Bot's concrete grants, and the effect governor. A Skill that says
"deploy" or "delete" cannot make either tool appear.

The first package is `itonami-bot-readiness`, assigned to the Cloud Itonami
Engineer. It turns the Disk Maintainer experience into a reusable readiness
contract: bounded objective, separate read/write capabilities, receipt-bound
mutation, provider-independent recovery when required, idempotent provision,
and evidence from tests through merge, pin, deploy, resident run, and healthy
follow-up no-op. Domain safety Skills still govern domain-specific mutations;
this package never grants deletion or deployment.

## Consequences

Cloud Itonami can use the reviewed Bot-readiness method during real Engineer
turns, and each live Bot reports the exact Skill digest it received. Catalog
and runtime fail closed on projection drift. The system prompt grows only for
roles that explicitly declare a package.

A Skill can improve decisions but cannot repair a missing host tool or grant.
Readiness therefore still requires executable and live evidence; Skill
presence alone is only the `wired` layer, not a claim that a Bot is deployed,
running, useful, or stable.
