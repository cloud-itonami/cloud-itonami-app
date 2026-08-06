# ADR-0023: A user account is a tenant of its own

Status: accepted and implemented

## Context

`docs/tenant-model.md` said one User may hold memberships in several Tenants and
that a session records exactly one active membership. It did not say where the
person themselves lives, and the code answered that question three different
ways at once:

- `register!` created one tenant per installation whose display name defaulted to
  the literal string `"Personal"`, and `configure-organization!` decided whether
  to rename it by comparing that string.
- `project-repository/chat-context` read a `:tenant/kind` attribute that
  **nothing ever wrote**, and fell back to inferring that whichever tenant the
  `:identity/registered` event named was somebody's personal namespace. That
  inference is wrong for every deployment whose first tenant was a real company,
  which is the ordinary case here.
- `issue-session!` chose the active tenant with `(some ... (vals memberships))`
  over the deployment's whole membership table — array-map order below nine
  entries, unspecified above it. Which organization a person signed in to was
  therefore decided by insertion order they never saw, and the organization
  switcher only changed the current session, so the choice did not survive
  sign-out.

Separately, a slug could name two different owners: `create-organization!`
checked new slugs against organizations only, and `configure-organization!`
handed the owner the organization's slug as their account id when they had none.
`<slug>.<organization-domain-suffix>` and `<slug>@<account-domain>` are derived
from those two, so one string could address a company and a person who are not
the same party.

## Decision

**Every User owns exactly one personal tenant, and organizations stand beside it
rather than containing it.** This is the arrangement GitHub has — a personal
account is an owner in the same namespace as an organization, not a member of a
primary one. There is no "primary organization" concept; there is a personal
tenant and there are organizations.

1. **`:tenant/kind` is written, not guessed.** `:personal` or `:organization`.
   `register!` creates the personal tenant with the User; it creates an
   organization beside it only when the call actually names one.
   `create-organization!` always creates `:organization`.

2. **The personal tenant holds its owner's handle.** Its slug is the User's
   `:account-id`, claimed at registration when free. `configure-organization!`
   branches on the kind: on a personal tenant it claims the slug *and* the
   handle, because they are one name; on an organization it leaves the owner's
   handle alone.

3. **One member.** `add-user!` refuses a personal tenant. A second person inside
   a tenant named after the first would be working under someone else's name.

4. **Landing is deliberate.** `:default-membership-id` on the User records the
   tenant they last selected — set at registration, updated by
   `switch-organization!` and by accepting an invitation. `issue-session!`
   honours it and falls back to the oldest membership, never to map order.

5. **One owner namespace.** A slug names a tenant or a User, never both. The
   personal tenant is the single exception, and it is not really one: it holds
   its owner's handle by construction. Enforced by `slug-claimed-by` in
   `create-organization!` and `configure-organization!`.

6. **Crossing a tenant boundary stays a lease, not a membership.** Moving data or
   work between a personal tenant and an organization uses the tenant
   connections of ADR-0014 — approved, capability-scoped, expiring, revocable.
   Nothing here adds a way for one tenant to read another.

## Migration

`ensure-personal-tenants!` runs from `public-state`. It stamps every existing
tenant `:organization` and creates the missing personal tenant for each User.

**No existing tenant is reclassified.** A person already working in two
organizations gains an empty third tenant that is theirs, and both organizations
stay organizations — including one that happens to be called "Personal", because
what it was created as is a fact and its display name is not evidence.

## Consequences

- The organization switcher gains one entry per person, labelled `· 個人`. It is
  empty until something is put in it; everything already scoped by tenant
  (workspaces, connections, governed Kanban partitions, repository owner ids)
  works there unchanged, because a personal tenant is a Tenant.
- A deployment where the pre-ADR code handed an owner their organization's slug
  as a handle already has one string with two owners. That is not rewritten —
  renaming a live organization or a live mail address to satisfy a new rule is
  worse than recording it. Such a User's personal tenant is created without a
  slug (`:pending-profile`) rather than taking a name that is in use.
- Configuring an *organization* no longer fills in a missing owner handle. A
  person who registered with no email and named an organization first keeps
  their `pending-…@` address until they claim a handle on their personal tenant.
  That is one more step than before and one less conflation.
- `organization-domain-for-did-web` still assumes a single-tenant deployment,
  but now prefers an organization and answers with a personal tenant only when
  there is no organization to name. Multi-tenant `did:web` publication remains
  out of scope, as `docs/tenant-model.md` already says.
- `project-repository/chat-context` reports the kind it is told rather than the
  kind it inferred.

## Alternatives

**Leave personal-ness implicit.** Cheapest, and it is what the code was doing:
the string `"Personal"`, an unwritten attribute, and an event-shaped guess. Three
mechanisms, no agreement, and a chat context that told the model the wrong thing
about which tenant it was standing in.

**Mark one membership `primary` instead.** This makes a person's own work live
inside whichever organization they picked, so leaving that organization takes it
with them. A personal tenant does not have that failure.

**Reclassify the first tenant as personal during migration.** One line shorter
and wrong: the first tenant here is `gftd`, a real company with real members.
