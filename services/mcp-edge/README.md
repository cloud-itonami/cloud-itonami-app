# MCP edge

`mcp.itonami.cloud` is the stable public resource-server origin. This Worker
forwards **two paths** to the Cloudflare Tunnel hostname and answers 404 to
everything else; the application continues to run against its durable local
EDN/DataLad workspace instead of an ephemeral edge filesystem.

| proxied | methods |
|---|---|
| `/mcp` | POST, GET, DELETE |
| `/.well-known/oauth-protected-resource/mcp` | GET |

**This is the whole public surface of the machine.** The Worker forwarded every
path until 2026-08-08, which put the entire loopback application on the
internet: `GET /` served the chat UI and `GET /api/state` returned the last
assistant message unauthenticated. Nothing about that was intended — the
application is configured `:webauthn-rp-id "localhost"`, so no one could sign in
from this origin even though anyone could read from it. Widening the table in
`src/index.js` publishes another endpoint of a personal workstation; do it on
purpose or not at all.

The tunnel origin is configured with `ORIGIN` in `wrangler.toml`. Keep the
application bound to loopback, and expose it only through the named tunnel.

Deploy from this directory with:

```sh
npx wrangler deploy
```

OAuth bearer validation remains fail-closed in the application. Provision the
configured authorization server and its RFC 7662 introspection credentials
before issuing external agent tokens.
