// agent.itonami.cloud is the COMMAND ingress for paired devices — and nothing else.
//
// ## What this exists for
//
// The itonami resident binds loopback. A phone cannot reach it, and it cannot
// sign in to it either: the application is Passkey-only with
// `:webauthn-rp-id "localhost"`, so a passkey CANNOT be presented from a remote
// origin. `services/mcp-edge/src/index.js` measured the consequence on
// 2026-08-08 — "signing in remotely was impossible while reading remotely was
// not" — because that Worker forwarded EVERY path and put the whole loopback
// application on the internet. A paired device carries a minted agent session
// instead (`cloud.itonami.app.agent-session`), and this Worker is the only
// door it comes through. ADR-2609061500.
//
// ## An explicit table, not a prefix test
//
// The same decision mcp-edge made, for the same reason it wrote down: a prefix
// match on "/api/agent-bots" would also admit "/api/agent-botsanything", and a
// deny-list would need editing every time the application grows a route.
// Adding a path here publishes one more endpoint of a personal workstation.
// Do it on purpose or not at all.
//
// This table needs patterns where mcp-edge needed only literals, because the
// routes carry ids. Every pattern is ANCHORED and every segment is bounded to
// a conservative charset — an unanchored or greedy pattern is a prefix test
// wearing a regex.
//
// ## What is deliberately NOT here
//
// Every approval. `bots decide`, `bots provision`, the workforce routes, the
// Wallet, the mail and drive surfaces. Approving a write needs a WebAuthn
// user-verifying assertion (ADR-0006 / ADR-0083), which no agent session can
// produce — so an approval route behind this door would be refused by the
// application anyway, and publishing it would only widen what an attacker who
// holds a stolen token can reach.
//
// The client refuses these too (`cloud.itonami.mobile.terminal/offered-writes`).
// Two gates for one rule is deliberate: this one is the authority and answers
// 404, which read back on a phone says "no such thing" — false, since the
// command exists and this surface declines it. The client says the true
// sentence; this says the safe one.

const SEGMENT = "[A-Za-z0-9._~-]{1,128}";

const ROUTES = [
  // ---- reads -------------------------------------------------------------
  ["GET", "/api/agent-bots"],
  ["GET", "/api/agent-bots/{id}/messages"],
  ["GET", "/api/profiles"],
  // `auth status` — so a device can ask whether its own session is still good
  // without being told by a failure somewhere else.
  ["GET", "/api/agent-session"],
  ["GET", "/health"],

  // ---- the conversation --------------------------------------------------
  // The only writes. Each one is a message; none of them decides anything.
  ["POST", "/api/agent-bots/{id}/messages"],
  ["POST", "/p/{profile}/api/sessions"],
  ["POST", "/p/{profile}/api/sessions/{session}/messages"],
];

// Compiled once at module scope. `{name}` becomes one bounded segment;
// everything else is escaped, so a template can never contribute regex syntax.
const TABLE = ROUTES.map(([method, template]) => ({
  method,
  template,
  pattern: new RegExp(
    "^" +
      template
        .split(/(\{[a-z]+\})/)
        .map((part) =>
          /^\{[a-z]+\}$/.test(part)
            ? SEGMENT
            : part.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"),
        )
        .join("") +
      "$",
  ),
}));

/**
 * Whether this request is carried, and which template carried it.
 *
 * Exported so the table can be asserted without a network. A table nobody can
 * test is a policy nobody can review.
 */
export function decide(method, pathname) {
  for (const route of TABLE) {
    if (route.method === method && route.pattern.test(pathname)) {
      return { carried: true, template: route.template };
    }
  }
  return { carried: false };
}

// The same 404 for an unknown path and for a wrong method on a known one.
// A 405 would tell a scanner which paths exist behind here, which is the one
// fact this gate is for. mcp-edge chose this and stated why; restating it here
// rather than sharing a module keeps each Worker's refusal readable on its own.
const denied = () =>
  new Response(JSON.stringify({ error: "not_found" }), {
    status: 404,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });

const misconfigured = () =>
  new Response(JSON.stringify({ error: "origin_not_configured" }), {
    status: 503,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });

export default {
  async fetch(request, env) {
    const incoming = new URL(request.url);
    if (!decide(request.method, incoming.pathname).carried) return denied();

    // An unset ORIGIN is 503 and not a forward to somewhere unintended.
    // `new URL(path, undefined)` throws, and a Worker that throws returns 1101
    // — which reads as the application failing rather than as this Worker never
    // having been told where to send.
    if (!env || !env.ORIGIN) return misconfigured();

    const target = new URL(incoming.pathname + incoming.search, env.ORIGIN);
    const headers = new Headers(request.headers);
    headers.set("host", target.host);
    return fetch(
      new Request(target, {
        method: request.method,
        headers,
        body: request.body,
        redirect: "manual",
      }),
    );
  },
};
