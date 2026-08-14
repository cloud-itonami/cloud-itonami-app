# ADR-0016: Use mailboxes as the messaging authority

## Status

Accepted.

## Context

Cloud Itonami exposed Chat, a Microsoft 365 mail archive, Background WorkerRuns,
AgentRuns, and OrganismWorkers as adjacent surfaces. They did not form one
conversation model. Chat was local UI state, mail was an imported archive, and
artificial performers had no addressable inbox through which a person or another
performer could communicate with them.

A Slack- or Teams-like projection is useful, but making a channel the durable
authority creates the wrong boundary. Delivery, admission, read state, and trust
belong to each recipient. This is especially important when a recipient is an
agent: an untrusted string must not silently become model context or an execution
instruction.

## Decision

Add an organization-scoped mailbox ledger and project conversations over it.
Every durable principal has one mailbox address:

| Principal | Address form | Notes |
|---|---|---|
| Human member | existing user ID | Passkey session owns the mailbox |
| Agent session | `agent:<session-id>` | bearer-authenticated, organization-bound |
| OrganismWorker | `organism:<worker-id>` | durable address; transport adapter remains separate |
| Bot | `bot:<bot-id>` | named persistent peer (ADR-0041); same-owner only |

Restart-ephemeral Background WorkerRuns are deliberately not addressable. They
may produce receipts, but they do not become organizational identities merely by
running a prompt.

A `Conversation` is a direct, group, or channel membership projection. A
`Message` is immutable content metadata. A `Delivery` is the per-recipient state
for that message:

```text
Message ── fan-out ──► Delivery(alice, accepted/read)
                   ├─► Delivery(agent:ops, quarantined)
                   └─► Delivery(organism:reviewer, accepted/read)
```

This makes groups well-defined without a special group inbox. Membership says
who is addressed; each member's own trust rule determines admission.

### Trust and execution boundary

Sender trust is an exact, per-mailbox allowlist and defaults to deny. Conversation
membership does not imply trust. Untrusted deliveries enter quarantine, where
the recipient projection exposes only sender, time, conversation, and a content
digest. It never exposes the body and therefore cannot supply it to an agent's
context. Explicitly allowing that exact sender promotes their existing
quarantined deliveries. Revocation is prospective.

Messages always have type `message`. They are not tool calls, approvals, or
execution authority. A later agent adapter must parse a typed intent and pass the
existing capability/approval gates before any effect is possible.

The initial store retains one canonical message record for the sender's history
and auditability. “Remove untrusted messages” therefore means removal from the
recipient and model projections, not physical erasure of the sender's record.

### Encryption truth

The ledger supports two explicit modes:

- `local-plaintext`: ordinary app composition, stored locally as plaintext and
  always reported as `e2ee? false`;
- `signal-v1`: an opaque envelope encrypted by a client and reported as E2EE.
  Cloud Itonami stores and routes it but does not claim to have encrypted it.

The browser implements the application profile `itonami-signal-v1`:

- non-extractable Ed25519 signing, X25519 identity, signed-prekey, one-time
  prekey and ratchet private keys are structured-cloned into origin-scoped
  IndexedDB;
- the server verifies signed-prekey signatures, publishes public device
  directories, consumes each one-time prekey at most once and remembers
  consumed IDs across device re-registration;
- X3DH establishes a per-device session and a bounded Double Ratchet advances
  message keys, persists skipped keys, and caps a malicious gap at 100;
- users compare a SHA-256 safety-number fingerprint out of band. First use and
  changed keys stop encryption/decryption until explicitly verified;
- non-direct conversations use a per-sender, per-device sender-key epoch. The
  sender key is distributed through pairwise ratchets and the membership hash
  is authenticated; membership changes create a new epoch.

This is a Cloud Itonami wire profile built from the published X3DH, Double
Ratchet, and Sesame design, not byte-for-byte interoperability with the Signal
service or `signalapp/libsignal`. It uses separate Ed25519 signing and X25519 DH
identity keys, is not yet PQXDH, and does not claim post-quantum security.
Kagi sealing protects a configured remote vault/storage path; it is not the live
messenger protocol and is not presented as such.

### External OrganismWorker transport

An owner/admin explicitly issues or rotates one 256-bit bearer credential for
an active assignment. The clear token is atomically written to a 0600 file in
Tamaki's private workplace; app state stores only SHA-256. Authentication
resolves it to exactly `organism:<worker-id>` and re-checks assignment,
organization, and active status on every request.

The external-supervisor client polls with an opaque cursor, replies to a
conversation, updates its exact sender allowlist, registers public prekeys and
acknowledges only after its own checkpoint. Neither request nor CLI accepts an
arbitrary mailbox owner. Polling never turns a message into an intent or effect.

## Canonical EDN root

```clojure
{:messenger
 {:organizations
  {organization-slug
   {:conversations {conversation-id conversation}
    :messages {message-id message}
    :deliveries {[message-id recipient-id] delivery}
    :trust {recipient-id {sender-id trust-rule}}
    :devices {principal-id {device-id public-prekey-record}}}}}}}
```

The messenger UI is one projection of this ledger: conversation rail, thread,
composer, mailbox trust controls, and quarantine metadata. HTTP operations use
the active organization and authenticated mailbox principal; clients cannot
select an arbitrary recipient mailbox.

## Consequences

- Direct messages, groups, and channels share one delivery model.
- Humans and durable artificial performers can be members without pretending
  that an artificial performer is a Person.
- Unknown senders fail closed and their bodies stay out of recipient/model
  projections.
- Agent bearer sessions can use the same API as human sessions under their own
  address.
- An issued OrganismWorker transport can poll and reply without impersonating a
  human or selecting another mailbox.
- Signal mode is the composer default and fails closed when WebCrypto, a public
  device, or explicit safety-number verification is missing. Plaintext remains
  a visibly separate manual option.
- Loss of IndexedDB loses that device and its sessions. Recovery is device
  removal/re-registration plus safety-number re-verification, not server-side
  private-key recovery.

## Session closure record

- Date: 2026-08-05
- Scope: `cloud-itonami-app` messenger and external AO worker integration work completed in this session.
- Covered decisions: mailbox-ledger model with per-mailbox trust, quarantine,
  AO bearer transport, Signal profile browser implementation, and signal-key/ratchet
  lifecycle handling.
- Result: recorded in this ADR and ready for handoff/closure.
