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
- Java 21+
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

`bin/cloud-itonami-app` opens the web surface as an application window — no tab
strip, no address bar — and starts the server only when nothing already answers
on 1338, since it is commonly already resident.

```bash
bin/cloud-itonami-app
```

There is one user interface, the web surface, built on jp-go-dds. A second
`kotoba:dom` surface rendered by the `kotoba-lang/shell` AppKit host was retired
on 2026-08-06: it had become the older of the two while remaining the one the
launcher preferred, so running the app showed the older interface.
`kotoba-lang/shell` is still a dependency for EventKit and Keychain.

The server binds to `127.0.0.1` by default. The browser intentionally uses
`http://localhost:1338`, which is required for the WebAuthn localhost
development exception.

## `itonami` — the command line, without opening the app

`bin/itonami` runs any of the app's operations from any directory. It starts a
headless server if one is not already running, so nothing here needs the desktop
window (ADR-0018).

```bash
bin/itonami up                    # start a headless server (no window)
bin/itonami status                # where the server is, and whether you can act
bin/itonami commands              # every command, with the coverage counts
bin/itonami commands drive        # just the ones matching "drive"
bin/itonami down                  # stop the server this data directory started

bin/itonami auth login --label claude-code
bin/itonami workspace inbox
bin/itonami workspace drive search --q invoice
bin/itonami workspace drive documents rename --document doc-1 --title "New"
bin/itonami esign envelopes show env-1          # positional path parameters work too
```

The commands are generated from the routes `server.clj` serves, not written by
hand — `commands-test` re-derives them and fails if the checked-in registry has
fallen behind, so `itonami commands` reports real coverage rather than a claim.
Regenerate after adding a route:

```bash
nbb --classpath src dev/gen_commands.cljs
```

Flags the registry does not know about are passed through; `--json '{…}'` sends a
whole body. A read puts leftover flags in the query string, a write puts them in
the body.

**Funding, settlement and governed approval are not commands.** They need a
WebAuthn user-verifying assertion, which no CLI and no agent can produce
(ADR-0006). `itonami commands` prints how many routes that is. A brand-new data
directory also needs the browser once, to enrol a Passkey and create the
organization, before `auth login` will issue a session.

## ローカル projects と、メールの振り分け

Local projects are ordinary Git repositories this machine owns — one per
organization/user/project. They are **not** `/api/workspace/projects`, which
reads GitHub Projects v2 through `gh`. Mail is filed against them by
deterministic rules (ADR-0019).

```bash
bin/itonami projects create --project finance --title "Finance"
bin/itonami projects list

# rules: sender, sender domain, subject substring, or a classify label.
# every clause must hold; the first matching rule wins.
bin/itonami mail projects rules --project finance --label finance
bin/itonami mail projects rules --project travel  --from-domain jal.com

bin/itonami mail projects apply        # -> {:assigned 20 :unmatched 88 …}
bin/itonami mail projects              # rules + per-project counts
bin/itonami mail projects unassigned   # the pile no rule caught, senders ranked
bin/itonami projects mail --project finance

bin/itonami mail projects assign --message <id> --project travel
bin/itonami mail projects unassign --message <id>
```

Nothing is moved and nothing is deleted: assignment is a third plane over the
message store and the per-person marks. A rule cannot name a project that does
not exist, and a rule never overwrites a manual assignment.

Filing also writes the message **into** the project, and commits (ADR-0021):

```
<project>/mail/2026/08/<id>.edn       git      envelope, labels, sha256 of the body
<project>/mail/2026/08/<id>.eml.age   annex    the body, encrypted with age
```

Both are tracked. The body is tracked as **ciphertext**: the project becomes a
DataLad dataset (`-c text2git`) on first filing, so envelopes stay readable Git
objects while encrypted bodies go to git-annex — a clone stays small, and the
bytes can later be pushed to an encrypted remote.

The key is resolved from four places, in order — environment, recipients file,
**macOS Keychain**, **kagi**. A desktop app started by a double-click has no
exported environment, so the last two are what actually answer:

| Store | Item |
|---|---|
| kagi (item of record) | `itonami-mail-age`, compartment `personal`, a kagitaba item with `recipient` + `identity` |
| macOS Keychain (mirror) | service `cloud-itonami-app.mail-age`, accounts `recipient` and `identity` |

`GET /api/mail/projects` reports which one answered, because "filing works" and
"filing is storing bodies" are different facts and the second fails silently.

**The app reads only the recipient.** It writes mail and never reads it back, so
it holds no identity. Decryption is a person's command:

```bash
kagi get itonami-mail-age | grep -o 'AGE-SECRET-KEY-[A-Z0-9]*' > /tmp/id   # or Keychain
age -d -i /tmp/id <project>/mail/2026/08/<id>.eml.age
```

**Fail closed:** with no recipient configured the body is not written in the
clear as a fallback — the envelope lands, the body is skipped, and the skip is
reported with its reason. One commit per filing run, and only for what changed.

Note that git-annex object directories are read-only, so a project repository
needs `chmod -R u+w` before `rm -rf`.

### Pushing the bodies to B2

The annexed bodies exist on one disk until you push them (ADR-0022):

```bash
bin/itonami projects push --project finance     # git annex copy --to b2
bin/itonami projects remote --project finance   # annexed / pushed / unpushed
```

The special remote is `encryption=none` **because the content is already age
ciphertext** — B2 receives what it would have received had you uploaded the
`.age` file by hand. Layering git-annex's GPG on top would add a second key to
lose for no additional secrecy. B2 sees object count, sizes, and that they are
age envelopes; the envelopes carrying subjects are ordinary Git objects and are
never annexed, so they never reach the bucket.

Objects land under `cloud-itonami-mail/<organization>/<project>/` in the shared
`gftdcojp-m365-annex` bucket. Credentials resolve from `B2_KEY_ID`/`B2_APP_KEY`
or the Keychain item `b2:gftdcojp-m365-annex`, and are never written to the
repository.

**Pushing is explicit.** Filing mail does not touch the network. And B2 is
durability against disk loss, not key loss — the bucket copy is exactly as
unreadable as the local one without the age identity.

There is **no model in this path on purpose.** An LLM asked which project an
invoice belongs to answers confidently for mail that belongs to none of them,
and a wrong assignment is invisible in a way an unfiled message is not. So the
unmatched count is reported and the unassigned senders are ranked — the backlog
of rules to write is a number, not a guess.

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
conversation the turn joins. Function calling, embeddings and the Responses API
are not implemented.

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
belongs to the client; the workspace reads sit behind an authenticated session on
`/api/*`, and a surface with no session must not reach around it.

## Mail

One inbox over however many mailboxes, of three kinds:

| kind         | reached over               | credential                        |
|--------------|----------------------------|-----------------------------------|
| `:gmail`     | Gmail API v1 (`com-gmail`) | an OAuth grant, refreshed         |
| `:microsoft` | Microsoft Graph            | an OAuth grant, refreshed         |
| `:imap`      | IMAP4rev1 (`org-ietf-imap`)| a password, or XOAUTH2            |
| `:pop3`      | POP3 (`org-ietf-pop3`)     | a password, or XOAUTH2            |

Messages are parsed by **`org-ietf-mime`** (RFC 5322 / 2045–2047 / 2231), not
here and not by the protocol clients: a `multipart/alternative` message shows
the text its sender wrote rather than its own MIME boundaries, an
`ISO-2022-JP` subject reads as Japanese rather than as mojibake, and an
attachment called `請求書.pdf` keeps that name.

The unit is an **account**, not a provider. One person can connect a work
Gmail and a personal one and they are two mailboxes with two cursors, two
credentials and two error states — `Google: error` does not say which of two
Google mailboxes stopped working, so nothing reports it that way.

`/api/workspace/inbox` serves the on-disk archive **and** every synced
account in one `mail.mailbox`, so threads, labels, unread counts and the
search that reads message bodies range over all of it.

Sending goes out through whatever the account already proved: an OAuth
account sends through its own provider API, an IMAP or POP3 account over SMTP
(`org-ietf-smtp`) with the password it was registered with. Every recipient
goes in **one** SMTP transaction (RFC 5321 §3.3) — one send per recipient is
not one message delivered several times, because each copy would carry only
its own address in the header and a reply-all would reach one person.

```
GET    /api/mail/accounts              every mailbox (never a credential)
POST   /api/mail/accounts              register one reached over IMAP
DELETE /api/mail/accounts/{id}         forget an IMAP account
POST   /api/mail/accounts/{id}/sync    one mailbox, now
POST   /api/mail/send                  {:account-id :to :cc :subject :text}
GET    /api/mail-sync                  what each mailbox last did
POST   /api/mail-sync/sync             all of them
```

Sync is **off unless asked for** (`:mail-sync :enabled?`) — a workspace that
was merely installed should not begin pulling somebody's mail. `:mail-sync
:providers` names *delegated* credentials only: OAuth grants another tool on
this machine already holds, named item by item, because an application that
reaches for whichever Google token is lying around is an application that
reads mail it was never pointed at.

Passwords and tokens go to the Keychain and never to `state.edn`, which is a
file that gets copied, read and backed up.
## Governed AgentRun Kanban

The Projects view includes editors for the DoDAF organization graph
(Person/System performers, assignments and reporting lines) and approval
policies. Reporting lines are display structure only; approval authority comes
from the policy's eligible roles. Mutations require the active Organization's
owner/admin session and are stored as namespaced EDN.

The primary editor is `http://localhost:1338/#organization`. Organization
Studio provides the nested Unit tree, Person/Actor directory, assignment matrix,
approval-route preview and editors for Unit, Position, Role, Performer,
effective-dated Assignment, ReportingLine and ApprovalPolicy. Actor IDs use an
active-organization-scoped picker populated from Users, Agent sessions and
OrganismWorkers.

Governed work is physically separated from `state.edn` under
`data/work-governance/`: an atomic manifest selects a global fragment and one
owner-only fragment per Organization. A legacy `:work-governance` root in
`state.edn` is migrated on the next successful transaction.

For a disposable GitHub Projects sandbox item, the live adapter can be checked
with a restoring mutation. First enable governance and GitHub write-back in
`data/config.edn`, and allowlist the capability used below. Connect GitHub from
Settings, add the disposable project ID to `:github-sandbox-project-ids`, then
set the sandbox IDs from `.env.example`. The explicit confirmation is mandatory:

```sh
CLOUD_ITONAMI_GITHUB_SANDBOX_CONFIRM=1 \
clojure -M -m cloud.itonami.app.github-projects-sandbox
```

The probe reads the current basis, writes the configured sandbox Status option,
verifies it, restores the original option, verifies restoration, and prints a
content-addressed EDN receipt. It fails before mutation when IDs, capability,
write-back configuration, confirmation, or connected OAuth credential are
missing. A restoration failure is reported as
`:github-projects/sandbox-restoration-required` for immediate inspection.

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

## Kotobase Passkey federation

Passkey browser session から、短命・一回限りの Kotobase 交換証明を発行します。
Passkey で成立した browser session から、短命・一回限りの Kotobase 交換証明を
発行できます。

```text
POST /api/integrations/kotobase/assertion
Origin: <this app origin>
X-CLOUD-ITONAMI-CSRF: <session csrf>
Cookie: cloud_itonami_identity=...
```

response の `cacao_b64` を `exchange_url` へ top-level form POST すると通常の
Kotobase session が成立します。Datomic query と Git bundle read はその session
を共有し、Git write は引き続き Nekko署名・委任・quorumを要求します。
response の `cacao_b64` を `exchange_url` へ top-level form POST すると、
Kotobase の通常 session が成立します。Datomic query と Git bundle read はその
session を共有します。Git write は引き続き Nekko 署名・委任・quorum が必要です。

## Identity and organizations

First launch requires only a Passkey. The verified ES256/P-256 public key is
encoded as the stable User `did:key`; the private key remains in the
authenticator. Organization information can be entered later.

Returning active Users may also sign in through a ten-minute, single-use email
magic link when `:email-login` delivery is configured. Email proves control of
the registered address for that session; it does not create a User, replace the
Passkey-rooted `did:key`, enroll an invited User, or approve a governed action.
Money, signatures, and outward authorities continue to require their own
operation-bound WebAuthn assertion.

The session proof model is shared with `kotoba-lang/authentication` (email and
Passkey factors, decisions, and assurance levels), and its default-deny access
decision is evaluated by `kotoba-lang/authorization`. Cloud Itonami retains the
runtime adapters—Yubico WebAuthn, mail delivery, cookies/CSRF, persistence—and
the Tenant/Membership and operation-approval policy. See ADR-0012 for the exact
library boundary.

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

### Numbering, portability and outbound calls

`:number` (allocation 払い出し, assignment, lifecycle, MNP port-in/port-out) and
`:voice`'s `:call/originate` ride the same spine, and pre-check against
[`kotoba-lang/phone`](https://github.com/kotoba-lang/phone) — the same tables a
governed numbering actor enforces, not a second copy. See ADR-2608034000.

What is refused **before a human is asked**:

| refusal | why |
|---|---|
| re-allocating a released number | `:assign` is unreachable from `:released`; the path back runs through quarantine (90d default) and `:recycle`, which needs the elapsed window stated as a fact. A recycled number carries the previous holder's one-time codes. |
| a port-out naming somebody other than the holder | that mismatch **is** the port-out scam; asking a human first would be asking them to authorise their own takeover |
| an emergency number as an outbound destination | a machine-originated 110/119/911 sends people somewhere. A human in trouble dials from their own phone — nothing here is between them and the call |
| a calling number the subject does not hold and have active | caller-ID spoofing. Only possible to check because numbering records and call records are on one plane |
| an unpriced or over-limit call | an absent rate is not free and an absent limit is not unlimited — the card daily-limit rule, pointed at toll fraud |
| any of the above while the subject's line has just changed hands | a SIM swap or port-out restricts spend, outbound calls **and** moving the number onward, for 7 days |

The records, blocks, posture, rate and today's spend are computed server-side
and overwrite anything the client sends. A committed proposal is a **governed
claim, not a provisioned line**: `cloud.itonami.app.numbers` records what was
consented and governed, never what a network agrees to. **The `:number` actor
does not exist yet** — an enabled authority with no endpoint records a refusal,
which is why it ships disabled.

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

**An agent cannot approve.** `approve/start` and `approve/finish` have no tools
and no dispatch branch, because consent is a WebAuthn user-verifying assertion
and there is none an agent could produce. Verified end to end over real stdio:

```text
review ¥38,500 against a ¥10,000 balance  -> REFUSED payment/insufficient-funds
review ¥38,500 against a ¥120,000 balance -> ok, status=awaiting-passkey
commit a proposal no human approved       -> REFUSED authority/proposal-not-found
```

## Legal practice record (disabled by default)

This app can host the record for `cloud-itonami/lawfirm` — a practice OS whose
gate refuses any operation a bar-verified 弁護士 did not decide. It owns every
rule; `cloud.itonami.app.lawfirm` owns only the effects.

```clojure
;; data/config.edn — holding a practice's 一件記録 is a deployment decision
{:lawfirm {:enabled? true}}
```

| route | |
|---|---|
| `GET /api/workspace/lawfirm` | whether the surface is on. **Answers while disabled** — reports `:matters nil`, never `0` |
| `GET /api/workspace/lawfirm/summary` | the practice, as `lawfirm.projection` computes it |
| `GET /api/workspace/lawfirm/docket` | every 期限 as a `calendar.model` calendar |
| `POST /api/workspace/lawfirm/inbound/sync` | run archive arrivals through the practice's gate |
| `POST /api/workspace/lawfirm/matters/{id}/drive` | put a matter's 一件記録 folders in your Drive |

The record lives in `state.edn` under `:lawfirm/db`. Arrivals carry the
message id as their digest and **never the body** — the practice's record
holds classifications and identifiers, not prose.

**This app cannot approve anything.** Recording what arrived is not a decision
and needs no sign-off; every operation that *is* a decision — 受任, 提出, 出金,
和解, 辞任, 書面の外部送付, 送達, 相談回答の送信, 共同受任の招請 — parks for a
弁護士 in the practice's own console. See
[ADR-0010](docs/adr/0010-host-a-practice-record-without-owning-a-rule.md) for
what the Drive port deliberately does not create and why there is no calendar
port at all.

### FAX (Dropbox Fax, disabled by default)

The practice can compute what to send and where; this is what executes it.

```clojure
;; data/config.edn — the password is NOT here and must never be
{:fax {:enabled? true
       :username "you@example.jp"          ; also the keychain account
       :account-guid "…"                   ; app.hellofax.com/account/apiInfo
       :keychain-service "dropbox-fax"
       :callback-token "…"}}
```

```bash
security add-generic-password -s dropbox-fax -a you@example.jp -w
```

| route | |
|---|---|
| `GET /api/workspace/lawfirm/fax` | whether the surface is on, and whether a credential could be located (never its value) |
| `POST /api/workspace/lawfirm/fax/dispatch` | execute a committed 送達 — `{transmission-id, document-base64, filename}` |
| `POST /api/fax/callback/{token}` | the provider's terminal status. No session; a fax machine has none |

**The destination is not a parameter.** It is read from the practice record by
`lawfirm.workspace/dispatch-plan`; a caller chooses which 送達 to execute and
nothing about where it goes. That is the whole reason this uses the API rather
than Dropbox Fax's web UI, which is what the prior implementation in this
workspace did — a UI handoff puts the number back in the hands of whoever is
in the biggest hurry.

**An unidentified document is refused.** If the approved work product records
`:object-ref "sha256:…"`, the bytes must hash to it. No ref means no send.

**This has never run against the live service** — no account is provisioned
and every test drives an injected transport. See
[ADR-0011](docs/adr/0011-fax-through-the-api-because-the-ui-is-the-hazard.md).

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

Repository-backed private state follows
[ADR-0013](docs/adr/0013-local-agent-queries-over-an-edn-projection-and-publishes-kagi-chunks.md):

- `workspace/<opaque-user-storage-id>/state.edn` is local editable plaintext;
- Kagi seals bounded EDN chunks and supplies non-interactive VMK/signing keys;
- DataLad verifies CID-named ciphertext remotely before the Kotobase head CAS;
- the local Agent queries a materialized Datomic/DataScript-compatible view;
- direct EDN edits and Datomic-shaped transactions meet at one reconciler.

Operator flow:

```bash
clojure -M:repository preflight
clojure -M:repository migrate data/state.edn
- DataLad receives only CID-named ciphertext blocks and verifies the configured
  remote before the Kotobase head advances;
- `repository-storage/commit-workspace!` and `hydrate-workspace!` are the host
  integration boundary; `migrate-legacy-state!` imports the old whole
  `state.edn` without publishing it;
- `repository-qualification/require-qualified!` denies cutover unless all
  twelve security, capacity, recovery and compatibility gates have evidence.

After initializing Kagi, creating/configuring the DataLad dataset and placing
the Kotobase token in the process credential environment, the operator flow is:

```bash
clojure -M:repository migrate data/state.edn
# edit data/workspace/<opaque-user-storage-id>/state.edn
clojure -M:repository publish
clojure -M:repository hydrate
clojure -M:repository rotate-vmk
clojure -M:repository measure 20
clojure -M:repository drill 20 config/repository-production-evidence.edn
clojure -M:repository usage
clojure -M:repository qualify config/repository-production-evidence.edn
clojure -M:repository audit secret-fixture-marker
clojure -M:repository profiles
# Registered actors receive this owner's state path and their fixed stream.
clojure -M:repository actor swachh ../swachh-actor \
  clojure -M:dev:run-repository z-001 25
```

`publish` fails closed for missing/unfinished DataLad transport, locked Kagi,
invalid or stale heads, and merge conflicts. `profiles` audits the explicit
29-repository inventory. `preflight` is read-only and reports readiness without
printing environment values, paths, owner IDs, tokens, Kagi material, heads or
plaintext. It checks the CLI tools, owner shape, warm/cold dataset isolation,
empty cold annex cache, configured remote, workspace, source SHA, Kagi unlock,
Kotobase read and published head. `measure` is a warm local probe. `drill` requires a
separate cache-empty dataset in `CLOUD_ITONAMI_COLD_DATALAD_DATASET`, verifies
that it contains no materialized annex blocks, performs the real hydrate, and
atomically writes source-bound measurements to the Git-ignored evidence file.
`CLOUD_ITONAMI_SOURCE_COMMIT` must be the exact deployed 40-character SHA and
`qualify` refuses evidence from any other commit. Production cutover still
requires observed peak-write, sustained-upload and RTO inputs. Live inventory,
plaintext-leak, physical-byte, mutation/conflict, VMK-rewrap,
transport-before-head and DataScript-parity gates are executed by the current
build and cannot be overridden by the file.

CI reads the same inventory as local qualification. Each entry binds a unique
GitHub `owner/repository` to its checkout path; the Ubuntu job validates the
current app profile from the PR checkout and the other 28 profiles from their
`main` branches through GitHub's Contents API. Missing repositories, missing or
tagged profiles, duplicate entries, API failures and weakened profiles fail the
job. The workflow contains no second hand-maintained repository list.
```

`publish` refuses a missing DataLad remote, incomplete remote verification,
locked Kagi VMK, invalid signed head, stale epoch or merge conflict.
`profiles` checks the explicit deployable-repository inventory in
`config/repository-storage-inventory.edn`; CI fails closed if any listed repo is
absent or weakens the shared storage contract.
`measure` reports warm reconcile, local-view, sealing and configured-transport
hydrate capacity over the current user's real workspace. It intentionally does
not label a warm annex hit as cold-device RTO evidence; production peak-write,
sustained remote sync and a cache-empty recovery drill remain separate inputs
to the twelve-gate admission decision.
Copy `config/repository-production-evidence.example.edn` to the ignored
`config/repository-production-evidence.edn` and fill it only from production
telemetry and a cache-empty recovery drill. `qualify` derives the repository
inventory result, private-state leak markers and retained physical-byte
reconciliation live; those values cannot be overridden by the evidence file.

The signed head uses Kotobase's `encryptedGraph.put/get` expected-epoch CAS.
The application intentionally refuses to downgrade it to independent IStore
`put` and `append` calls.

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
