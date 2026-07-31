# Cloud Itonami App

Cloud Itonami is a local-first AI workspace built with
[`kotoba-lang/kotoba`](https://github.com/kotoba-lang/kotoba) and
[`kotoba-lang/shell`](https://github.com/kotoba-lang/shell). It combines chat,
mail, projects, drive, calendar, Passkey identity, and delegated service
connections while keeping local data and cloud authority boundaries explicit.

The repository is the tenant-neutral application. `gftd.ai` is represented by
the optional [`profiles/gftd.edn`](profiles/gftd.edn) distribution profile, not
by a fork of the application.

## Status

This is an early public development release. The loopback server, local model
adapters, chat UI, background worker runs, read-only workspace integrations,
Passkey registration, User `did:key`, organization membership, OAuth/PKCE
connections, W3C Verifiable Credentials for organization membership
(`eddsa-jcs-2022` Data Integrity proofs with status-list revocation), the
`did:web` document endpoint, and optional private email relay client are
implemented. Production multi-tenant hosting, domain verification, holder-signed
Verifiable Presentations, and signed desktop packages remain separate
responsibilities — the presentation gap is structural, not unfinished work: a
Passkey signs its own `authenticatorData || clientDataHash` and cannot produce a
Data Integrity proof.

## Requirements

- macOS 14 or later for the native shell, EventKit, and Keychain integrations
- Java 24+ (required for the standard ML-DSA-65 provider used by `kagi`)
- Clojure CLI
- `jq` and `curl`
- Ollama or another configured OpenAI-compatible provider

Pure tests and the loopback web surface also run on Linux.

## Run

```bash
clojure -P
clojure -M:server
open http://localhost:1338
```

On macOS, `bin/cloud-itonami-app` uses a sibling `kotoba-lang/shell` checkout
or `CLOUD_ITONAMI_SHELL_DIR` when available and otherwise opens the web surface.

```bash
bin/cloud-itonami-app
```

The server binds to `127.0.0.1` by default. The browser intentionally uses
`http://localhost:1338`, which is required for the WebAuthn localhost
development exception.

## OpenAI-compatible clients

Any tool that speaks the OpenAI chat API can use the local models through the
loopback server. Point it at `http://localhost:1338/v1` with any API key — the
compatibility slice is `GET /v1/models` and `POST /v1/chat/completions`, and it
is reachable without a session because the loopback bind is what protects it.
The management API under `/api` is not part of this surface and does require
one.

```bash
curl -N http://localhost:1338/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"gemma4:e2b","stream":true,
       "messages":[{"role":"user","content":"hello"}]}'
```

Streaming is Server-Sent Events in the `chat.completion.chunk` format, ending
with `data: [DONE]`. `stream_options.include_usage` adds the usage-only chunk
before it. Omit `stream` for a single `chat.completion` response.

A request the provider policy refuses — a cloud provider behind a shut gate —
fails with its real status code even under `stream: true`, rather than a 200
stream that carries an error a client would read as an empty answer.

The non-OpenAI extensions are `provider`, `agent_id` and `session_id`: they
select a configured provider, one of the local agents, and the stored
conversation the turn joins. Function calls preserve provider-native stop
reasons. `POST /v1/responses` and Anthropic-compatible `POST /v1/messages`
(including Anthropic streaming) are also implemented. Embeddings are not.

## MCP server (fleet directory)

The actor fleet is also served over the Model Context Protocol, so an MCP client
— Claude Code, Claude Desktop, an editor — can query the directory directly.
Transport is stdio, so nothing new listens on the network.

```json
{
  "mcpServers": {
    "cloud-itonami-fleet": {
      "command": "clojure",
      "args": ["-M:mcp"],
      "cwd": "/path/to/cloud-itonami-app"
    }
  }
}
```

There is no wrapper script on purpose: MCP clients launch a command directly, and
putting another process in the middle of a stdio protocol stream only risks its
framing.

Two tools, the same ones the in-app agent uses — the descriptors and behaviour
live in `cloud.itonami.app.fleet`, and `cloud.itonami.app.mcp` is an adapter over
them rather than a second implementation:

| Tool | What it does |
|---|---|
| `fleet_search` | Query the bundled catalog of ~1,200 actors by text, domain, ISIC, ISO-3166, maturity, execution, or whether they have an address. No network. |
| `fleet_call` | `GET` a path on a deployed actor. The actor is named by repository, never by URL, so the host comes from the catalog and cannot be chosen by the caller. Read-only. |

Both require the fleet capability to be enabled; until then `tools/list` is
empty, which is the same fail-closed default the agent capabilities have:

```clojure
;; data/config.edn
{:agent-control {:fleet {:enabled? true}}}
```

Browser, computer, mail, calendar, drive and chat are deliberately **not**
exposed. The device tools verify the frontmost application between approval and
action, which does not survive translation to a protocol whose consent model
belongs to the client; the workspace reads sit behind the Passkey session on
`/api/*`, and a surface with no session must not reach around it.

## Background worker runs

The Worker tab queues prompts that take longer than an interactive turn. Runs
share the local model with chat under a small concurrency limit (default 2,
`:worker :max-concurrency`) and go through the same fail-closed provider policy,
so a background run cannot reach a cloud provider that chat could not.

Runs are kept in memory and **are lost when the server restarts** — the durable
store keeps only a bounded completion event per run, because persisting streamed
output would rewrite the whole state file on every token. Output is capped at
16,000 characters per run. Cancellation takes effect at the next streamed
chunk, so a stalled provider request can stay open until it times out.

## Identity and organizations

First launch requires only a Passkey. The verified ES256/P-256 public key is
encoded as the stable User `did:key`; the private key remains in the
authenticator. Organization information can be entered later.

The default public profile uses managed addresses below `cloud-itonami.app`,
but does **not** claim `did:web`. A deployment may enable Organization
`did:web` only after it controls the generated domains and publishes the
corresponding DID documents. Operators that do not control that domain must
override both identity domains before inviting production users.

Identity concepts remain separate:

- Installation: one local application state
- User: Passkey-rooted person with a stable `did:key`
- Tenant: internal immutable organization/workspace ID
- Organization ID: human-readable, immutable slug
- Domain: managed or independently verified DNS name
- Membership: User-to-Tenant role
- OrganismWorker: independently supervised AO assigned to a Tenant
- Relay address: optional provider-managed mail alias

Users may belong to multiple Organizations. The sidebar selector changes the
active membership for the current session; all workspace and OrganismWorker
reads remain scoped to that one active Organization.
Existing Users join another Organization only after Passkey authentication
and explicit acceptance of a one-time, expiring, User-bound invitation.

An OrganismWorker is not a background WorkerRun. It retains its own identity,
memory, lifecycle, and repository authority while Cloud Itonami provides the
organization directory, redacted activity projection, and human intent and
approval surface. See
[ADR-0002](docs/adr/0002-external-artificial-organism-workers.md).

## 事業 (business) — the Portfolio pane

A business is the entity that joins the planes this workspace already describes
separately and could not compare: the BMC/Lean canvas (`:canvas/product`), a
system-dynamics model (XMILE), the blueprints an operator declared they run
(`:adoption/repo`), repositories (`:repo/path`) and a legal entity
(`:company/lei`). Their grains disagree — in the BMC plane `cloud-itonami` is one
of twelve products, while in this app it is a directory of 1,213 actors — so one
entity holds one key from each.

```bash
POST /api/business            {"slug":"cloud-itonami-5820","name":"ISIC 5820 事業"}
POST /api/business/{id}/bind  {"canvas":"cloud-itonami","adoptions":["cloud-itonami-isic-5820"]}
GET  /api/business
```

A business is Tenant-scoped, like a funding account. It is created and bound by
hand: which repository or canvas belongs to which business is a judgement, and
deriving it from a name prefix would invent the binding the entity exists to
record. A session with no Organization ID gets `409 organization-required` — it is
logged in, so `401` would send it to fix something that is not broken.

**A face nobody can resolve is not an empty face.** Each of the five reports
`unbound` (no key), `unresolvable` (a key, but no workspace checkout to resolve it
against), `missing` (resolvable, not found), `unreadable` (found, would not parse)
or `resolved`. `:business :workspace-root` is **nil by default** — this app ships
on its own and cannot assume a west checkout of the superproject beside it — so
out of the box every plane-backed face is `unresolvable` and names the setting,
rather than reporting `missing` for a place nobody said to look.

The Portfolio pane reports whether a face resolves, not what it says. The Canvas
pane is the first one that reads a face's contents.

### The matrix — every business across every plane

```bash
GET /api/portfolio/matrix
```

One row per business, one column per plane (Canvas / riskiest gate / maturity /
Loops / Repos / 実測). Every cell carries **which kind of nothing it is**, and the
four never collapse into one: `unbound` (this business never named that face —
fix it in Portfolio), `unresolvable` (it named one, but there is no workspace
checkout — set `:workspace-root`), `missing` (resolvable and not there — generate
it), `measured` (a real value). Metrics adds a fifth, `stale`, because it is the
only plane carrying a date it can be late against.

The counts under the table turn 「まだ何も測れていない」 into a number rather
than a screen of grey cells.

It is **its own endpoint and loads on demand**, not part of `/api/business` which
runs at startup: computing it re-runs every bound XMILE model — plus one extra
run per constant for the sensitivity sweep — and reads 4.8 MB of repository
planes. Those planes are read once per request, not once per business.

### Canvas — read the fold, propose the change

```bash
GET  /api/business/{id}/canvas
POST /api/business/{id}/canvas/propose   {"action":"add-item","canvas-id":"cloud-itonami.problem",
                                          "value":"…","by":"山田","reason":"…"}
POST /api/business/{id}/canvas/proposals/{pid}/withdraw
```

The read is `90-docs/business/<product>-canvas.datoms.edn` — the **folded**
canvas, base datoms with every canvas-ledger event applied, generated upstream by
`gftd canvas datoms --all`. This app does not fold: `gftd.canvas/apply-event` is
the fold, and a second copy of it here would drift (its rolling-observation
window — three most recent `観測 (signal):` items per block — is not behaviour
anyone would rediscover from the ledger format).

**Writing is a proposal, not a ledger entry.** `canvas-ledger.edn` is append-only
and governed; this app has no governor, so there is no route that appends to it —
not one that fails, one that does not exist. A proposal is recorded here and
rendered as the exact `gftd` command that would apply it.

**The canvas carries its own maturity score.** Three of the five BMC dimensions
(completeness, hypothesis, validation) are computed from the very blocks and
hypotheses this pane renders, so `90-docs/business/maturity-scores.datoms.edn` is
read here. Fourteen dimensions come back, each labelled `auto` (computed) or
`facts` (a judgement somebody entered) — eleven are judgements, and a reader
comparing two products should not have to guess which. Each also carries whether
that judgement was **recorded**: the generator reads absent facts as 0, so an
unassessed dimension would otherwise read as one assessed and found lacking. As
of 2026-07-30 nothing is unrecorded across the twelve products; the flag exists
for the day a thirteenth is added.

**Whether a proposal landed is measured, not stored.** `awaiting-governor` means
the value is not in the projection; `landed` means it is, read back out of the
regenerated projection; `unverifiable` means there is no checkout to read.
`withdrawn` is the one state a human sets, because 「もう要らない」 is not
something a projection can show.

### Loops — run the model, and refuse to fake a run

```bash
GET /api/business/{id}/loops
```

`:business/model` binds an XMILE 1.0 file and `:business/leverage` a leverage
ledger, both relative to the workspace root. The simulator is
[`kotoba-lang/org-oasis-open-xmile`](https://github.com/kotoba-lang/org-oasis-open-xmile)
(`xmile.execute/run`, Euler or RK4) and the Meadows band vocabulary is
[`kotoba-lang/dynamics`](https://github.com/kotoba-lang/dynamics). Both are
dependencies, not reimplementations: a second simulator in this app is what
ADR-2607309600 forbids.

A model that will not run — array-dimensioned, an unsupported method, no
sim-specs — comes back as `unsimulatable` **carrying the engine's own message**,
and with no series at all. An empty series would read as 「シミュレーションした
結果、全部ゼロ」, which is a different and false claim. Likewise
`dynamics.core/loop-structural-strength` returns nil when cycle time was never
observed, and that nil is reported as `uncomputable-until-measured` rather than 0.

The pane draws **small multiples, one panel per variable** — a stock in `repos`
and a flow in `repos/day` do not share a y-axis — with a table view beside it.

Two things it says out loud: which model was simulated when a document declares
several, and that today's leverage ledgers model the **fleet's own repository
registration backlog**, not a business's economics.

**Where intervening pays is measured, not judged.** `dynamics.core/leverage-score`
wants a tractability in [0,1] per intervention, and inventing those would put a
guessed number at the centre of the ranking — so the pane computes **elasticity**
instead: each leaf constant is nudged 10%, the model is re-run, and the percent
change in each stock is reported per percent change in the parameter.
Dimensionless, so a rate in `tenants/day` and a window in `days` are comparable.

An elasticity of exactly 0 is ambiguous between 「動かしても効かない」 and
「そもそも繋がっていない」, so the second is decided structurally: a constant that
no equation references is reported as **disconnected**, with the list of what
does reference the others. On the shipped cloud-itonami model that distinction is
the finding — `Weekly_Human_Uniques` and `Agent_Runs_Per_Week` are disconnected,
which is the model saying out loud that traffic cannot move the funnel while the
traffic→signup rate is unmeasured.

The superproject now ships one real model —
`90-docs/business/cloud-itonami-saas-funnel.xmile`, every parameter a dated
measurement, with the unconverted funnel expressed as a 95% upper bound rather
than as zero (com-junkawasaki/root ADR-2607310100). Bind it with
`:business/model` to see it here.

### Repos — an unscored axis is not a zero

```bash
GET /api/business/{id}/repos
```

`manifest/repo-taxonomy.edn` and `manifest/repo-maturity.edn` joined on
`:repo/path`: what kind of thing each repository is, and five 0.0–1.0 maturity
axes plus a composite. Repos come from two bindings and are labelled by which —
`:business/repos` is a path the owner named, `:business/adoptions` is a blueprint
an operator declared they run (its workspace path comes from the fleet catalog,
never from concatenating org and repo).

`repo-maturity.edn` says in its own header that an axis is nil when not
computable, and it means it: `:maturity/stage-score` is nil for **2,732 of 3,899**
repos. So an unscored axis gets **no bar and no number**, the mean composite is
taken over only the repos that have one, and the count it left out is reported
beside it. An average that counts a missing score as zero is how a maturity
dashboard becomes a lie about work nobody assessed. Each axis also carries its
method, because an `:impl` score is a size-and-scaffold heuristic and a `:stage`
score is a parsed marker — two identical-looking decimals otherwise.

### Metrics — how old is this number?

```bash
GET /api/business/{id}/metrics
```

`90-docs/business/metrics/<product>.edn`, keyed by the bound canvas product.
**Freshness leads**: every file carries `:as-of`, and measured across the twelve
real files eleven were same-day while `ai-gftd-yukkuri` was **28 days old**. The
age is computed against `:business :metrics-max-age-days` (3 by default, because
the emitter runs daily) and reported as `fresh` / `stale` / `undated` — `undated`
being its own state, since a file with no date did not measure late, it declined
to say when.

Two things this pane will not do. It does not unify `:funnel`, because the shape
differs per product (`{:trials :freeClaims :paid}` vs `{:visitors :chatters
:paying}` vs `{:visitors :signups :checkouts}`) and deciding a `freeClaim` is a
`signup` is a product judgement the app has no basis for — product-specific keys
pass through under their own names. And it never returns `requests-7d` without
`probe-4xx-pct` and `error-5xx-pct` in the same map: one real file reports 508,284
requests at 80% 5xx, and 57% probe traffic is normal here, so a request count
alone would state the wrong fact.

See [ADR-0008](docs/adr/0008-business-is-the-join-of-five-planes.md).

## Embedded Bitcoin consensus synchronization

Bitcoin Core remains the default production backend. An operator who has
allocated enough disk can instead enable the embedded, watch-only validating
client. It persists headers, UTXOs, undo journals, side branches, peer health,
and fork-choice evidence; private keys, transaction relay, mining, and signing
are outside this process.

```clojure
{:bitcoin
 {:embedded-consensus
  {:path "/absolute/path/mainnet-consensus.sqlite"
   :network :mainnet
   ;; Required only when creating a new database. A verified existing database
   ;; reopens without replaying a configuration-supplied genesis body.
   :genesis-hex "..."
   :peer-sync
   {:enabled? true
    :dns-discovery? true
    ;; Operator anchors are additional availability sources, not trust roots.
    :peers [{:host "bitcoin.example.net" :port 8333}]
    :interval-seconds 300
    :maximum-peers 8
    :required-successes 2
    :max-header-batches 32
    :max-blocks-per-cycle 32}}}}
```

`enabled?` starts an interruptible lifecycle supervisor. Each cycle resumes
from the durable locator, compares a bounded health-scored peer set, validates
and atomically commits headers, then downloads a bounded block segment and
fully validates block, Script, UTXO, chainwork, and reorg transitions locally.
DNS never supplies consensus. Peer selection/cooldown history is checksummed
and persisted beside the chainstate by default. Sequential and atomic batch
header paths reject obsolete block versions at the buried BIP34, BIP66, and
BIP65 activation heights and enforce testnet4's BIP94 600-second
adjustment-boundary timewarp floor. The pinned node release also carries the
disk-backed, resumable Core full-history differential verifier and Core-aligned
stripped transaction/output-script boundaries. Input values, transaction input
totals, and accumulated block fees use Core's exact `MoneyRange`. Weight-derived
witness bounds also preserve consensus-valid unknown witness versions for
future soft-fork compatibility, while block validation rejects all witness
serialization before the configured SegWit activation height even when a
coinbase commitment is present. Excessive legacy sigops are rejected before
either active or side-chain block bodies enter local storage. Prevout Script
validation also matches Core's retroactive P2SH/WITNESS/TAPROOT flags and
historical exception composition. BIP30 checks use Core's parent-view scan,
pinned BIP34-chain optimization, and height 1,983,702 recheck boundary;
replacement remains coinbase-only and non-coinbase collisions fail closed.
BIP9 deployment periods preserve Core's start/timeout and threshold/timeout
transition precedence. Compact proof-of-work targets preserve Core's exact
`SetCompact` exponent-33/34 and 256-bit overflow boundaries, including during
initial-context header validation. The `assumevalid` fast path preserves
Core's 256-bit `GetBlockProofEquivalentTime` rounding and does not skip Script
checks until the strict two-week burial boundary has actually been crossed.
Transaction versions retain Core's unsigned 32-bit wire semantics, preserving
CSV and BIP68 validation above `0x7fffffff`; the embedded Script verifier also
matches `SCRIPT_VERIFY_CONST_SCRIPTCODE`. Legacy signature hashing matches
Core's `OP_CODESEPARATOR` parser and serialization contract and is pinned
against all 500 official legacy sighash outcomes.
The pinned node also provides Core-compatible BIP158 basic-filter construction,
strict decoding and membership matching plus BIP157 filter-header chaining.
All 10 official Core block-filter vectors are pinned in its CI. Its P2P filter
API requires `NODE_COMPACT_FILTERS`, an exact requested range, an explicit
retained header anchor, strict GCS decoding, and an expected filter header;
compact filters remain non-consensus scan hints and are not used to bypass the
app's full local block-validation path.
The embedded UTXO database uses Core-identical unspendable-output pruning and
transactionally upgrades legacy state to schema v7, requiring authenticated
reindex if impossible spend history is detected.

Owner/admin users can trigger the same exclusive cycle with
`POST /api/bitcoin/consensus/sync`; concurrent attempts return `409`.
`GET /api/bitcoin/consensus/status` includes supervisor, peer, header, block,
snapshot, reorg-window, and failure evidence. Invalid enabled configuration
fails before the HTTP listener binds. Full mainnet storage is never enabled by
default or silently placed on the application disk.

## Funding accounts and payment settlement

An Organization may link the bank accounts it pays from, and record what they
held. Both are Tenant-scoped: a company account outlives whichever member linked
it.

```bash
# link an account (the number is fingerprinted, never stored)
POST /api/funding/accounts
     {"institution":"PayPay銀行","account-type":"current",
      "holder":"JK株式会社","number":"1234567","currency":"JPY"}

# record what the bank said, and WHEN it said it
POST /api/funding/accounts/{id}/balance
     {"amount-minor":120000,"currency":"JPY",
      "as-of":"2026-07-30T09:00:00Z","source":"owner-attested"}

GET  /api/funding
```

There is **no bank connector**. A balance is recorded because a human read it,
and `as-of` is the instant the *bank* stated — not the instant it was typed in.
Amounts are integers in the currency's minor unit (JPY has exponent 0, so
`38500` is ¥38,500). A balance older than `:balance-max-age-seconds` (24h by
default) is `:stale` and refuses; one that was never recorded is
`:never-recorded` and also refuses. **An unknown balance is never rendered as
¥0** — that would state a fact nobody established.

Settling a payable rides the same spine as the other authorities:

```bash
POST /api/authority/payment/review                        # deterministic pre-check
POST /api/authority/payment/proposals/{id}/approve/start  # Passkey
POST /api/authority/payment/proposals/{id}/approve/finish
POST /api/authority/payment/proposals/{id}/commit
```

The pre-check refuses **before a human is asked** when the recorded balance does
not cover the amount (`402`), when it is unknown or stale (`409`), when the
reference is already settled by anyone in the Organization (`409`), or when an
eSIM ownership transfer for the same subject currently holds spend (`423`). The
balance, its freshness, the funding account and the settlement history are all
computed server-side and overwrite anything the client sends — otherwise the
funds gate would be a suggestion.

A committed proposal is a **governed settlement record, not a transfer.** This
app holds no banking credential and moves no money; a human makes the transfer
in their bank. `:payment` ships disabled, like every other authority. See
[ADR-0005](docs/adr/0005-payment-settlement-authority.md).

### Driving it from an agent (MCP)

The stdio MCP server publishes these as tools — but only when it can resolve a
**real app session**. Export a session token, or put one in the login Keychain:

```bash
security add-generic-password -s cloud-itonami-app.mcp -a session-token -w
export CLOUD_ITONAMI_MCP_SESSION=…   # takes precedence over the Keychain
clojure -M:mcp
```

| tool | |
|---|---|
| `funding_accounts` | accounts, balances, freshness |
| `funding_link_account` | number is fingerprinted, never stored |
| `funding_record_balance` | `as-of` is the instant the **bank** stated |
| `payment_review` | runs every deterministic refusal; records a proposal **awaiting a human** |
| `payment_proposals` | statuses |
| `payment_commit` | only for a proposal a human **already** approved |
| `payment_reject` | records that a human declined |

Without a token there are no such tools in the manifest at all — not tools that
fail on call. With one, the agent acts *as* that session: same organization
scoping, same store, same refusals as `/api/*`. The session's user must have
enrolled a Passkey.

### Authenticated Streamable HTTP

The app server can additionally expose `POST /mcp` for remote clients. It is
disabled by default and requires a constant-time checked Bearer token from
`:mcp :access-token-env`; the configured `:actor-user-id` scopes every AgentRun,
schedule and watcher result. Stateful MCP `2025-06-18` and sessionless
`2026-07-28` `server/discover` are both supported.

Only this authenticated HTTP profile adds `workspace_snapshot`,
`agent_runs_list`, `agent_run_create`, `agent_schedules_list`, and
`agent_watchers_list`. The stdio manifest remains unchanged, so enabling HTTP
does not silently widen Claude Code or another local client's authority.

Official Python OpenAI/Anthropic/MCP SDKs and the official Go MCP SDK run against
the real fixture server in CI (`test/sdk_compat.py`,
`test/sdk_go_compat/main.go`).

**An agent cannot approve.** `approve/start` and `approve/finish` have no tools
and no dispatch branch, because consent is a WebAuthn user-verifying assertion
and there is none an agent could produce. Verified end to end over real stdio:

```text
review ¥38,500 against a ¥10,000 balance  -> REFUSED payment/insufficient-funds
review ¥38,500 against a ¥120,000 balance -> ok, status=awaiting-passkey
commit a proposal no human approved       -> REFUSED authority/proposal-not-found
```

## Distribution profiles

Set a named profile or an EDN file path:

```bash
CLOUD_ITONAMI_PROFILE=gftd clojure -M:server
CLOUD_ITONAMI_PROFILE=/secure/path/company.edn clojure -M:server
```

Profiles contain branding and non-secret service coordinates. Secrets remain
in environment variables or the operating-system credential store. Local
`data/config.edn` is merged after the selected profile.

The included gftd profile maps:

- the corporate `gftd` organization to `gftd.ai`;
- managed user organizations to `{organization-id}.gftd.ai`;
- public addresses to `@gftd.ai`;
- relay calls to `https://relay.gftd.ai`.

Enabling `:publish-did-web?` is a deployment assertion: the operator must
actually serve the DID documents over HTTPS.

## Dependencies

Release and CI dependencies are immutable Git SHAs in `deps.edn`. A `:dev`
alias overrides them with sibling `kotoba-lang` checkouts for the west
workspace:

```bash
clojure -M:dev:test
```

Do not commit `:local/root` dependencies to the release dependency map.
See [the dependency policy](docs/dependencies.md), including the binary
distribution license gate.

## Data and secrets

Runtime state is stored below `data/` and is ignored by Git. OAuth tokens use
macOS Keychain; only references and non-secret metadata enter the local state.
Provider and relay credentials are read from environment variables.

See [`.env.example`](.env.example), [the architecture](docs/architecture.md),
and [the tenant model](docs/tenant-model.md).

## Verify

```bash
clojure -M:test
clojure -M:lint
```

## License

Code in this repository is available under Apache License 2.0. Dependencies
remain under their respective licenses; see [NOTICE](NOTICE) and
[the dependency policy](docs/dependencies.md).
