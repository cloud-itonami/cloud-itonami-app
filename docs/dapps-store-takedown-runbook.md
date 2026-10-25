# DApps Store reporting and takedown runbook

What happens after a listing is published and something is wrong. Decisions:
[ADR-0144](adr/0144-dapps-store-listings-carry-published-trust-claims.md),
[ADR-0145](adr/0145-a-dapp-publication-consumes-action-specific-trust-evidence.md),
[ADR-0146](adr/0146-a-publication-deposit-is-a-bond-with-explicit-forfeiture.md).
Linear: JUN-425 (this runbook), JUN-426 (UI surface).

## Roles

| Role | Who | Authority |
|---|---|---|
| intake | the report channel (UI button, JUN-426) + bot first-pass triage | record and classify only |
| investigator | the named store operator (human) | quarantine, evidence gathering |
| approver | the named store owner (human) | tier derating, credential revocation, deposit forfeiture, relisting |

A bot may classify and gather; it may not derate, revoke or forfeit. Those
are the human gate.

## Flow

1. **Report received.** Record: listing id, reporter contact (optional),
   claimed violation, evidence links, timestamp. Every report gets a case id.
2. **Triage (target: first response within 72h).** Duplicate or out of
   scope → close with reason. Otherwise classify: impersonation, fabricated
   claims, malware/phishing, artifact mismatch, other.
3. **Quarantine.** A suspected fraud listing is hidden immediately
   (investigator decision, no waiting). The listing's tier drops to
   `quarantined` in the published record; the UI shows the state, not a
   blank. Other listings from the same publisher and identical digests are
   swept in the same step.
4. **Investigation (target: decision within 14 days).** Compare the
   published digest against the reviewed artifact; re-check the publisher's
   evidence (step-up receipt, identity credential, existence claim, deposit
   status); collect the report's evidence and the publisher's response.
5. **Decision by the approver.**
   - Confirmed violation → permanent takedown, tier → `removed`, credential
     revocation where the claim was false, deposit forfeiture (ADR-0146) if
     posted. Write the audit record: case id, evidence, approver, actions.
   - Not substantiated → relist, restore tier, record the correction and its
     reason. Repeated unfounded reports from one source are themselves
     recorded.
6. **Publisher response and appeal.** The publisher may respond during step
   4 and appeal a decision once, to the owner. An overturned decision
   relists, restores tiers and returns any forfeited bond; the correction is
   part of the audit record.

## Rules that do not bend

- A report alone never removes a listing permanently and never forfeits a
  deposit; quarantine hides, it does not convict.
- Badge and tier state always come from the published record. Takedown
  derates the record first; the UI follows.
- Every action above writes to the audit ledger. An action with no record
  did not happen.
- The runbook is exercised: one tabletop drill per quarter (a written
  scenario walked through steps 1–6), with the drill record kept next to
  this file's history.

## Status

The UI report button (JUN-426) and the deposit surface (JUN-423) are not
implemented yet. Until they are, reports arrive through the operator's
existing contact channels and steps 1–6 still apply with the record kept in
the case tracker (Linear).
