# ADR-0021: The body goes into Git, encrypted with age and held by git-annex

## Status

Accepted. 2026-08-05. Reverses the exclusion decided in ADR-0020; keeps its
boundary but moves the line from *exclude* to *encrypt*.

## Context

ADR-0020 put a filed message's envelope in Git and kept its body out, in a
git-ignored `.mail/` directory. The reasoning was the boundary
`project-repository` already drew for conversation history, and it was sound as
far as it went.

It went one step too far. Keeping the body out of Git kept it out of everything
Git gives you: it did not travel with the project, it appeared in no history, a
clone did not carry it, and a `datalad push` would never have moved it. The
project held a record *about* a message and not the message. Owner direction,
2026-08-05: **put the body in too, and encrypt it instead.**

## Decision

Both halves are tracked. The body is tracked as ciphertext.

| Path | In Git as | Holds |
|---|---|---|
| `mail/<yyyy>/<mm>/<id>.edn` | ordinary Git object | envelope, labels, `sha256` of the plaintext, which rule filed it |
| `mail/<yyyy>/<mm>/<id>.eml.age` | **git-annex** pointer | the body, encrypted to this deployment's age recipients |

### Why age, and why annex

**age** because this workspace already encrypts mail at rest that way — the
Gmail archive under `m365-archive/gmail/mailboxes/` is `*.eml.age`. Reusing the
convention means one identity opens both.

**git-annex** because a body is the large part. Annexed content is a pointer in
Git and bytes in the object store, so a clone stays small and content can be
pushed to an encrypted special remote (B2) later without the repository itself
carrying every mail ever filed.

The project repository becomes a DataLad dataset on first filing:
`datalad create --force -c text2git`. `--force` because the repository already
exists with commits; `text2git` is the part that decides what lands where —
envelopes stay readable Git objects, binary ciphertext goes to the annex.
Annexing the envelopes too would turn every project's mail index into a
directory of symlinks nobody can read in a `git show`.

Committing is `datalad save`, not `git commit`. `git commit` would work and would
store the ciphertext as an ordinary blob — the one outcome this design exists to
avoid, and invisible until the repository had grown by every mail it holds.

### Fail closed

With no recipients configured (`CLOUD_ITONAMI_MAIL_AGE_RECIPIENTS` or
`…_RECIPIENTS_FILE`), the body is **not** written in the clear as a fallback.
The envelope lands, the body is skipped, and the skip is reported with its
reason. A filing system that silently downgrades to plaintext is worse than one
that refuses, because nothing about the result looks different.

### What is still readable in Git

The envelope: sender, subject, date, labels. That is deliberate and unchanged
from ADR-0020 — an artifact with no subject is not an artifact. Verified on real
mail: searching `HEAD` for words that appear only in bodies (`コンビニ払い`,
`銀行振込`, an amount) finds nothing, while subjects are present because they are
envelope fields.

The envelope also carries a `sha256` of the *plaintext*, so a decrypted body can
be checked against the committed record. It is a plaintext identity in a tracked
file, which is a confirmation oracle for a body someone already guessed. For
mail filed into a local project that is an acceptable trade; a deployment where
it is not should say so and drop the field.

## What building it caught

- **Filing was not idempotent, and annex made that fatal rather than untidy.**
  An annexed file is a read-only symlink into `.git/annex/objects`, so re-filing
  a message did not overwrite it — it failed with `permission denied`. Filing now
  skips a message already filed to the same project and deletes before rewriting
  one moving between projects. This also removes real churn: age uses a fresh
  ephemeral key every time, so identical plaintext yields different ciphertext
  and every re-run would otherwise add a revision holding nothing new.
- **`datalad save` signed as the operator.** `git-commit!` had always passed
  `-c user.name/user.email` so that app-made commits carry the app's name;
  DataLad has no `-c` and picked up the machine's git config, signing project
  commits as the owner's personal iCloud relay address. The identity now travels
  as `GIT_AUTHOR_*`/`GIT_COMMITTER_*` environment for both DataLad calls, and a
  test asserts the author.
- **`age-binary` lacked the blank guard `datalad-binary` has had since it was
  written.** With no override the first candidate is `""`, `(io/file "")`
  answers `canExecute` true on this JVM, and the result was `Cannot run program
  ""` surfacing as "the body could not be written".

## Consequences

- Verified end to end on real mail from `jun784@gmail.com`: 14 messages filed
  into `finance` in one commit, 28 paths tracked (envelope + body each), every
  body an `age-encryption.org/v1` envelope behind a git-annex symlink, no
  body-only text anywhere in `HEAD`, and the bodies decrypt to the original.
- `.mail/` stays in `.gitignore`. Projects filed into under ADR-0020 may still
  hold plaintext there; nothing writes it any more, and it must not start being
  tracked now that `mail/` is.
- **These repositories can no longer be removed with `rm -rf`.** git-annex object
  directories are read-only by design; deleting one needs `chmod -R u+w` first.
  This bit the test fixtures before it could bite an operator.
- ~~No remote is configured and nothing pushes.~~ **Done in ADR-0022
  (2026-08-05):** each project dataset takes B2 as a git-annex special remote and
  `itonami projects push` copies its bodies there. Note the correction: that ADR
  uses `encryption=none` rather than layering git-annex's own encryption, because
  the content handed to the annex is already age ciphertext and a second layer
  would be a second key to lose rather than more secrecy.
- Losing every age identity means losing every filed body. The ciphertext is in
  Git and the key is not; that is the point, and it is also the risk. Key custody
  is a deployment concern this ADR does not decide.
