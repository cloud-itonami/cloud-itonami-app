interface Env {
  EVENTS: KVNamespace;
  RELAY_ACCESS_TOKEN: string;
  GOOGLE_VERIFICATION_TOKEN: string;
  GOOGLE_PUSH_AUDIENCE?: string;
  GOOGLE_PUSH_SERVICE_ACCOUNT?: string;
  GRAPH_CLIENT_STATE: string;
}

type MailEvent = {
  id: string;
  provider: "google" | "microsoft";
  tenant: string;
  receivedAt: string;
  payload: Record<string, unknown>;
};

type AccountLink = {
  schema: "cloud.itonami.account-link.v1" | "cloud.itonami.account-link.v2";
  id: string;
  "subject-did": string;
  namespace?: "bip122";
  network?: "mainnet" | "testnet";
  address: string;
  "chain-id"?: number;
  account: string;
  did: string;
  "rp-id": string;
  resource: string;
  message: string;
  signature: string;
  "proof-type"?: "bip322-simple";
  proof?: { type: "bip322-simple"; message: string; signature: string };
  capabilities?: string[];
  status: "active" | "revoked";
  "connected-at": string;
  "revoked-at"?: string;
};

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
    },
  });

const secureEqual = (left = "", right = "") => {
  const a = new TextEncoder().encode(left);
  const b = new TextEncoder().encode(right);
  if (a.length !== b.length) return false;
  let difference = 0;
  for (let index = 0; index < a.length; index += 1) {
    difference |= a[index] ^ b[index];
  }
  return difference === 0;
};

const decodeBase64Url = (value: string) => {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const binary = atob(normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "="));
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
};

const decodeJsonBase64Url = (value: string) =>
  JSON.parse(new TextDecoder().decode(decodeBase64Url(value))) as Record<string, unknown>;

const verifyGoogleJwt = async (request: Request, env: Env) => {
  if (!env.GOOGLE_PUSH_AUDIENCE && !env.GOOGLE_PUSH_SERVICE_ACCOUNT) return true;
  const authorization = request.headers.get("authorization") || "";
  const token = authorization.match(/^Bearer (.+)$/)?.[1];
  if (!token) {
    console.warn("google oidc rejected", { reason: "missing_bearer" });
    return false;
  }
  const [encodedHeader, encodedClaims, encodedSignature] = token.split(".");
  if (!encodedHeader || !encodedClaims || !encodedSignature) {
    console.warn("google oidc rejected", { reason: "malformed_jwt" });
    return false;
  }
  const header = decodeJsonBase64Url(encodedHeader);
  const claims = decodeJsonBase64Url(encodedClaims);
  if (header.alg !== "RS256" || typeof header.kid !== "string") {
    console.warn("google oidc rejected", { reason: "invalid_header", alg: header.alg });
    return false;
  }
  const response = await fetch("https://www.googleapis.com/oauth2/v3/certs", {
    cf: { cacheTtl: 3600, cacheEverything: true },
  });
  const keys = (await response.json()) as { keys: JsonWebKey[] };
  const key = keys.keys.find((candidate) =>
    (candidate as JsonWebKey & { kid?: string }).kid === header.kid);
  if (!key) {
    console.warn("google oidc rejected", { reason: "unknown_kid" });
    return false;
  }
  const cryptoKey = await crypto.subtle.importKey(
    "jwk",
    key,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"],
  );
  const validSignature = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    cryptoKey,
    decodeBase64Url(encodedSignature),
    new TextEncoder().encode(`${encodedHeader}.${encodedClaims}`),
  );
  const now = Math.floor(Date.now() / 1000);
  const valid = (
    validSignature &&
    (claims.iss === "https://accounts.google.com" || claims.iss === "accounts.google.com") &&
    Number(claims.exp || 0) > now &&
    (!env.GOOGLE_PUSH_AUDIENCE || claims.aud === env.GOOGLE_PUSH_AUDIENCE) &&
    (!env.GOOGLE_PUSH_SERVICE_ACCOUNT ||
      (claims.email === env.GOOGLE_PUSH_SERVICE_ACCOUNT && claims.email_verified === true))
  );
  if (!valid) {
    console.warn("google oidc rejected", {
      reason: "claims_or_signature",
      validSignature,
      issuer: claims.iss,
      audience: claims.aud,
      email: claims.email,
      emailVerified: claims.email_verified,
      expectedAudience: env.GOOGLE_PUSH_AUDIENCE,
      expectedEmail: env.GOOGLE_PUSH_SERVICE_ACCOUNT,
    });
  }
  return valid;
};

const enqueue = async (env: Env, event: MailEvent) => {
  const dedupeKey = `dedupe:${event.provider}:${event.id}`;
  if (await env.EVENTS.get(dedupeKey)) return false;
  const eventKey = `event:${event.tenant}:${event.receivedAt}:${event.provider}:${event.id}`;
  await Promise.all([
    env.EVENTS.put(dedupeKey, "1", { expirationTtl: 7 * 24 * 60 * 60 }),
    env.EVENTS.put(eventKey, JSON.stringify(event), { expirationTtl: 7 * 24 * 60 * 60 }),
  ]);
  return true;
};

const googleWebhook = async (request: Request, env: Env) => {
  const url = new URL(request.url);
  if (!secureEqual(url.searchParams.get("token") || "", env.GOOGLE_VERIFICATION_TOKEN)) {
    console.warn("google push rejected", { reason: "verification_token" });
    return json({ error: "invalid_verification_token" }, 403);
  }
  if (!(await verifyGoogleJwt(request, env))) {
    return json({ error: "invalid_oidc_token" }, 403);
  }
  const body = (await request.json()) as {
    message?: { data?: string; messageId?: string; publishTime?: string };
    subscription?: string;
  };
  if (!body.message?.data || !body.message.messageId) {
    return json({ error: "invalid_pubsub_envelope" }, 400);
  }
  const payload = decodeJsonBase64Url(body.message.data);
  await enqueue(env, {
    id: body.message.messageId,
    provider: "google",
    tenant: String(payload.emailAddress || "default"),
    receivedAt: body.message.publishTime || new Date().toISOString(),
    payload: { ...payload, subscription: body.subscription },
  });
  return new Response(null, { status: 204 });
};

const graphWebhook = async (request: Request, env: Env) => {
  const validationToken = new URL(request.url).searchParams.get("validationToken");
  if (validationToken) {
    return new Response(validationToken, {
      status: 200,
      headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" },
    });
  }
  const body = (await request.json()) as {
    value?: Array<Record<string, unknown> & { clientState?: string; id?: string; tenantId?: string }>;
  };
  if (!Array.isArray(body.value) || !body.value.length) {
    return json({ error: "invalid_graph_envelope" }, 400);
  }
  if (body.value.some((notification) =>
    !secureEqual(notification.clientState || "", env.GRAPH_CLIENT_STATE))) {
    return json({ error: "invalid_client_state" }, 403);
  }
  await Promise.all(body.value.map((notification, index) =>
    enqueue(env, {
      id: String(notification.id || `${Date.now()}-${index}`),
      provider: "microsoft",
      tenant: String(notification.tenantId || "default"),
      receivedAt: new Date().toISOString(),
      payload: notification,
    })));
  return new Response(null, { status: 202 });
};

const relayAuthorized = (request: Request, env: Env) => {
  const bearer = request.headers.get("authorization")?.match(/^Bearer (.+)$/)?.[1] || "";
  return secureEqual(bearer, env.RELAY_ACCESS_TOKEN);
};

const accountLinkHash = async (subjectDid: string) => {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(subjectDid),
  );
  return [...new Uint8Array(digest)]
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
};

const validAccountLinkShape = (value: unknown): value is AccountLink => {
  if (!value || typeof value !== "object") return false;
  const link = value as Record<string, unknown>;
  const common = (
    (link.schema === "cloud.itonami.account-link.v1" ||
      link.schema === "cloud.itonami.account-link.v2") &&
    typeof link.id === "string" && /^[0-9a-f-]{36}$/i.test(link.id) &&
    typeof link["subject-did"] === "string" &&
    link["subject-did"].startsWith("did:") && link["subject-did"].length <= 512 &&
    typeof link.address === "string" && link.address.length <= 128 &&
    typeof link.account === "string" && link.account.length <= 256 &&
    typeof link.did === "string" && link.did.length <= 320 &&
    typeof link["rp-id"] === "string" && link["rp-id"].length <= 253 &&
    typeof link.resource === "string" && link.resource.length <= 1024 &&
    typeof link.message === "string" && link.message.length <= 16_384 &&
    typeof link.signature === "string" && link.signature.length <= 16_384 &&
    (link.status === "active" || link.status === "revoked") &&
    typeof link["connected-at"] === "string"
  );
  if (!common) return false;
  if (link.schema === "cloud.itonami.account-link.v1") {
    return (
      /^0x[0-9a-f]{40}$/i.test(String(link.address)) &&
      Number.isSafeInteger(link["chain-id"]) && Number(link["chain-id"]) > 0 &&
      String(link.message).includes(`\n- ${link.resource}`) &&
      /^0x[0-9a-f]{130}$/i.test(String(link.signature))
    );
  }
  const proof = link.proof as Record<string, unknown> | undefined;
  return (
    link.namespace === "bip122" &&
    ["mainnet", "testnet"].includes(String(link.network)) &&
    /^bip122:[a-f0-9]{64}:[a-zA-Z0-9]{14,128}$/.test(String(link.account)) &&
    /^did:pkh:bip122:/.test(String(link.did)) &&
    link["proof-type"] === "bip322-simple" &&
    proof?.type === "bip322-simple" &&
    proof.message === link.message &&
    proof.signature === link.signature &&
    String(link.message).includes(`Resource: ${link.resource}`)
  );
};

const upsertAccountLink = async (request: Request, env: Env) => {
  if (!relayAuthorized(request, env)) return json({ error: "unauthorized" }, 401);
  const body = await request.json() as { link?: unknown };
  if (!validAccountLinkShape(body.link)) {
    return json({ error: "invalid_account_link" }, 400);
  }
  const link = body.link;
  const subjectHash = await accountLinkHash(link["subject-did"]);
  const key = `account-link:${subjectHash}:${link.id}`;
  const existing = await env.EVENTS.get<AccountLink>(key, "json");
  if (existing && existing.status === "revoked" && link.status !== "revoked") {
    return json({ error: "account_link_is_revoked" }, 409);
  }
  await env.EVENTS.put(key, JSON.stringify(link));
  return json({ ok: true, id: link.id, status: link.status });
};

const listAccountLinks = async (request: Request, env: Env) => {
  if (!relayAuthorized(request, env)) return json({ error: "unauthorized" }, 401);
  const subjectDid = new URL(request.url).searchParams.get("subjectDid") || "";
  if (!subjectDid.startsWith("did:") || subjectDid.length > 512) {
    return json({ error: "invalid_subject_did" }, 400);
  }
  const subjectHash = await accountLinkHash(subjectDid);
  const listed = await env.EVENTS.list({
    prefix: `account-link:${subjectHash}:`,
    limit: 100,
  });
  const links = (await Promise.all(
    listed.keys.map(({ name }) => env.EVENTS.get<AccountLink>(name, "json")),
  )).filter((link): link is AccountLink =>
    Boolean(link && link["subject-did"] === subjectDid));
  return json({
    schema: "cloud.itonami.account-links.v1",
    links,
    complete: listed.list_complete,
  });
};

const poll = async (request: Request, env: Env) => {
  if (!relayAuthorized(request, env)) return json({ error: "unauthorized" }, 401);
  const limit = Math.min(Number(new URL(request.url).searchParams.get("limit") || 50), 100);
  const listed = await env.EVENTS.list({ prefix: "event:", limit });
  const events = (await Promise.all(listed.keys.map(async ({ name }) => {
    const event = await env.EVENTS.get<MailEvent>(name, "json");
    return event ? { key: name, event } : null;
  }))).filter(Boolean);
  return json({
    events,
    cursor: listed.list_complete ? null : listed.cursor,
    complete: listed.list_complete,
  });
};

const acknowledge = async (request: Request, env: Env) => {
  if (!relayAuthorized(request, env)) return json({ error: "unauthorized" }, 401);
  const body = (await request.json()) as { keys?: string[] };
  const keys = (body.keys || []).filter((key) => key.startsWith("event:")).slice(0, 100);
  await Promise.all(keys.map((key) => env.EVENTS.delete(key)));
  return json({ acknowledged: keys.length });
};

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const { pathname } = new URL(request.url);
    try {
      if (request.method === "GET" && pathname === "/health") {
        return json({
          status: "ok",
          service: "itonami-cloud-webhooks",
          capabilities: ["mail-events", "account-links"],
        });
      }
      if (request.method === "POST" && pathname === "/v1/webhooks/google") {
        return googleWebhook(request, env);
      }
      if (request.method === "POST" && pathname === "/v1/webhooks/microsoft") {
        return graphWebhook(request, env);
      }
      if (request.method === "GET" && pathname === "/v1/events/poll") {
        return poll(request, env);
      }
      if (request.method === "POST" && pathname === "/v1/events/ack") {
        return acknowledge(request, env);
      }
      if (request.method === "POST" && pathname === "/v1/account-links/upsert") {
        return upsertAccountLink(request, env);
      }
      if (request.method === "GET" && pathname === "/v1/account-links") {
        return listAccountLinks(request, env);
      }
      return json({ error: "not_found" }, 404);
    } catch (error) {
      console.error("webhook request failed", error);
      return json({ error: "internal_error" }, 500);
    }
  },
} satisfies ExportedHandler<Env>;
