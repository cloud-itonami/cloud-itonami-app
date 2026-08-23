# ADR-0071: A business names its organization, and lands only there

**Status:** accepted — 2026-08-23. Extends ADR-0056 (startup workforces are
governed resident Bot jobs) and ADR-0023 (a user account is a tenant of its
own). Changes one route's error table and the `POST /api/agent-session` body.

## Context

The owner asked for the Tamaki actors (`etzhayyim/tamaki`, `actors/*.edn`) to
run as Cloud Itonami Bots, under a profile, **inside the etzhayyim
organization**. ADR-0056 already gives a registry business a way to become
resident Bots: `network-awai/loop-yakuwari` projects it, `provision-workforce!`
reconciles it, the tick drives it. Three things stood between that path and
the request.

**The catalog had no notion of which tenant a business belongs to.** Every
workforce Bot was scoped to the provisioning session's tenant
(`bots.clj`: `:bot/organization (:organization-id session)`), and the whole
catalog was projected into it. All 92 Bots of the 13 businesses live in the
operator's *personal* tenant today for that reason. Adding a 14th business
would have put the Tamaki actors there too, beside businesses that belong to
nobody in particular, with nothing to say that `etzhayyim` is a different
owner.

**A CLI could not obtain a session in an organization tenant.** Agent sessions
land in the user's default membership (ADR-0023), and moving a session to
another tenant is `switch-organization!`, which requires a Passkey — correct
for a browser, and unreachable from a terminal. So even with a correctly scoped
catalog, `itonami bots provision` could only ever provision the personal
tenant.

**The tick consulted one session per person.** `tick-sessions` kept the newest
live session per user, and `fire-due-workforce!` fired only the jobs of that
session's tenant. A person present in two tenants had the workforce of one of
them running — whichever they had signed in to last — and nothing said so.

A fourth thing is not a design gap but a fact of this deployment: the
organization tenant was registered as **`etzhayym`**, and a tenant slug is
immutable once claimed (`:identity/organization-id-immutable`). The registry
names the organization correctly, `etzhayyim`. The two do not meet.

## Decision

1. **A business may name its organization.** loop-yakuwari's `businesses.edn`
   gains `:business/organization "<slug>"`, projected as
   `:business {:organization …}` (nil for the 13 that carry none, so the wire
   shape is one). `provision-workforce!` is now two-sided:

   - an **organization tenant** (`:tenant/kind :organization`) takes exactly
     the businesses that name its slug;
   - a **personal tenant** takes the businesses that name nobody;
   - an organization tenant that *no* business names is **refused**
     (`:workforce/no-business-for-tenant`, HTTP 409) rather than recorded as a
     workforce of zero under a name nobody used;
   - a store with no tenant record for the session (tests; a store from before
     tenants were recorded) reads as personal, so it provisions as it always
     did.

   The status (`workforce-status`) now reports `:tenant {:kind :slug :alias}`
   and `:catalog-businesses` beside `:businesses`, so `businesses 1` over a
   14-business catalog reads as the tenant's slice rather than the catalog's
   number wearing the tenant's name.

2. **An operator alias may bridge a tenant to a registry organization.**
   `[:bots :workforce :organization-aliases {"etzhayym" "etzhayyim"}]` in the
   deployment's configuration. It lives there and not in the registry because
   the typo is this deployment's, and because configuration is the operator's
   statement about which tenant stands for what (ADR-0043's line: configuration
   does not prove a name; here it is the operator vouching for one they already
   own). A match made through an alias is reported in the status, never
   silent.

3. **An agent session may name which held tenant it is for.**
   `POST /api/agent-session` accepts `organization-id` (record id or slug,
   case-insensitive); `itonami auth login --organization <slug>`. Local
   ownership of the store already proves everything the store holds, so
   choosing among the owner's memberships adds no authority the caller did not
   have — a tenant the user holds no membership in is `403`, not defaulted.
   The response carries `organization-id` so a caller can see it was honoured.

   Found while testing this: `owner-user-id` picked the session's user by
   counting owner *memberships*, so one operator owning a personal tenant and
   two organization tenants — three memberships, one person — was refused as
   `ambiguous-user` the moment the second tenant existed. It now counts
   distinct persons. Which tenant the session acts in was never that
   function's question.

4. **The tick fires each tenant the person is present in, under that tenant's
   own session.** `tick-people` keeps, per person, the newest live session and
   the newest per organization; `fire-due-workforce!` takes that per-tenant
   list, submits each job under the session of *its* organization, and counts
   the active limit over **every** job the owner holds in any tenant — the
   provider is one slot whatever tenant asks for it, and a per-tenant count
   would let two tenants overlap on it, which is the overlap the limit exists
   to prevent. The tick still never creates, refreshes or impersonates a
   session (ADR-0056): a tenant with no live session in it runs nothing.

## What this does not change

- A workforce Bot's grant. One admitted repository, no connector tools, every
  write held for approval — `tamaki-entry` in the tests carries
  `:patch.create :blocked` and the Bot cannot widen it. "Runs as a Bot" says
  how the actor runs, not what it may do.
- Tamaki's own supervisor. The ActorSpecs remain its source of truth; the
  registry mirrors them and loop-yakuwari's parity test fails when a mirror
  drifts without a stated reason. Both runtimes may exist; the registry says
  which of the actor's runners actually execute today (only codex — the
  claude / grok runners hand a model id to a main that does not know it).
- Routines. They fire once per person from the newest session, as before.

## Consequences

- Provisioning the existing catalog under the personal tenant yields the same
  92 Bots; `:businesses` in its status drops from 14 to 13 because the
  etzhayyim business is not its to take.
- `itonami auth login --organization etzhayym --label …` then
  `itonami bots provision` (with the alias configured) lands the six Tamaki
  Bots in the `etzhayym` tenant; the tick fires them under that session while
  it is live, and the personal tenant's 92 keep firing under the other.
- The six share the one inference slot with the 92. At `max-active 1` that is
  98 resident jobs over one slot; the stagger and the 720-minute cadences are
  what keep it a queue rather than a pile-up, and the SLO score (ADR-0056's
  successors) is where it shows if they do not.

## Verification

- `bots_test`: a named business lands only in its tenant; the personal tenant
  takes only unnamed ones; an unmatched organization is refused and records
  nothing; the alias matches and says so; a store with no tenant records
  provisions as before; the tick fires each tenant under its own session; the
  active count spans tenants; two people's sessions are refused.
- `agent_session_test`: unnamed enrollment defaults as before; named by slug,
  by record id and case-insensitively lands in that tenant and says so; a
  tenant not held, or not existing, is 403 with no token.
- Full suite: 1,815 tests, 10,771 assertions, 0 failures (2026-08-23).
