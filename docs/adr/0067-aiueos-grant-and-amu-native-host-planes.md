# ADR-0067 — Host planes sit under aiueos grants; word-typed decisions run on amu native

## Status

Accepted (2026-08-20)

## Context

ADR-0066 put durable write and process spawn behind `kotoba-lang/fs` and
`kotoba-lang/process`. ADR-0037 proved one decision core (`policy.kotoba`) as
signed kexe on the host ISA via amu's loader. Production oracle still answers
through KIR on the JVM because most real exports take `[:ref …]` records.

aiueos already names the host imports this app needs:

- `:host/process` ↔ capability `:process/spawn` (wire id 20,
  `capability-process-spawn`)
- `:host/filesystem` ↔ `:fs/read` / write surfaces in the catalog

Those imports are tenders, not Docker. The Bot "virtual computer" (ADR-0051) is
still a networkless OCI container on the *host* Docker engine. aiueos does not
replace that runtime; it decides whether a component may reach for spawn/fs at
all.

## Decision

1. **Layering (fixed)**

   | Layer | Owns | Does not own |
   |---|---|---|
   | `.kotoba` decision cores | may / refuse judgements | docker, paths, argv |
   | amu native (kexe) | word-typed exports in the default suite | production oracle (yet) |
   | `cloud.itonami.app.host` | `IFilesystem` / `IProcess` handles | ambient Files / PATH |
   | aiueos grant set | whether spawn/fs may be invoked | how Docker isolates a Bot |
   | Docker / OrbStack | Bot shell isolation (ADR-0051) | identity / peer judgements |

2. **Native canary expands by one word-typed core.** `identity_core.kotoba`
   (`:bool` → `:bool`) crosses a kexe export. It joins `policy.kotoba` in the
   default suite as a *second one-core canary*, not a full sweep. Record-typed
   cores (`peer_core`, `handoff_core`, …) stay interpreter-only until an export
   boundary for aggregates exists (ADR-2608110200). Production
   `kotoba-oracle/call` remains KIR.

3. **Optional grant gate on the host seam.** When a caller binds
   `cloud.itonami.app.host/*granted-capabilities*`, missing `:process/spawn`
   or `:fs/write` fails closed. Unbound / nil means today's JVM desktop host
   (no tender yet) — fail-open only in that legacy mode, documented here so
   it is not mistaken for aiueos admission.

4. **Out of scope for this ADR:** running OCI create/exec as bare-metal
   aiueos kernel syscalls; flipping production oracle to native; amu process
   capability kit (catalog + stdlib twins already cover the zero-kir path).

## Consequences

- Live verification of ADR-0051 stays on Docker: create → exec → inspect
  (network=`none`, ReadonlyRootfs, CapDrop=ALL) → rm, through `IProcess`.
- Peer messaging judgements stay on the peer oracle (KIR); they are not
  native-canaried here.
- A future aiueos-tendered desktop build binds the grant set before any Bot
  shell or store write. Tests assert both directions of the gate.
