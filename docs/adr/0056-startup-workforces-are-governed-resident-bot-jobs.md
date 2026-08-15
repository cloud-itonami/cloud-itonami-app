# ADR-0056: Startup workforces are governed resident Bot jobs

Status: accepted, 2026-08-16

## Context

Eight startup repositories need durable roles such as business owner, product
manager, engineer, QA, marketer and financial chief. A title alone is not an
actor definition, and copying role policy into this app would create two
sources that can drift. Conversely, treating a role's semantic capability
policy as a tool grant would let a descriptive registry widen runtime
authority.

## Decision

`network-awai/loop-yakuwari` owns business membership, job templates,
responsibilities, cadence and capability decisions. Its `workforce` command
emits one deterministic EDN catalog. Cloud Itonami accepts only the complete
eight-business catalog and refuses unavailable, partial or unreadable input.

Provisioning is an idempotent reconcile per organization and human owner. Bot
IDs are deterministic, so conversation and run history survive reprovisioning.
Roles removed upstream are disabled, not deleted. The same job key owned by
another person is stored and scheduled independently.

Every projected Bot is a bounded system actor with exactly one admitted Git
repository. It receives no connector tools, browser, virtual shell, standing
write grant or omakase delegation. Responsibility and capability policy are
visible in the prompt, API and UI, but runtime tool admission continues to be
computed only from concrete grants. Existing write approval remains mandatory.

The resident tick uses deterministic initial staggering, fixed delay after a
submission and a global default of one new workforce run per tick. Each run is
a persistent Goal that advances exactly one bounded step or records a no-op.
It never crosses business repositories. The tick runs only for a live human
session and never creates, refreshes or impersonates one.

Resident inference is globally capacity-bounded to one active workforce job.
A tick may make at most two repository reads, the host admits at most four tool
calls, and only the first 1,600 characters of a tool result return to model
context; the complete result remains represented by its SHA-256 receipt. Tool
calls are sent sequentially to the OpenAI-shaped provider.

If that provider returns an empty final answer after at least one read receipt,
only a workforce tick terminates as a safe no-op. The record says that no write
or external effect occurred and cites the read receipt hashes; it does not
claim that the model interpreted the evidence or advanced the business. An
interactive turn, a tick without a receipt, and every other provider error keep
their ordinary failure semantics. This bounded terminal state prevents an
uninterpreted read from becoming an endless resident retry loop.

Provisioning is available only through the human, origin- and CSRF-protected
route. MCP exposes status but cannot provision. Residency therefore changes
latency, not authority.

## Consequences

- Eight businesses currently project to 70 visible, owner-isolated job Bots.
- Role policy has one canonical source and can be audited separately from the
  app's stricter execution grant.
- Missing registry checkout is an explicit service failure, not an empty or
  partially provisioned company.
- A machine without a live human session performs no workforce tick; durable
  jobs resume when that authority is present again.
- Production readiness still requires resident, CLI/MCP and browser evidence;
  unit tests alone do not prove the resident loop ran.
