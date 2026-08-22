# ADR-0070: A standing delegation is the operator's, and provisioning keeps it

**Status:** accepted — 2026-08-22. Extends ADR-0052 and ADR-0060; changes no
route.

## Context

ADR-0060 rests on one sentence: only the human `/api/bots` surface may write
`:bot/omakase?`. That is what makes a Bot's decision an owner's authority
carried out rather than the Bot's own.

Two things were true on 2026-08-22 that the sentence did not cover.

1. **Provisioning took the delegation away.** `provision-workforce!` wrote
   `:bot/omakase? false` into every workforce Bot on every run. A delegation a
   person set in Settings lasted exactly until the next `bots provision` —
   which is how a registry edit reaches a running Bot, so "until the next
   time anyone changed an objective". Nothing reported it. Measured while
   re-provisioning the dougaka roles three times in one afternoon.

2. **The owner's standing decision had no place to live.** The owner said, of
   the dougaka production Bots, 「基本は omakase でok」. Expressed through the
   only surface ADR-0060 allows, that is three toggles in a browser, redone
   after every registry refresh (see 1). A standing decision that must be
   re-performed by hand is not standing.

## Decision

- **Provisioning never lowers `:bot/omakase?`.** An existing Bot keeps what it
  has. A person's delegation survives a registry refresh.
- **The operator's configuration may declare a standing delegation**:
  `[:bots :workforce :omakase]` is `:all` or a set of workforce keys
  (`"business/kind"`). Provisioning applies it to the Bots it names. Absent
  means what it meant before: nobody.
- **The human Settings surface is still the only place to flip one Bot**, and
  still the only HTTP route that writes the field. `standing-omakase?` reads
  a file no route can reach; an agent session is exactly as unable to set its
  own delegation as before.

## Why the config file is a human surface

The same file already carries the deployment's most consequential human
decisions: which providers are `:reviewed? true` and `:enabled? true`, the
public origin, who may sign up. ADR-0060's worry was an *agent session*
reaching a route; a file on the operator's disk is on the other side of that
line. (A local process with the operator's filesystem could also edit
`state.edn` directly; that was never what the gate was for.)

## Consequences

- `cloud-itonami-app.defaults.edn` ships no `:omakase` key. A distribution
  cannot delegate on a stranger's behalf, for the same reason it ships
  providers disabled.
- `bots provision` now reports the same `:omakase?` it found, so the CLI can
  be read to verify a delegation rather than the browser.
- `:all` is one word. It delegates every workforce Bot's admitted writes —
  repository commits included — without a card. The dougaka deployment on
  2026-08-22 names three keys instead; widening to `:all` is the operator's
  one-line decision, not this ADR's.
- Tested both ways: with the old `false` reset restored, the new test fails
  at the assertion that names it.
