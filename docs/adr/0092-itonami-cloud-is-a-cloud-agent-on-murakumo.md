# ADR-0092: itonami.cloud runs as a cloud-agent on murakumo's WASM lattice, talking to Basho and Hokusai

**Status**: accepted · 2026-09-02 · owner instruction「itonami.cloud を local-agent
ではなく cloud-agent として murakumo.cloud を使って機能するように整えて. api は
glm 5.3 で baseten or together backed で serverless が可能な方で. basho, hokusai
モデルを利用. basho に関しては常駐しても月額 20 万円を超えない構成に」and the
owner's correction the same day:「murakumo は wasm cloud 的に動く設計になっていなかったっけ」.

Root record: `90-docs/adr/2609021200-itonami-cloud-agent-basho-glm53-serverless-budget.edn`
(superproject). Gateway contract: `90-docs/deployment/awai-murakumo-basho-hokusai-routing.edn`.

## Context

Until now the agent behind itonami.cloud was a **local-agent**: this JVM
resident on the owner's Mac, started by a LaunchAgent
(`~/.gftd/bin/itonami-app-resident.cljs`), bound to `127.0.0.1:1338`. The
public hostnames (`app.` router, `hooks.`, `mcp.`, `bots.`) are Cloudflare
Workers, but the Goals / Assignments / approvals authority — the resident —
went away when that laptop slept.

murakumo.cloud is not a VM farm. `kotoba-lang/murakumo` is the **control plane
for the kotoba WASM lattice** across the Mac-mini fleet: libp2p gossipsub
nodes (`kotoba-server`) host content-addressed WASM components placed by
auction, fire their `on-http` / `on-tick` / `on-kse` triggers, and persist
their `kqe-assert!` output to the node's Datom log. Desired state is a wadm-style
manifest (`murakumo.app.edn`) that a reconciler converges. Two actors already
live there with the same split of duties this ADR adopts — **kenchi** (valuation
records served on-mesh, ingest off-mesh) and **minidrama** (profile + heartbeat
on-mesh, governed pipeline off-mesh), both probed live on `asher` 2026-07-07.

The first draft of this ADR proposed placing the JVM resident on a fleet node.
That was the wrong substrate: the fleet nodes have no JVM (measured 2026-09-02 on
judah / simeon / asher), and murakumo's unit of residency is a WASM component,
not a process.

Measured 2026-09-02:

| fact | value |
|---|---|
| lattice (`murakumo ops status`) | 12 nodes, 9 reachable, `kotoba-server` up on levi / joseph / dan (wasm executor ready), 8 peered, 0 LaunchAgents loaded, hosted CIDs on dan (2) |
| GLM-5.3 on Baseten Model APIs | serverless, $1.40 / $4.40 per Mtok in/out, `zai-org/GLM-5.3` |
| GLM-5.3 on Together | serverless, same price, same id |
| GLM-5.3 shape | 753B-A40B — a dedicated always-on replica is ≥ 8×H100 ≈ $52/h |
| USD/JPY | ≈ 160, so 200,000 JPY ≈ $1,250 / month |
| `capability-llm-infer` | provider status `contract-only` — a guest cannot yet be admitted to call a model |
| `~/.local/bin/kotoba` on the operator Mac | points at a removed `/tmp` binary; `kotoba app deploy` cannot run from here today |

## Decision

1. **Residency = a lattice app.** `mesh/itonami.app.edn` declares the
   itonami cloud-agent's ON-MESH surface: `agent-profile` (`on-http`
   `/mesh/http/itonami/profile`, the agent's identity record — residency,
   ingress, the models it speaks through murakumo) and `agent-heartbeat`
   (`on-tick` every 5 min, a resident-liveness datom). Both only serve and
   attest facts the resident asserted; they decide nothing. murakumo's
   `murakumo.app.edn` registers the app (`:replicas 2`, JP edge tribe,
   `:needs-build` until the first deploy records a CID). That is what makes
   itonami.cloud a cloud-agent: its presence and liveness are hosted by the
   lattice, placed by auction, content-addressed, independent of any one
   machine including the owner's.
2. **The JVM resident stays the off-mesh authority** for Goals, Assignments,
   grants, approvals, replay and every Bot *turn* (ADR-0077), exactly as
   kenchi's ingest and minidrama's governed pipeline stay off-mesh. The reason
   is a capability, not a preference: a turn needs `llm/infer`, and that
   capability has no admitted provider yet. When it does, the turn loop moves
   on-mesh as a third component and this manifest does not change shape.
   The resident that serves itonami.cloud runs `profiles/itonami.edn`, which
   declares `:residency {:plane :cloud}`; the workspace shows
   "cloud-agent · murakumo.cloud" in its status rail because the process cannot
   tell from inside. A laptop resident keeps the default `:local`.
3. **Chat model = Basho, API base = GLM-5.3, serverless.** Both vendors offer
   GLM-5.3 as a shared pay-per-token Model API, so "常駐" (always available)
   costs nothing idle and has no cold start; Baseten first, Together fallback
   (the order the gateway already pins). A dedicated replica cannot fit the
   budget and is not used. The gateway enforces a hard monthly cap
   (`MURAKUMO_BASHO_MONTHLY_BUDGET_JPY=200000` at a pinned rate) and answers
   503 `self_model_monthly_budget_exhausted` past it; this application's
   fallback (`awai-network/basho` → `z-ai/glm-5.3-flash`) carries the turn,
   labelled as the model that served. Basho's fine-tuned Japanese checkpoint
   remains a separate, later artifact; serving the upstream base under the
   Basho id is an **internal** route and is never advertised to OpenRouter as
   Basho.
4. **Video = Hokusai.** Bots gain `video_generate` / `video_status`
   (`cloud.itonami.app.media-tools`) against murakumo's
   `POST /api/v1/videos` contract, through the *same* provider record and the
   *same* admission as chat. Offered to a Bot whose capability policy names
   `:media.video`; `video_generate` is a write and is held for approval unless
   the Bot is omakase. Hokusai's backend is fail-closed on murakumo until its
   revision is attested; the 503 reaches the Bot verbatim.
5. **No second credential, no second authority.** The resident holds one
   `MURAKUMO_API_KEY`; the vendor keys live only in the gateway's Worker
   secrets. Egress admission is unchanged (ADR-2608130100).

## Consequences

- `awai-network/basho` joins the murakumo provider's models, accepted
  response models (`awai-network/basho`, `zai-org/GLM-5.3`), context window
  (128k conservative, unmeasured through the gateway) and fallbacks.
- **Placed (2026-09-02).** The `kotoba` CLI was pinned from levi's
  `~/.murakumo/bin` (same build as the servers, 2026-06-27); `kotoba app
  deploy --publish` through levi:8077 gossiped the routes, the two blocks were
  `block put` to levi / dan / joseph with the operator op-token, and
  `POST /mesh/http/itonami/profile` answers the identity record (HTTP 200,
  JSON) on all three. CIDs: agent-profile
  `bafyreig5thbb7i5i56l5m5ym7bp44ez3jvweblqeay3uiwliqkbr54wbxi`, agent-heartbeat
  `bafyreicp5ashj2ewjjmpmkvs7swdzz4ajol6etmwnk7n7hlartc47g2kr4`; recorded in
  `murakumo.app.edn`. Before the block put the same call answered 502
  "component unavailable" — the route-without-block state murakumo's README
  warns about — so a 200 is the receipt, not the publish.
- **The record is served from the artifact, not read back from the log.**
  Measured on kotoba-server 0.1.0: `kqe-query` is a predicate filter over a
  snapshot (kotoba-runtime `host.rs`), and `invoke_trigger` (kotoba-server
  `net_actor.rs`) hands every trigger an empty snapshot. A component's
  `kqe-assert!` output IS persisted to the node's quad store, but nothing a
  component queries can see it. The first two versions of `agent_profile`
  queried, executed (`trigger: executed` in `mesh.log`), and returned
  content-length 0 — the same shape as the kenchi / minidrama "HTTP 200"
  precedents. The profile component therefore carries its six identity facts
  as constants (the placed artifact *is* the record), asserts them at
  placement, and returns them on `on-http`. Handing components a real
  snapshot is a kotoba-server change, tracked in the root ADR as the next
  step for any on-mesh read.
- The murakumo operator token is a JWT-shaped bearer over the operator DID
  (`murakumo.identity/op-token`); the DID is the nodes' `KOTOBA_AGENT_DID`
  and is public. `app deploy --publish` accepts no token; `block put` requires
  it.
- What is NOT decided: moving the turn loop on-mesh. That waits on
  `capability-llm-infer` having an admitted provider, and is recorded as the
  next step in the root ADR rather than pretended here.
