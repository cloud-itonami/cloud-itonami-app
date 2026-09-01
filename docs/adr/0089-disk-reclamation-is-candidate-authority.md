# ADR-0089: disk reclamation is candidate authority

**Status**: accepted / **Date**: 2026-09-01
**Related**: ADR-0034, ADR-0056, ADR-0060, ADR-0070

## Context

The resident Disk Maintainer had two capabilities: observe the data volume and
run one fixed cleanup helper. That contract was safe and provider-independent,
but repeated successful runs could leave the host below its 20 GiB floor. The
remaining large objects were not members of the helper's fixed allowlist.

Giving the Bot a path argument, a shell, or a general delete tool would turn a
capacity problem into filesystem authority. A model-selected pathname is not
evidence that the object is regenerable, unused, outside Git, or still the
object that was inspected.

## Decision

Disk maintenance has four separately admitted capabilities and tools:

| capability | tool | effect |
|---|---|---|
| `:disk.inspect` | `disk_space_status` | observe the data-volume floor |
| `:disk.cleanup` | `disk_space_cleanup` | run the existing fixed helper mode |
| `:disk.candidate.inspect` | `disk_space_inventory` | mint bounded candidate receipts |
| `:disk.reclaimable.cleanup` | `disk_space_reclaim` | reclaim receipt IDs after revalidation |

Inventory has no path input. It inspects `/private/tmp`, the exact per-user uv
cache, and the aggregate Cloud Itonami resident-release footprint. The release
footprint is observation-only and always `review-required`; current and
rollback releases are never promoted to autonomous deletion authority. A
receipt ID binds the canonical object identity, class, byte count and
modification evidence. A receipt may show a root-relative audit locator so a
review-required object is identifiable, but reclaim never accepts that locator
(or any path) as mutation authority.

Reclaim accepts only 1–8 unique receipt IDs, only while the disk is below the
floor, and at most 10 GiB of planned candidates per cycle. It re-discovers each
ID and checks it again immediately before mutation. Unknown or stale IDs,
symlink roots, tracked Git content, open-file evidence, inspection failure and
unknown classes fail closed. A dependency tree inside a worktree is eligible
only when Git itself proves the exact tree is ignored and `git ls-files` proves
it contains no tracked files; the worktree and its source remain outside the
mutation target.

Automatic classes are temporary package stores, standalone temporary
`node_modules` with both package and lock manifests, qualified CMake build
trees whose source still exists outside the build, and the uv cache through
uv's own cache manager. Model artifacts such as GGUF are reported as
`review-required`; they are not automatically deleted. Resident releases are
reported as an aggregate review surface but remain outside mutation authority.
Repositories, worktrees, DataLad, sessions, documents, databases, browser
profiles, toolchains and OS storage are outside the authority.

The resident cycle remains deterministic: status, fixed cleanup, inventory
only if pressure remains, bounded reclaim only if reclaimable receipts exist,
then two settled capacity observations. Provider availability cannot prevent
the recovery path. A completed run reports pressure honestly; completion does
not mean the floor was restored.

## Consequences

The Bot can recover more regenerable space without becoming a general-purpose
filesystem actor. Inventory and reclaim are distinct grants, so visibility of
a candidate does not imply permission to mutate it. New classes require code,
tests and a reviewed classifier; they cannot be introduced through a prompt or
tool argument.

The candidate ID is deliberately short-lived. Normal writes can invalidate it,
which produces a safe stale-receipt failure and requires a new inventory. This
cost is preferable to deleting an object whose evidence has changed.
