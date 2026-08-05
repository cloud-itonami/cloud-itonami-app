# ADR-0022: Annexed mail bodies are pushed to B2, already sealed

## Status

Accepted. 2026-08-05. Takes the step ADR-0021 named and did not take.

## Context

ADR-0021 put filed mail bodies in the project's git-annex as age ciphertext and
ended by saying no remote was configured and nothing pushed. That left the annex
holding bytes that existed on exactly one disk: a `git clone` of such a project
gets the pointers and nothing to resolve them with, and losing the machine loses
every body while the envelopes survive — the worst of both, because the
repository would still list mail it could no longer produce.

## Decision

Each project dataset gets Backblaze B2 as a git-annex special remote, and
`POST /api/projects/{project}/push` copies its annexed content there.

### The remote is `encryption=none`, and that is not a gap

git-annex can encrypt what it stores on a special remote. Here it does not, and
the reason is that the content it is handed **is already the ciphertext**. B2
receives exactly what B2 would have received if someone had uploaded the `.age`
file by hand. Turning on git-annex's own layer would add a GPG key to lose on top
of an age key already carrying the secrecy — and this workspace's rule for this
exact case says to annex the ciphertext and let the annex key be a *ciphertext*
identity, which `MD5E-s407--<md5 of ciphertext>.eml.age` is.

What B2 learns is: how many objects, each one's size, and that they are age
envelopes. Not a sender, not a subject, not a date. The envelopes — which do
carry subjects — are ordinary Git objects and are **not** annexed, so they never
reach B2 at all.

### One bucket, one prefix per project

`gftdcojp-m365-annex` is the workspace's existing annex bucket, already holding
the M365 mail archive; filed mail is the same kind of data with the same owner.
Every dataset in that bucket is separated by `fileprefix`, which is the
convention `scripts/datalad-b2-init.cljs` established and warns about: without
one, every dataset's keys land at the bucket root and nothing on the B2 side can
say which dataset an object belongs to.

The prefix is `cloud-itonami-mail/<organization-storage-id>/<slug>/`. The
organization segment is what stops two organizations that both have a project
called `finance` from writing into each other's space.

**A dedicated bucket would have been better and was not possible.** Creating one
needs the B2 master key, which lives in 1Password, and `op` cannot sign in on
this machine. Sharing an existing bucket is the compromise; it is recorded here
rather than discovered later.

### `git annex copy`, not `datalad push`

The envelopes are ordinary Git objects and there is no git remote to push them
to; only the bodies need somewhere else to live. `datalad push` would attempt
both and report a failure for the half that was never configured, which reads as
"the push failed" when the push succeeded.

### Credentials

Environment first (`B2_KEY_ID` / `B2_APP_KEY`), then the macOS Keychain item
`b2:gftdcojp-m365-annex` — the same order and the same item the rest of this
workspace uses. Nothing is written to the repository: git-annex keeps them in
`.git/annex/creds`, which is not committed, so a clone must supply them again to
enable the remote. With none, `ensure-remote!` refuses and names both places
rather than failing at the copy.

## What building it caught

**The same defect, in the same session, for the third time.** `run` merges
stderr into stdout so that a failure reports what the command said. This
machine's git prints `error: could not read IPC response` from its filesystem
monitor, and those lines are indistinguishable from output. Here they were being
*counted*: `git annex find` listing one file was counted as two, and the derived
"unpushed" then claimed a body had not reached B2 when all of them had — a status
surface confidently reporting data loss that had not happened.

`run` now takes `:merge-stderr?`, true where the output is an error message and
false wherever it is parsed or counted. That this recurred after being fixed
twice is the argument for the flag rather than for remembering.

## Consequences

- Verified against real B2: pushed a filed body, `git annex whereis` reported
  **2 copies** (local and `[b2]`), dropped the local copy, and `git annex get`
  retrieved it from B2 alone as a valid age envelope. The probe object was then
  dropped from B2.
- `GET /api/projects/{project}/remote` answers with `annexed`, `pushed` and
  `unpushed` — the last being the number that matters, since it counts bodies
  that exist only on this machine.
- `itonami projects push` and `itonami projects remote` appeared with no CLI
  code written (ADR-0018 registry).
- **Pushing is explicit, not automatic.** Filing mail does not reach the network;
  someone or something must ask. Whether a sync should push is a policy question
  about somebody's mail leaving their machine, and it is not answered here.
- **B2 holds ciphertext this app cannot decrypt.** The age identity is in kagi
  and the Keychain, not in the bucket and not in the app. Losing it makes the
  bucket copy as unreadable as the local one — B2 is durability against disk
  loss, not against key loss.
- The bucket is shared with `gftdcojp`'s M365 archive. A dedicated bucket, and a
  key scoped to it, is the better shape and needs the master key from 1Password.
