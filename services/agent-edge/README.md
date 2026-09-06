# agent edge

`agent.itonami.cloud` is the command ingress for paired devices — and nothing
else. ADR-2609061500.

## Deployed

`agent.itonami.cloud` → Worker `cloud-itonami-agent-edge` → named tunnel
`itonami-agent` → the resident on `127.0.0.1:1338`. The tunnel is a launchd
resident (`~/Library/LaunchAgents/cloud.itonami.agent-tunnel.plist`,
`KeepAlive`), config `~/.cloudflared/config-itonami-agent.yml`.

### The origin has ONE label, and that is not cosmetic

Cloudflare universal SSL covers `*.etzhayyim.com` and **not deeper**. The first
attempt routed `agent-origin.itonami.cloud.etzhayyim.com` — four labels, no
certificate — and the edge aborted the handshake: `curl` exit 35, even with
`-k`, which cannot help because the handshake never completes. The origin is
`itonami-agent.etzhayyim.com`.

**`services/mcp-edge` has the same defect.** Its `ORIGIN` is
`mcp.itonami.cloud.etzhayyim.com`, also four labels, also without a
certificate. That Worker's origin is unreachable by TLS today, which is a
separate fact from its tunnel being down, and it was not known before this.

### The bypass question is closed

`services/agent-edge` used to record an unmeasured question: is the tunnel
hostname independently reachable, and if so is this table bypassed? It is
reachable — and it is not a bypass, because **the tunnel carries the same path
table** (cloudflared ingress rules). Measured 2026-09-06 against
`itonami-agent.etzhayyim.com` directly:

| path | at the tunnel |
|---|---|
| `/health` | 200 |
| `/api/agent-bots`, `/api/profiles`, `/api/agent-session` | 401 (no session) |
| `/`, `/api/state`, `/api/identity` | **404** |
| an approval path, `/api/agent-botsanything`, `…/messages/extra`, `//messages` | **404** |

What the tunnel layer cannot do is distinguish **method** — cloudflared matches
on path only. `POST /api/agent-bots` reaches the resident through the tunnel and
is refused 404 by this Worker. The resident answers 401 without a session on
every `/api/*` route this carries (measured the same day), so the method
distinction is defence in depth and not the floor.

### Measured through, from outside

With a minted agent session (`label phone-…`, 7-day TTL), through
`https://agent.itonami.cloud`:

| | |
|---|---|
| `GET /api/agent-bots` with the token | **200**, 231 bots |
| `GET /api/profiles` with the token | **200**, 231 profiles |
| the same, without the token | **401** |
| `/api/state` and an approval path, **with** the token | **404** |

That last row is the one worth keeping: a valid token does not widen the table.

## What comes through

An explicit table, not a prefix test. The same decision `services/mcp-edge`
made, for the reason it wrote down: a prefix match on `/api/agent-bots` also
admits `/api/agent-botsanything`, and a deny-list needs editing every time the
application grows a route.

| method | path |
|---|---|
| GET | `/api/agent-bots` |
| GET | `/api/agent-bots/{id}/messages` |
| GET | `/api/profiles` |
| GET | `/api/agent-session` |
| GET | `/health` |
| POST | `/api/agent-bots/{id}/messages` |
| POST | `/p/{profile}/api/sessions` |
| POST | `/p/{profile}/api/sessions/{session}/messages` |

The three writes are messages. None of them decides anything.

**Adding a row publishes one more endpoint of a personal workstation. Do it on
purpose or not at all.**

## What does not, and why it is a 404

Every approval (`bots decide`), workforce provisioning, minting a session
(`POST /api/agent-session` — a remote caller must not be able to mint its own),
`GET /api/state`, and the root document. `mcp-edge` measured on 2026-08-08 what
happens when the whole loopback application is behind a public Worker: `GET /`
served the chat UI and `GET /api/state` returned the last assistant message,
unauthenticated.

Unknown path and wrong method on a known path get the **same 404**. A 405 would
tell a scanner which paths exist behind here, which is the one fact this gate is
for.

## Two gates, one rule

The client refuses the same set (`cloud.itonami.mobile.terminal/offered-writes`)
before any socket opens. That is deliberate rather than redundant: this Worker
is the authority and answers 404, which read back on a phone says *no such
thing* — false, because the command exists and the surface declines it. The
client says the true sentence; this says the safe one.

They must not drift. `mobile/test/.../terminal_test.clj` parses this file's own
table and asserts the two sets are equal; it was shown to go red when one side
gains a route the other does not.

## Test

```sh
npm test        # or: npx nbb test/table_test.cljs
```

15 checks, no network. The ones that matter are the refusals — including that
the patterns are anchored at both ends and that an id segment cannot contain a
path separator, since a prefix test wearing a regex is the failure this table
exists to avoid.
