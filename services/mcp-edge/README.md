# MCP edge

`mcp.itonami.cloud` is the stable public resource-server origin. This Worker
forwards requests to the Cloudflare Tunnel hostname; the application
continues to run against its durable local EDN/DataLad workspace instead of an
ephemeral edge filesystem.

The tunnel origin is configured with `ORIGIN` in `wrangler.toml`. Keep the
application bound to loopback, and expose it only through the named tunnel.

Deploy from this directory with:

```sh
npx wrangler deploy
```

OAuth bearer validation remains fail-closed in the application. Provision the
configured authorization server and its RFC 7662 introspection credentials
before issuing external agent tokens.
