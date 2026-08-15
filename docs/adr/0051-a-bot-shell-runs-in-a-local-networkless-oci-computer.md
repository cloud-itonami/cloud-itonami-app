# ADR-0051: A Bot shell runs in a local, networkless OCI computer

**Status:** accepted — 2026-08-15

## Context

ADR-0036 correctly refused an unreviewed cloud PC, but its blanket refusal of
virtualization left a different hazard: adding a general shell would otherwise
run directly on the host. The product now needs several Bots to share one PC
for local folders, Git and coding without sharing one ambient shell authority.

## Decision

`:bot/virtual-shell?` grants one Bot a dedicated local OCI container. It is not
remote compute. The container has `network=none`, a read-only root filesystem,
all Linux capabilities dropped, `no-new-privileges`, 1 CPU, 1 GiB memory and a
256 PID ceiling. It receives no host environment, credentials or Docker socket.
The only host mount is the exact standalone Git root already admitted for that
Bot, at `/workspace`.

Every `virtual_shell` call is a write effect and therefore stops at the existing
human approval card. Commands are bounded to 600 seconds and 32,000 output
characters. The host invokes Docker with a fixed argv vector; `/bin/bash -lc`
exists only inside the container. Cancelling stops only that Bot's container.

The PC admits two shell processes globally. A fair lock keyed by canonical Git
root serializes Bots that share a repository, so two agents cannot concurrently
mutate one worktree. Linked Git worktrees are refused because their `.git`
metadata lives outside the granted root; use a standalone clone.

Network egress, host credential injection, Docker socket mounting, arbitrary
host paths and remote Git writes are outside this decision. They require a
separate capability and review boundary rather than a broader shell toggle.

## Verification

- Contract tests assert the container argv carries every isolation limit and
  that a command is one guest argv item rather than host-shell text.
- Two live Bot identities running `sleep 1; git status` against one root took
  about 3.1 seconds, demonstrating workspace serialization.
- Docker inspection confirmed network `none`, read-only root, capability drop,
  no-new-privileges, 1 GiB memory, 256 PIDs and only `/workspace` mounted.
- Cancelling a 60-second command stopped its container in about one second with
  exit 137.
