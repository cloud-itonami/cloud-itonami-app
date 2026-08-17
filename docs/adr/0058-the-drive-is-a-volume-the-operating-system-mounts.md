# ADR-0058: The Drive is a volume the operating system mounts

## Status

Accepted. 2026-08-17.

## Context

The Drive had a web surface and `folder_sync` (ADR-0027), which copies bytes
between an ordinary local directory and the Drive. What it did not have was a
way to *be* a disk: nothing appeared in Finder, and no application could open
a Drive document with a normal file path.

Measured 2026-08-17: this app had no File Provider extension, no FUSE, no NFS
and no SMB, and neither did anything else in the workspace.

## Decision

1. **The Drive is exported over NFSv3**, using `kotoba-lang/org-ietf-nfs`.
   The surface was chosen by measurement rather than preference — see
   ADR-2608171200 in the superproject. On macOS it is the only one that
   mounts with no kernel extension, no third-party install, no code signing
   and no root; FUSE needs macFUSE installed, a File Provider extension needs
   Swift in a signed app, and SMB needs NTLMSSP and is an addition to a
   working NFS rather than an alternative.

2. **`cloud.itonami.app.drive-fs` is the join.** `nfs.v3/IFilesystem` is
   thirteen questions about a tree of bytes; the Drive answers all thirteen.
   `org-ietf-nfs` depends on nothing here, this app depends on both, which is
   the arrangement `documents` already uses for `drive` and the office
   surfaces.

3. **A handle is a Drive item id.** NFS requires a handle to keep meaning the
   same file across server restarts, because a client does not know the
   server restarted. `drive` already has stable ids; an implementation that
   handed out array indices would produce stale handles days later.

4. **Every read and write goes through `drive.object`.** The mount is not a
   second door into the Drive: a share that expired is refused here for the
   same reason it is refused in the browser, and NFS mode bits are derived
   from `ws/effective-role` rather than being a second opinion.

5. **`rm` trashes, it does not purge.** `ws/trash` is reversible. A mount
   must not be the one irreversible path into a Drive; emptying the trash
   stays a decision someone makes in the app.

6. **Authorization is `kekkai`, because NFSv3 has none.** `AUTH_SYS` carries
   a uid the *client* chose. Two things answer that:
   - the bind address, loopback by default — and **a non-loopback bind
     without a netmap policy throws**, because an unauthenticated NFS export
     on a LAN is an open Drive and falling back to loopback would silently
     ignore what the operator asked for;
   - `kekkai.acl/edge-allowed?` when a policy is configured: pure,
     deny-by-default, port-granular. The admitted principal is **the node the
     netmap says the address is**, not the uid the client claimed.

   What kekkai deliberately does not decide is read-versus-write; its charter
   authorizes reachability and never what flows. That granularity is the
   Drive's ACL, which decision 4 already routes through.

7. **Off unless configured.** `:nfs {:enabled? true :actor …}`. Not inferred
   from anything: a Drive that becomes network-reachable because a port
   happened to be free is the failure this is written to avoid.

## What does not map cleanly, stated rather than discovered

- **The Drive stores whole objects; NFS writes at an offset.** Every partial
  write is read-modify-write and costs the whole file. `:max-file-bytes`
  (64 MiB default) refuses above a ceiling rather than letting the mount
  become mysteriously slow.
- **A new file has no bytes until the first write.** `drive.object` has no
  empty version and `upload!` refuses zero bytes, so `-create` makes the item
  and nothing else; `-attrs` reports size 0, which is what a client expects.
- **`drive` records only `:drive/created-at`.** NFS wants three times.
  Reporting the creation instant for all three is honest; reporting `now`
  would make every file look modified on every listing.
- **READDIR is sorted by title.** `ws/children` returns insertion order,
  which is right for the model and wrong for a cursor — a file created
  between two READDIRs would shift every later entry and the client would
  skip or repeat one.

## Verification

- `cloud.itonami.app.drive-fs-test`: 9 tests, 49 assertions. Verified to
  discriminate: admitting any address without a policy fails it, and making
  `rm` purge instead of trash fails it.
- Live, 2026-08-17, against a real macOS mount:

  ```
  mount_nfs -o vers=3,tcp,port=12050,mountport=12050,nolocks,soft \
    127.0.0.1:/kotoba /tmp/drive-mnt
  127.0.0.1:/kotoba on /private/tmp/drive-mnt (nfs, nodev, nosuid, mounted by junkawasaki)
  ```

  A folder and a document created through `drive.workspace` appeared as a
  directory and a file. Writing, creating, nesting, renaming and removing
  through the kernel client all landed in the Drive, and the Drive recorded a
  **version per write** (`[0 obj-9350…] [45 obj-1954…]` for a file opened
  with `>`, which truncates first).

- **The whole chain, end to end.** With
  `CLOUD_ITONAMI_DRIVE_OBJECT_STORE=kotobase` (ADR-0057), a file written into
  the mounted volume produced
  `bafkreihbfo374yy4mz2w3sli2azqfw3ea73cnr3jm5gzqvub7wsh57uoxa` as its
  `:drive/object-ref`. An unauthenticated `GET https://kotobase.net/ipfs/{cid}`
  returned the exact bytes (HTTP 200, 52 bytes), and `ipfs add -Q
  --cid-version=1 --raw-leaves` over the same content printed the same CID.

  A file saved in Finder is an IPFS CID in the kotobase archive, and nothing
  in between had to be exported.

## Consequences

- The Drive can be opened by any application, with a path, without this app
  being involved in the open.
- **Mounting is a command, not a click.** `nfs://host:port/path` does not
  work — Finder's Connect to Server fails with NetFS −5014 and never opens a
  connection, because it wants a portmapper on port 111 that would need root.
  `nfs-service/mount-command` prints the line; running it is the operator's
  action, since mounting changes the machine.
- A mount point under `/Volumes` still needs root, because `/Volumes` is
  root-owned. Anywhere the user owns works without it.
- **No locking.** NLM is not implemented, so mounts use `nolocks`. Two
  clients writing one file concurrently is not a case this stands behind yet.
- Read-modify-write means a mounted Drive is not a place to put a database
  file, a disk image, or anything else written in small pieces.
