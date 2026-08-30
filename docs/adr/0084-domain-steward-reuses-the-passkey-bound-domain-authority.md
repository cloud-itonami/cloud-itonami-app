# ADR-0084: Domain Steward reuses the Passkey-bound Domain Authority

Status: accepted

## Context

Cloud Itonami exposes domain catalog, pricing, registration, auto-renew and DNS
operations through `domain_tools`. Reads are direct, while mutations are exact
proposals governed by `authority.api` and human Passkey approval. The resident
workforce could not use this surface, so domain operations still depended on an
operator manually calling MCP despite already having a durable Bot scheduler.

Giving an ordinary workforce Bot the Domain tools would also give it unrelated
repository, Commerce, Wallet and browser tools. Treating `domain_commit` as an
ordinary write without the inner authority would make omakase an approval
bypass; withholding it would leave Passkey-approved proposals unexecutable by
the autonomous loop.

## Decision

Provision one `cloud-itonami/domain-steward` workforce role. Its built-in tool
surface is exactly the Domain Authority tools and no other local tool family.
Its bounded resident cycle reads registrations, DNS and proposals, advances at
most one exact proposal, and stops.

The role may use standing omakase for its local proposal operations. Omakase
does not decide domain authority: registration billing, auto-renew mutation and
DNS mutation still enter only through `authority.api/commit!`, which refuses
unless a human Passkey approved the exact stored proposal. An unapproved commit
attempt is a refusal receipt, not a mutation.

The workforce capability token maps Domain tools to three explicit scopes:
`domain.read`, `domain.proposal.create`, and
`domain.approved-proposal.commit`. The last name records that autonomy begins
after, not instead of, the Passkey decision.

The scheduled minimum cycle is host-owned and deterministic: list
registrations, list proposals, and commit at most the first proposal already in
`:approved`. It does not call inference, search for names, or create a proposal.
This keeps renewal observation and approved actuation alive when the model
provider is unavailable; inference remains available for bounded discovery and
proposal preparation outside that fixed cycle.

## Consequences

- Domain portfolio observation and proposal preparation run on the existing
  durable workforce cadence and ledger.
- Passkey approval remains the only path to billing or DNS state change.
- The Domain Steward cannot edit repositories, operate a wallet or browse.
- A machine without configured Domain Authority credentials offers the role no
  Domain tools, rather than exposing calls that cannot execute.
