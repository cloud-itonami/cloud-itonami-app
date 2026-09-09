# services/

One Worker lives here: **`app-edge`**, and it owns no hostname.

Everything else that used to be in this directory now lives in
[`cloud-itonami/cloud-itonami-apex`](https://github.com/cloud-itonami/cloud-itonami-apex),
which owns the real `itonami.cloud` hosts.

| Worker | Host | Where it lives |
|---|---|---|
| `app-edge` | none — `workers.dev` only | **here** |
| `agent-edge` | `agent.itonami.cloud` | apex |
| `itonami-cloud-webhooks` | `hooks.itonami.cloud` | apex |
| `itonami-fleet-dispatch` | `app.itonami.cloud` | apex |
| `mcp-edge` | `mcp.itonami.cloud` | apex |

`app-edge` stayed because it is the one that reaches into this tree:
it requires `cloud.itonami.app.fleet-core` and `.kotoba-oracle` in shipped
code, and `kotoba-oracle` is required by six other app namespaces, so it is
shared foundation rather than edge code. Deploying to `workers.dev` only is
deliberate — a slice in progress must not be able to take a live host with it.

## Why the four are gone rather than merely unused

The split landed in apex on 2026-08-06 but did not remove the copies here, and
copies of deployable things are not inert. Measured 2026-09-09, on both
repositories' `main`:

- all four copies carried the **same Worker name and the same
  `custom_domain` route** as the apex originals, so `wrangler deploy` from this
  tree would have overwritten the live Worker rather than failing;
- three copies were byte-identical, and **`itonami-fleet-dispatch` had already
  drifted** — the copy here was missing `capital` from its API route test, so
  deploying it would have taken `/api/capital` off `app.itonami.cloud` while
  every check in both repositories stayed green.

Deploy has no fast-forward check. The last person to run it wins, and nothing
was measuring which tree they ran it from.

## The one thing that crossed the boundary

`mobile/test/.../terminal_test.clj` compares the mobile client's
`offered-writes` against the ingress Worker's own POST table. It used to read
that table by slurping `../services/agent-edge/src/index.js`, which stops being
possible once the Worker is in another repository — and a sibling path that
resolves on one machine and not on another is a check that reports a pass
wherever it cannot look.

So the table is pinned at
[`resources/cloud-itonami-apex.agent-edge-writes.edn`](../resources/cloud-itonami-apex.agent-edge-writes.edn),
the same seam `cloud-itonami-cli` already uses for the tables this app
generates. The pin is compared against the live apex Worker by
`scripts/verify-itonami-surface-split.cljs` in the superproject, which is the
only checkout that holds both repositories.
