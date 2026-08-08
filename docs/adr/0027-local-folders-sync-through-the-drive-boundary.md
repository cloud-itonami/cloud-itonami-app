# ADR-0027: Local folders synchronize through the Drive boundary

## Status

Accepted. 2026-08-08.

## Context

Cloud Itonami already had two relevant but separate mechanisms. Drive stored
files, folders, versions, ACLs and content-addressed objects. Repository storage
sealed application state and published it through DataLad before advancing a
Kotobase head. Neither mechanism watched an ordinary user directory, and the
read-only Microsoft 365 archive adapter was not a synchronization client.

A Dropbox-like client must also answer the losing-write question. File mtime is
not a common ancestor, and last-writer-wins would silently discard either a
local edit or a Web edit.

## Decision

Each configured root records, per relative path, the last local SHA-256 and
remote Drive ETag known to contain the same bytes. A finite reconciliation tick
compares both copies with that pair:

- one changed side propagates to the other;
- equal concurrent content converges without a conflict;
- different concurrent content keeps the local file and writes the remote file
  under `.itonami-conflicts/<time>/`;
- a remote deletion moves an unchanged local file to
  `.itonami-trash/<time>/`;
- a local deletion trashes an unchanged Drive item;
- editing the local file after a conflict is the explicit resolution that may
  replace the remote copy.

The state journal is atomically replaced after every successful path mutation.
Symbolic links are not followed, relative paths and remote names are validated,
and the conflict/trash trees are excluded from scanning.

`RemoteDrive` is the transport seam. The in-process adapter uses the existing
`documents` and `drive.object` boundaries. The HTTP adapter uses only the
authenticated Cloud Itonami Drive API and reads its bearer token from a named
environment variable. It receives no Storj, DataLad or Kotobase credentials.

Synchronization is disabled by default. Starting the app starts one bounded
fixed-delay worker only when an operator configured roots. The authenticated
status and manual-wake routes select roots by their configured local actor and
reveal root IDs and counts, not local paths or tokens. The hosted bearer token
selects the remote principal independently; it does not replace that local
ownership binding.

## Consequences

- Ordinary nested files can now move bidirectionally between local disk and a
  hosted Cloud Itonami Drive.
- Existing Drive ACL, quota, object-store and trash semantics remain the remote
  authority.
- Conflicts and deletions consume local recovery space until a person removes
  them.
- An empty file is still refused by the existing Drive upload contract. The
  sync fails visibly for that root; it never omits the file and reports success.
- Rename optimization, selective placeholder hydration and block-level binary
  delta transfer remain future optimizations. Current semantics are correct but
  replace a changed remote object as a complete file.
