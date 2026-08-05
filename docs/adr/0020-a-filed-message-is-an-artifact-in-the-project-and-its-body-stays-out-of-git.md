# ADR-0020: A filed message is an artifact in the project, and its body stays out of Git

## Status

Accepted. 2026-08-05. Answers the question ADR-0019 left open.

## Context

ADR-0019 made mail filable against local projects and said plainly what it had
not done: *"a filed message is a record in the store, not a commit in the
project's Git repo."*

That gap was larger than it sounded. A local project **is** a Git repository —
`create-project!` runs `git init` — and until now **nothing had ever committed
to one**. The repository was created, `.itonami/project.edn` was written into
it, and it stayed at zero commits forever. So "filed against a project" meant a
key in `state.edn` and nothing a person opening the project would ever see.

A filing system whose filing you cannot see is not much of a filing system.

## Decision

A filed message is written into the project's repository and committed. Where
it is written is the decision that matters.

### Two destinations, which is a boundary this codebase already drew

`project-repository`'s own docstring states it: *"Project source and
conversation history deliberately have different roots. The former is an
ordinary Git repository. The latter is plaintext only in the local editable
workspace and enters DataLad solely through the existing Kagi sealed-block
pipeline."* `.gitignore` has carried `.conversations/` and `.itonami/runtime/`
since the repository model was written, and `prepare-attachments!` materializes
its read-only copies into the ignored `runtime` path for the same reason.

Mail is the same category as conversation history, so it lands the same way:

| Path | Tracked? | Holds |
|---|---|---|
| `mail/<yyyy>/<mm>/<id>.edn` | **yes** | envelope, labels, `sha256` of the body, which rule filed it and when |
| `.mail/<yyyy>/<mm>/<id>.txt` | **no** | the body, in plaintext, for tools working inside the project |

**The body never enters Git.** That is what lets these repositories safely gain
a remote later — and a remote is anticipated, since `.itonami/project.edn`
already carries a `:west` note about adding one. It is pinned by a test that
greps `HEAD` for the body text and requires no hit, rather than by a comment
asking future readers to be careful.

The envelope — sender, subject, date, labels — **is** tracked, and that is a
deliberate line rather than an oversight. An artifact with no subject is not an
artifact; the project would gain a directory of opaque digests. The body is
where the volume and the sensitivity are, and it is what stays out. If a
deployment needs subjects out of Git too, that is a further decision and should
be made explicitly, not discovered.

### One commit per call, and only for what changed

`apply-rules!` re-runs on every sync. Committing per message would make filing
twenty messages twenty revisions; committing unconditionally would add an empty
revision to every project on every sync. So the writer groups by project, writes
once, and `apply-rules!` passes it only the assignments whose project actually
changed.

### The artifact is a projection; the decision is the assignment

`file-mail!` failures are caught, counted and reported in the response —
never thrown. The assignment is already recorded when the write is attempted,
and discarding a filing decision because Git was busy is the wrong trade. A test
redefines `file-mail!` to throw and asserts the assignment survives.

## What this caught

**A project made before this existed would have leaked.** `ensure-git-project!`
wrote `.gitignore` only `when-not (.isFile …)`. Every project created before mail
filing already had one, so that branch would never have run for them, and the
first filed body would have landed in tracked Git. The ignore lines are now
appended individually, and a test creates a project with the old two-line
`.gitignore` and asserts `.mail/` is added and stays untracked.

**Git's own noise was being read as data.** `run-command!` merges stderr into
stdout so a failure reports what git said — which means everything else git
writes to stderr is in that output too. On this machine git prints
`error: could not read IPC response` from its filesystem monitor. Read
literally, that made a clean tree look dirty and made `rev-parse HEAD` return a
"commit id" that was an error message. Porcelain is now matched by shape and the
SHA by pattern.

## Consequences

- Opening a project shows the mail that belongs to it, with a history of when
  each message was filed and by which rule.
- Verified on real mail from `jun784@gmail.com`: finance took 14 messages in one
  commit, travel 6, shopping 1; 21 envelopes tracked, 21 bodies ignored, and
  `git grep` over `HEAD` found no body text in any of the three.
- The tracked envelope carries a `sha256` of the body, so the committed record
  can be checked against the plaintext beside it — and a body that changed
  underneath is detectable rather than assumed.
- These repositories still have no remote and nothing pushes them. Sealed
  publication to DataLad continues to be the conversation pipeline's job;
  whether filed mail should also ride it is not decided here.
- Unassigning a message does not remove its artifact. The assignment is
  reversible; the history of having filed it is not, because rewriting a Git
  history to un-say something is a different and much larger decision.
