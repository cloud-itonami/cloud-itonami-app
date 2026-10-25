# P2P service resolution replaces DNS in the hot path

Today an itonami inference call resolves `api.murakumo.cloud` through public DNS
and one hostname carries four separate jobs: naming (which provider), routing
(how to reach it), trust (who it is), and failover (what to try next). On
2026-10-25 this concentration failed measurably: the aiueos VM's upstream
resolvers flapped, `curl` reproduced `Could not resolve host: api.murakumo.cloud`,
and the henshu tick died after five turns of `transport: fetch failed`. The
provider was alive; the name was not. A single resolver path in the hot path is
a single point of failure.

The decision is to split those four jobs and resolve them P2P — the same
decomposition Holochain/hApp gets right (name entries are signed by agents, not
mapped by a directory service) — using components this workspace already owns:

- **Identity** is `did:key` (Kotoba ID) plus the bundle/manifest CID. A name is
  not authority, and a hostname is not identity.
- **Naming** is a signed service record — `{did, multiaddr, engine, model id,
  artifact digest, epoch}` — content-addressed so it verifies without any
  resolver. The kotobase naming plane holds only the mutable pointer to the
  current record (the four-plane split of ADR-2609092600: identity is
  immutable, naming is the pointer, bytes are content-addressed, entry is one
  hostname).
- **Transport** is the kekkai overlay: kekkai-node's mutually-authenticated
  Noise IK tunnels, with MagicDNS answered from the signed netmap. In-mesh
  names derive from signed state, so public DNS leaves the hot path entirely.
- **Authorization** stays yakuwari's capability decisions plus the Kotoba ID
  capability chain (Biscuit delegation); browser operations keep the shared
  client-runtime per-operation consent contract.
- **Liveness and failover** are signed heartbeats and inga quorum certificates
  with client-side failover in netmap order. No load balancer, and no D1 /
  Durable Object / KV as the adjudicator of head, ref or reachability
  (ADR-2608039000).

Bootstrap is the one problem P2P does not remove — DHTs need a rendezvous too —
so it is bounded deliberately: cold-start entry is (1) kekkai's netmap roots
(`kekkai desired publish --roots`) and (2) the signed netmap published at
kotobase; day-to-day updates ride peer exchange. `api.murakumo.cloud` is
demoted to the entry face: one of several bootstraps and the fallback, never
the authority.

Model and artifact downloads remain external operations under the shared
client-runtime contract: service records carry digests, an unexpected digest is
a refusal, and local inference stays the default.

## Phases

1. **P1 — records and resolution.** Murakumo fleet nodes publish service
   records into the netmap extension; itonami-agent resolves `model id → peer
   set` via `:placement {:mode :p2p}` / `:inference-rail :itonami-p2p` (the
   schema slots already exist) with client-side failover. DNS is demoted to
   fallback.
2. **P2 — auth.** Replace the `mk1.` bearer with did:key challenge-response;
   every run appends a signed receipt to the execution ledger.
3. **P3 — browser.** The app origin stays static; P2P runs over
   WebTransport/WebRTC via kotoba-net (io-libp2p) under the same per-operation
   consent contract.
4. **P4 — demotion.** `api.murakumo.cloud` is entry-face only.

## Constraints preserved

- No D1 / DO / KV in the arbiter seat. Chain `awai` is the order layer and is
  not used for name resolution.
- **"libp2p exists" is not "the runtime reaches it".** kotoba-net's connection
  paths still have JVM-only parts; reachability from kbb / itonami-agent is
  measured BEFORE any service record is advertised. If it does not reach, P1 is
  recorded as blocked — not silently degraded to DNS.
- The kekkai extension must not break kekkai's charter: the control plane
  publishes reachability and never carries a packet or pushes WireGuard config.
  Service records extend the netmap's fact set; they do not become a second
  control plane.

## Consequences

In-mesh calls stop depending on public DNS resolvers; naming becomes auditable
signed state with a lineage (the netmap ledger) instead of a directory answer.
The cost is the bootstrap path, the reachability qualification work, and a
second (signed) name publication step on every endpoint change.
