# ADR-0024: A project can leave the tenant it was started in

Status: accepted and implemented

## Context

ADR-0023 gave every person a tenant of their own. The ordinary thing to do with
something started there is to hand it to an organization once it stops being
personal — and the reverse, taking a project out of an organization that is
winding down. Neither was possible: a project's coordinates are its tenant, in
three places at once, and nothing rewrote them.

- `:chat-projects` and `:project-workspaces` are keyed by `[organization-id
  project-id]`.
- The Git project lives at `projects/<organization-storage-id>/<slug>`, and its
  `.itonami/project.edn` names that storage id.
- The editable workspace lives at `<workspace-root>/<storage-owner>`, where
  `storage-owner` hashes the organization, the user *and* the project.

A personal tenant work can only enter is a box with no lid.

## Decision

`POST /api/projects/{project}/transfer` moves one project from the session's
active tenant to another tenant the caller belongs to. It is deliberately
narrow, and each limit is a decision rather than an omission.

**Both sides are yours.** Owner or admin in the source tenant *and* in the
destination. A member cannot push work into an organization they do not
administer, and cannot take an organization's project into their own namespace.
`identity/tenant-membership` exists for this: authority over a tenant is not
always a question about the active one, and `membership-role` can only answer
for the session.

**A human, in a browser.** The route takes `require-human-session!`, and
`transfer-project!` additionally requires `:kind :passkey` — so neither an agent
session nor an email magic-link session (ADR-0012) can move ownership.
`require-passkey!` alone would admit both. The reason is specific: an agent
holding a tenant connection to one tenant could otherwise grant itself access to
a project by moving the project into it. Being human-gated also keeps the
operation out of the generated command registry, where per ADR-0018 it would be
a command certain to refuse.

**Local-only projects.** A published project's ciphertext is encrypted under a
key bound to its `storage-owner`, which hashes the tenant it is leaving. Renaming
the directory would move bytes nobody at the destination can read. Re-keying them
is a re-publication, not a move, and is not this operation.

Publication is now recorded on the project — `:publication-state :none` at
creation, `:published` with `:published-at` when `persist-conversation!`
actually publishes. `:sync-state` could not answer this: it says whether the
deployment *can* publish, not whether this project *did*. A project created
before that record exists cannot prove either way, so on a deployment that has
publication configured it is refused as well. Moving unreadable ciphertext is
worse than refusing a move.

**What does not move is named, not discovered.** Mail filed against the project
stays in the tenant it was filed in — a filing is a record of that tenant's
correspondence, and carrying it out would move somebody else's mail. The receipt
reports how many messages and rules stayed, and the UI repeats it.

## Mechanics

Directories move first, the store transaction second, because the disk move is
the reversible half: a failure in the transaction moves the directories back and
rewrites the metadata file. The reverse order would leave records pointing at
paths that do not exist yet. A destination directory that already exists is a
refusal, not a merge.

The transaction rewrites `:chat-projects`, `:project-workspaces` (including the
`:organization-id` inside the board), and every `:drive-artifacts` key naming the
project, then appends a `:project/transferred` event.

## Consequences

- The transfer form lives in Settings beside the tenant cards, not in the
  Projects panel — that panel reads GitHub Projects v2, and this is a question
  about tenants. The destination list offers only tenants where the caller is
  owner or admin; the server checks both sides again.
- Drive items do not move, because they are scoped to the User rather than the
  tenant. Only the artifact *keys* that record which project produced them are
  rewritten.
- A deployment with publication configured cannot move projects created before
  this ADR. That is a permanent consequence for those projects: nothing local
  can now establish whether they were published.
- `route-scan` reads a clause's body as the text up to the next clause, so a
  comment above a clause belongs to the clause above *it*. A comment naming
  `require-human-session!` therefore silently re-gated the route before this
  one, removing a command from the registry. Caught by the count in the
  regenerated registry; recorded here because the next person writing a route
  comment will meet the same edge.

## Alternatives

**Let an agent do it with a tenant connection capability.** Rejected: the
capability would be "may move a project into the tenant I am connected to",
which is a way to widen a lease by moving its target inside.

**Copy instead of move.** Two projects with one history, diverging from the
first edit, and no answer to which one the mail filings mean.

**Move the ciphertext too, re-encrypting under the destination owner.** That is
a real operation and a much larger one — it needs the destination's key epoch, a
new head chain, and a story for the old head. Refusing published projects is the
honest interim, and it says so rather than moving bytes that will not decrypt.
