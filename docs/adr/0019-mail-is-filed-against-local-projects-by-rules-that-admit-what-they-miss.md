# ADR-0019: Mail is filed against local projects, by rules that admit what they miss

## Status

Accepted. 2026-08-05.

## Context

Mail arrived and stopped. `mail-sync` fetches it, `classify` puts four broad
labels on it (`:finance`, `:security`, `:notification`, `:newsletter`), and
`mailbox` records what one person has read or starred. Nothing connected a
message to the work it was about.

The app has two things called "projects" and they are not the same thing:

| | What it is |
|---|---|
| `/api/workspace/projects` | **GitHub Projects v2**, read through `gh api graphql` |
| local projects | ordinary Git repositories this machine owns, one per organization/user/project |

This ADR is about the second.

**The second did not exist on `main`.** `project_repository.clj` — 687 lines,
the whole local-project model — was an **untracked file** in a working tree 37
commits behind `main`. It was on no branch, on no remote, and had no PR. The
resident server was running it, so it looked like a shipped feature from the
outside while being one `git checkout` away from gone.

## Decision

### Phase 1 — rescue the local projects, without the tree they came from

`project_repository.clj` lands as it was. The 26 modified tracked files beside
it do **not**. They are a divergent evolution of this app's mail and server
namespaces, not an increment on top of them: that tree's `mail_sync.clj` is a
single-account predecessor of `main`'s multi-account split across
`mail-account` / `mail-gmail` / `mail-imap` / `mail-pop3` / `mail-send`.
Replaying it would have rolled back working, verified mail. The deletion of
`app.clj` is recorded and never replayed, per the cleanup runbook. Everything —
patch, untracked files, status — is archived to `.git/stash-archive-20260805/`.

One function was genuinely needed and genuinely missing: `documents/
ensure-folder-path!`, ported additively, changing no existing function.

Routes are written fresh against `main` rather than copied from the divergent
`server.clj`. `/api/projects` is deliberately not `/api/workspace/projects`:
both sources call their own thing "projects", so neither name could be taken
from the other, and the path says which authority is being asked.

### Phase 2 — assignment is a third plane, not a move

```
[:mail :messages]                     what the accounts returned  — shared, not ours to rewrite
[:mail :marks <principal>]            what one person did with it — per person
[:mail :project-assignments <org>]    what work it belongs to     — per ORGANIZATION
```

Organization-scoped, because a project is shared: two people looking at one
project see the same mail filed against it, while what each has read stays their
own. Nothing is moved and nothing is deleted — unassigning a message puts it back
in the inbox it never left.

### Rules are deterministic, and there is no model in this path

A rule matches on the envelope — sender, sender domain, a subject substring, or
a label `classify` already derived. **Every** clause must hold, because
narrowing is what writing a second clause is for. The first matching rule wins,
so order is meaningful and visible.

An LLM asked "which project is this invoice for?" will answer confidently for
mail that belongs to none of them. **A filing system that is confidently wrong
is worse than one that leaves things unfiled**: the unfiled pile is visible, and
a wrong assignment is not. So `apply-rules!` reports `:unmatched`, and
`/api/mail/projects/unassigned` ranks the sender domains worth a rule — the gap
is a number, and the next action is derived from it rather than guessed.

Measured on 108 real messages: 4 rules filed 20 and left 88, and the top
unassigned sender was `icloud.com` with 28 — which is a private-relay address,
i.e. exactly the case a model would have cheerfully misfiled.

### Two refusals that make the rest trustworthy

- **A rule cannot name a project this organization does not have**, checked when
  the rule is written. A typo would otherwise file mail into a project nobody
  can open, and it would look like it worked. This is the specific weakness of
  referring to projects by bare id, and it is why Phase 1 had to come first.
- **A rule never overwrites a manual assignment.** Rules re-run whenever mail
  arrives; without this, filing something by hand would last exactly until the
  next sync.

## Consequences

- Mail can be filed against the work it belongs to, and the filing is auditable:
  every assignment records whether a rule or a person made it, and which rule.
- 11 CLI commands appeared with no CLI code written — the registry is generated
  from the routes (ADR-0018). `itonami mail projects`, `… apply`, `… assign`,
  `… unassigned`, `projects mail`, and the rest.
- **The unfiled pile is the product surface**, not a failure state. 88 of 108 is
  a normal first day, and the ranked senders are the backlog of rules to write.
- Both project prefixes dispatch through ONE `cond` clause. `handler`'s reify
  method is within a few hundred bytes of the JVM's 64 KB bytecode limit; a
  second clause pushed it over and the compiler refused the whole namespace,
  twice, during this work. New route families belong in a sub-router.
- Assignment does not yet reach the project's Git repository — a filed message
  is a record in the store, not a commit in the project. Whether mail should
  become an artifact in the project repo (it already has an issue model) is the
  obvious next question and is deliberately not answered here.
- The other rescued untracked namespaces from that tree — `automation`,
  `git_session`, `ux_score` — are archived and still present in the working
  tree, and are **not** landed here. They are unrelated to this work and would
  have made this diff unreviewable.
