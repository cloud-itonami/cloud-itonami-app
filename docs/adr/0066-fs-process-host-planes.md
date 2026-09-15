# ADR-0066 — Host planes are kotoba-lang/fs and kotoba-lang/process

## Status

Accepted (2026-08-20)

## Context

Product code still reached ambient `java.nio.file.Files` and `ProcessBuilder`
for durable state and virtual-shell docker. That is the same ambient-authority
class as ambient WebAuthn/JVM crypto that ADR-0065 removed from the identity
axis. kotoba-lang already had `fs` (with `fs-host` jail). `process` did not
exist as a foundational twin; only `provider.process` (KIR-valued) and
`capability-process-list` (list, not spawn).

## Decision

1. Depend on `kotoba-lang/fs` and new `kotoba-lang/process` for host I/O and
   spawn. Catalog identity for spawn remains `:process/spawn` (wire id 20);
   authority package is `capability-process-spawn` (contract-only).
2. `cloud.itonami.app.host` is the only place that builds `IFilesystem` /
   `IProcess` handles for this app (root + binary map).
3. Proof cutovers: `store/persist!` writes through confined fs + atomic
   rename; `virtual-shell` spawns `docker` / `id` through `IProcess` with
   basename argv0 (no PATH).
4. Bulk remaining `ProcessBuilder` / `Files` call sites migrate along the same
   seam; they are not ambient-by-design.

## Consequences

- Docker create/exec argv uses basename `docker`; absolute path lives only in
  the host binary map.
- `process/max-argv` is 64 so OCI create lines fit.
- Repository-mode state files use the parent directory as the fs jail root.
