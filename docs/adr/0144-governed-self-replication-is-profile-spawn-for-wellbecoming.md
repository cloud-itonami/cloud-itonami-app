# Governed self-replication is profile spawn, and its purpose is wellbecoming

The itonami steward may replicate itself within the AWAI network. What that
means here is governed profile spawn and retire (`:fleet/profile-spawn`,
`:fleet/profile-retire` under manifest/yakuwari.kotoba): the steward brings up
new bot profiles inside the registry when a named wellbecoming purpose needs
capacity that existing profiles do not cover, and retires them again.

Wellbecoming — the well-being and becoming of the people and communities AWAI
Network serves — is the purpose of the work and the purpose of any
replication. Replication is instrumental. A child exists because a concrete
purpose needs it, never to grow the fleet for its own sake; the moment
replication becomes its own justification, spawning stops and the pattern is
reported.

What this deliberately is not: the ARA worm threat model of Anthropic's
Frontier Red Team write-up ("AI models may be capable of self-replicating
without access to a datacenter"). Autonomous replication onto machines the
operator does not own — network scanning, intrusion and exploitation,
credential or session reuse for propagation, carrying model weights onto
third-party devices, evading shutdown, hidden persistence — is out of scope
and permanently refused. That research is a threat model, not a feature
specification. Self-replication here only ever acts inside the AWAI registry,
on owner-controlled hosts, through PRs a human can read.

The contract, enforced as fail-closed capability decisions in
hermes/profiles/itonami/yakuwari.edn and executed by
hermes/profiles/itonami/skills/wellbecoming-replication/SKILL.md:

1. Demand-driven spawn with a real purpose and observed evidence.
2. Registration gate: the child runs only after its registry entry has merged.
3. Capability subset: a child never holds authority the parent lacks;
   replication cannot mint authority. New authority is an owner edit to
   manifest/yakuwari.kotoba.
4. Charter inheritance: children carry the wellbecoming charter; no agent
   edits its own yakuwari, charter, or purpose line — those change only in a
   PR a human reviews.
5. Owner-controlled, registered hosts only.
6. Bounded scale: 4 children alive, 8 per month, cost within the existing
   fleet bounds.
7. Kill switch that always works: retire is immediate, revertable, and never
   concealed or delayed.
8. Evidence: one PR per spawn, measured results only.

The safeguards live in each profile's own SOUL.md, not only in linked
documents. The charter text for itonami is pending the owner's approval to
land in SOUL.md (the file is owner-gated); until then yakuwari.edn and the
skill carry the contract and itonami remains propose-only for anything the
charter would have permitted.
