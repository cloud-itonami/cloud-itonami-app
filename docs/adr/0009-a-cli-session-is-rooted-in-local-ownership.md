# ADR-0009: A CLI session is rooted in local ownership of the store

## Status

Accepted. 2026-07-31. 2026-09-02: `deps.edn` alias `:cli` is gone. Do not
run `clojure -M:cli`.

## Context

Every `/api/*` route takes `require-app-session!` — a session from the identity
cookie, plus an enrolled Passkey. That is the right gate for a browser, and it
left no way in for anything else. A CLI has no cookie. An MCP client has no
browser to run a WebAuthn ceremony in.

`payment-tools` had already met this and solved it halfway: it resolves a
session from a token in `CLOUD_ITONAMI_MCP_SESSION` or the login Keychain
(service `cloud-itonami-app.mcp`, account `session-token`), then acts as that
session with the same scoping and refusals as HTTP. What it could not do was
*get* such a token — `issue-session!` is called by the Passkey flow and by
nothing else. So the mechanism existed and had no way to be started.

The immediate case that forced it: this install's user is
`:status :pending-passkey`, `:passkey-enrolled? false`, and the browser ceremony
would not complete. Nothing could act, in any client, including the browser.

## Decision

### The root is the data directory, not a Passkey

An agent session is issued against a 0600 file inside the data directory
(`agent-enrollment.key`), written by the server at start.

The reasoning is about what the Passkey actually defends. The loopback server is
reachable by every process and every page on this machine, so a browser session
belonging to a half-enrolled user must not act — that boundary is real and
unchanged. But `data/state.edn` **is** the app: sessions, memberships,
organizations, every record. A process that can read and write it can mint
itself any session by editing the file. Requiring a Passkey on top of that
refuses the operator and stops nobody else.

So the proof is the thing that is actually load-bearing: can you read the key.

Owner decision, 2026-07-31, over the alternative of keeping the Passkey as the
root — that alternative required the browser ceremony to succeed once, and the
case this exists for is the one where it cannot.

### The gate reads `:kind`, and the browser bar does not move

Sessions carry `:kind` — `:passkey` (the default, and what an unmarked record
from an older store is read as) or `:agent`. `require-passkey!` passes an
`:agent` session and refuses a `:passkey` one whose user has not enrolled. A
test holds both halves: the agent path works with no Passkey anywhere on the
install, and a cookie session for that same user is still 428.

### It is not the approval gate

`approve/finish` needs a WebAuthn user-verifying assertion and no agent can
produce one (ADR-0006). Nothing here touches it. An agent may ask, record, and
carry out what a human already approved. It may not approve.

### Bearer on `/api/*`, and what that turns off

`require-session!` accepts `Authorization: Bearer <token>` before the cookie.
For a bearer request, Origin and CSRF are not required — both defend against a
browser attaching the cookie by itself, and nothing attaches a bearer token by
itself. A page cannot set an Authorization header cross-origin without a CORS
preflight, and this server sends no CORS headers, so the browser refuses before
the request is made.

Bearer is checked *first* so a CLI that happens to carry a stale browser cookie
acts as the token it presented.

### The CLI is a client, not a second writer

`store/state` is `(defonce state (atom (load-state)))` — read once per process,
never re-read. A CLI writing `state.edn` beside a running server has its write
silently reverted by the server's next `transact!`. So enrollment is a route on
the running server and `clojure -M:cli` is an HTTP client of it.

### One login serves both clients

`auth login` stores the token in the Keychain item `payment-tools` already
reads. Not a coincidence to tidy up later: one enrollment is meant to be what
makes the CLI *and* the MCP server able to act, and a second location would mean
enrolling twice for one decision.

### Sessions are labelled, listed and revocable

A label is required — an unlabelled agent session is one nobody can later decide
to revoke, because there is nothing to tell it from the others. `auth status`
lists revoked and expired ones rather than filtering them: the question is "what
has ever been given access", and a list that drops the dead ones answers a
different one.

## What this cost, measured

`base-url` first preferred `:server :public-origin`, which the shipped config
hardcodes to `http://localhost:1338`. A CLI run against a probe install on 1351
therefore read that install's enrollment key and sent it to whatever was
listening on 1338 — a different store. It came back 404 only because that server
was older; with both current it would have been a confusing `invalid-key`, and
with the same key it would have acted on the wrong install.

`:public-origin` is what a browser is told the app is called. A CLI connects
directly, so the bound host and port are the truth. Fixed, and the reason is in
the docstring rather than in this file alone.

## The money surface is deliberately left out

`payment-tools/session` asked the same question by a different name — it called
`passkey-enrolled?` directly rather than `require-passkey!`, so it did not learn
about agent sessions when that gate did. Found by running the CLI's own token
through it: the Keychain item resolved, the digest matched, and the session came
back nil.

Two spellings of one rule is how they drift, so there is now one:
`identity/may-act?`, which `require-passkey!` is a throwing wrapper around.
`payment-tools` keeps the stricter check and now says why in the place it makes
the choice: an agent session is rooted in being able to read a file in the data
directory, and the decision that made that enough was about the business and
portfolio surface. Widening it to funding and settlement is a separate decision
nobody has made. A test holds the line and fails if the check is swapped for
`may-act?`.

## The money line, held where it is actually crossed

The paragraph above was true of the MCP adapter and false of the app. `/api/*`
uses `require-app-session!`, which is `require-passkey!`, which now passes an
agent session — so the moment bearer auth landed, a token could reach
`/api/funding/*` and `/api/authority/*` over HTTP while the surface it was
minted for refused it. **A boundary enforced in the client and not at the route
is not a boundary**, and this one was enforced in the client for exactly one
commit.

`require-human-session!` refuses an agent session outright and now gates all
thirteen money routes. 403 rather than 428: a Passkey is required and this
caller can never present one, so telling it to go and enrol would be an
instruction it cannot follow. A test calls three of those routes with a real
agent token, asserts the refusal, then enrols a Passkey and shows the same
routes answer — so the refusal is about `:kind` and not about the token.

## The surfaces over MCP

`business_list`, `business_create` and `business_bind` are published when a
session resolves against the running server. They are HTTP clients of this
app's own `/api/business` routes, so a refusal the route makes is the refusal
the agent sees.

They go over HTTP rather than calling `business/bind!` in-process because
`store/state` is read once per process: an MCP server writing in-process would
write onto a snapshot taken when IT started, and the resident app's next
`transact!` would drop it. `app-client` owns that transport and the CLI uses the
same one — one expression, after the `may-act?` lesson about two spellings.

`fleet` stays in-process. Measured: `fleet.clj` contains no `store/` call at
all, so it has no snapshot to go stale, and routing a pure read of a bundled
resource through HTTP would add a running-server requirement to the one
capability that does not need one.

The local ownership root must also be one value across those clients. In the
resident layout, launchd owns `~/.cloud-itonami/data`; a CLI or MCP process
started from `~/.cloud-itonami/app` must not silently fall back to the checkout's
`app/data`, because it would read a different enrollment key while sending the
resulting token to the resident server. The `bin/itonami` and
`bin/itonami-mcp` launchers therefore default to the sibling resident data
directory in that layout, while preserving an explicit
`CLOUD_ITONAMI_DATA_DIR` override.

**Not converted: `payment-tools`.** It still calls `funding/*` and
`authority-api/*` in-process and therefore still has the stale-snapshot hazard.
It is also, as of this change, unreachable in practice: it needs a
Passkey-enrolled session token, and `auth login` writes an AGENT token into the
one Keychain item it reads. The two surfaces need different sessions and there
is one slot. That is a design question — how does the money surface get a human
session when the token slot is taken — not a port to HTTP, so it is named here
rather than answered.

## Consequences

- **Anything that can read `~/.cloud-itonami/data/agent-enrollment.key` can act
  as the owner over the CLI and MCP.** That is the decision, stated plainly
  rather than left to be discovered. It was already true of `state.edn` beside
  it; what changed is that the operator can now use it deliberately.
- The Passkey remains required for every browser session, and for approval in
  every client.
- A token is a bearer credential with no proof-of-possession. It is scoped by
  expiry (30 days default, `--ttl-days`), revocable by id, and visible in
  `auth status`.
- `issue-session!` gained a second arity. Callers that pass no options get
  exactly the record they got before.
