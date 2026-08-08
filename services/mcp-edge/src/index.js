// mcp.itonami.cloud is the MCP resource-server origin — and nothing else.
//
// This Worker forwarded EVERY path to the tunnel, so the whole loopback
// application was on the public internet. Measured 2026-08-08 from off-host:
// `GET /` answered 200 with the full chat UI (651 KB), and `GET /api/state`
// returned the last assistant message's text, the session count, and the model
// configuration — unauthenticated.
//
// That was never a decision anyone made. The application runs with
// `:webauthn-rp-id "localhost"` and `:public-origin "http://localhost:1338"`,
// so a passkey CANNOT be presented from this origin: signing in remotely was
// impossible while reading remotely was not. `/api/identity` was already
// fail-closed there (`authenticated? false`, `user null`), which is what makes
// the `/api/state` reply a leak rather than a feature — the app draws the line
// in one handler and not the other, and this Worker was handing every handler
// to the internet either way.
//
// An explicit table, not a prefix test. `/mcp` and its RFC 9728 metadata
// document are the entire surface this Worker's README claims; a prefix match
// on "/mcp" would also admit "/mcpanything", and a deny-list would need editing
// every time the application grows a route. Adding a path here is a decision
// someone makes on purpose, in a diff.
//
// The methods mirror the application's own route table
// (`cloud.itonami.app.server`) so this stays a gate, not a second opinion about
// what those endpoints accept.
const PROXIED = new Map([
  ["/mcp", new Set(["POST", "GET", "DELETE"])],
  ["/.well-known/oauth-protected-resource/mcp", new Set(["GET"])],
]);

// The same 404 for an unknown path and for a wrong method on a known one.
// A 405 would tell a scanner which paths exist behind here, which is the one
// fact this gate is for.
const denied = () =>
  new Response(JSON.stringify({ error: "not_found" }), {
    status: 404,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });

export default {
  async fetch(request, env) {
    const incoming = new URL(request.url);
    const methods = PROXIED.get(incoming.pathname);
    if (!methods || !methods.has(request.method)) return denied();

    const target = new URL(incoming.pathname + incoming.search, env.ORIGIN);
    const headers = new Headers(request.headers);
    headers.set("host", target.host);
    return fetch(new Request(target, {
      method: request.method,
      headers,
      body: request.body,
      redirect: "manual",
    }));
  },
};
