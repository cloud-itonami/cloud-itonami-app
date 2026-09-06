# agent edge

`agent.itonami.cloud` is the command ingress for paired devices — and nothing
else. ADR-2609061500.

## Not deployed

`wrangler.toml` carries no route and no `ORIGIN`, on purpose. Measured
2026-09-06:

| fact | value |
|---|---|
| `agent.itonami.cloud` | **NXDOMAIN** (`dig` status), while `profiles/itonami.edn` declares it as `:residency :ingress` |
| the app's tunnel origin | did not answer (`000`) |

Deploying a door onto a resident nothing reaches would publish an endpoint that
answers 502, and creating the DNS record is what makes the door real. Whoever
turns this on fills in the route and the tunnel hostname, having first answered
the question this Worker cannot: **is the tunnel hostname itself reachable?**
If it is, the table below is bypassed by addressing the origin directly, and the
bound this Worker provides is not a bound. That was not measured here — the
tunnel was down — and "not measured" is not "safe".

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
