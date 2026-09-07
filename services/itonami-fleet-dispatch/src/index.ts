/**
 * The one public entrance to the itonami actor fleet.
 *
 * There are ~1,200 actors and each is a Worker uploaded into the
 * `ai-gftd-repository-dispatch` namespace. A user Worker in a dispatch
 * namespace has no URL of its own — that is the point of the arrangement, and
 * it is why the namespace sat empty from 2026-04-20 until now: uploads were
 * possible, but nothing could reach them. This Worker is the binding that
 * makes them reachable.
 *
 *     GET  https://<this>/cloud-itonami-isic-0111/health
 *     POST https://<this>/cloud-itonami-isic-0111/operations
 *              |
 *              +-- env.FLEET.get("cloud-itonami-isic-0111").fetch(...)
 *
 * The first path segment is the actor's repository name — the same key the
 * fleet catalog uses as `:repo`, so `fleet_search` and this router agree
 * without a second mapping to keep in sync.
 *
 * Why a dispatch namespace rather than a Worker each: an actor here wakes for
 * a request and stops. It holds no loop (the taxonomy says so: isic actors are
 * :on-demand and must-not :hold-a-loop), so a route and a hostname per actor
 * would be 1,200 pieces of infrastructure standing idle. It also runs past the
 * ordinary per-account script cap, which namespaced user Workers are not bound
 * by.
 *
 * What this refuses to do:
 *
 *   - It does not authenticate. Each actor already gates its own writes, and a
 *     router that decided who may call what would be a second, weaker copy of
 *     every governor's admission rule.
 *   - It does not rewrite bodies, retry, or cache. A response is passed back as
 *     it came, including a failure. A dispatcher that quietly retried a POST
 *     would turn one order into two.
 *   - It does not fall back when an actor is missing. 404 names the actor, so
 *     a caller can tell "not deployed yet" — true of 1,196 of them — from "the
 *     fleet is down".
 */

export interface Env {
  ITONAMI_APP_GATEWAY_KEY?: string;
  FLEET: { get(name: string): { fetch(request: Request): Promise<Response> } };
}

const json = (body: unknown, status: number) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store" },
  });

/**
 * Repository names are `[a-z0-9-]`. Validating rather than trusting keeps a
 * path segment from being read as anything but a script name — the namespace
 * lookup is the only thing a caller gets to influence here.
 */
const ACTOR = /^[a-z0-9][a-z0-9-]{2,80}$/;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    // The browser workspace owns these reserved paths. All other first
    // segments still dispatch to their existing actor, including kaisya/yotei.
    const localeRoot = url.pathname.match(/^\/(en|ja|zh|es|fr|hi|ar)?\/?$/);
    const oldApp = url.pathname.match(/^\/(?:(en|ja|zh|es|fr|hi|ar)\/)?bots\/app(?:\/|\/index.html)?$/);
    if (oldApp) return Response.redirect(url.origin + '/' + (oldApp[1] ? oldApp[1]+'/' : '') + url.search, 308);
    const page = /^\/(?:(en|ja|zh|es|fr|hi|ar)\/)?signin\/?$/.test(url.pathname);
    const auth = /^\/api\/auth\/(?:session|logout|web3\/(?:challenge|login))\/?$/.test(url.pathname);
    const passkey = /^\/api\/webauthn\/(?:challenge|login)\/?$/.test(url.pathname);
    if (/^\/api\/(?:auth|webauthn)(?:\/|$)/.test(url.pathname) && !auth && !passkey) {
      return json({error:'Authentication route closed'},410);
    }
    const api = auth || passkey || /^\/api\/(my-bots|plugins)(?:\/|$)/.test(url.pathname);
    const asset = /^\/(js|css|fonts)\//.test(url.pathname) || ['/icon.png','/favicon.ico','/apple-touch-icon.png'].includes(url.pathname);
    if (localeRoot || page || api || asset) {
      if (!env.ITONAMI_APP_GATEWAY_KEY) return json({error:'App gateway unavailable'},503);
      const upstream = new URL(url);
      upstream.hostname = 'cloud-itonami.pages.dev';
      if (localeRoot) upstream.pathname = (localeRoot[1] ? '/'+localeRoot[1] : '') + '/bots/app/';
      const headers = new Headers(request.headers);
      // Overwrite caller input; the backend only trusts this server-held key.
      const stamp=String(Date.now());
      const key=await crypto.subtle.importKey('raw',new TextEncoder().encode(env.ITONAMI_APP_GATEWAY_KEY),{name:'HMAC',hash:'SHA-256'},false,['sign']);
      const signature=await crypto.subtle.sign('HMAC',key,new TextEncoder().encode(stamp+'\n'+request.method+'\n'+upstream.pathname+upstream.search));
      const hex=Array.from(new Uint8Array(signature),b=>b.toString(16).padStart(2,'0')).join('');
      headers.delete('x-itonami-app-key');
      headers.set('x-itonami-app-proof',stamp+'.'+hex);
      return fetch(new Request(upstream, new Request(request,{headers,redirect:'manual'})));
    }
    if (/^\/(trust|partners|legal)(?:\/|$)/.test(url.pathname)) {
      return Response.redirect('https://itonami.cloud'+url.pathname+url.search,302);
    }
    const [, actor, ...rest] = url.pathname.split("/");

    if (!actor) {
      return json(
        {
          ok: true,
          service: "itonami-fleet-dispatch",
          namespace: "ai-gftd-repository-dispatch",
          usage: "/{repo}/{path} — repo is the fleet catalog's :repo key",
        },
        200,
      );
    }

    if (!ACTOR.test(actor)) {
      return json({ error: "invalid actor name", actor }, 400);
    }

    // The actor sees the path with its own name stripped, so it can be written
    // and tested against "/health" rather than against wherever the router
    // happens to mount it.
    const inner = new URL(url.toString());
    inner.pathname = "/" + rest.join("/");

    try {
      // get() is lazy — it does not touch the namespace, so a missing script
      // surfaces at fetch() and not here. An earlier version wrapped only
      // get() in this catch and reported every undeployed actor as a 502,
      // which is precisely the distinction it was written to preserve.
      const stub = env.FLEET.get(actor);
      return await stub.fetch(new Request(inner.toString(), request));
    } catch (e) {
      const detail = String(e);
      if (/worker not found/i.test(detail)) {
        // True of 1,196 of the fleet today. A caller needs to tell "nobody has
        // deployed this yet" from "the fleet is down", and only the first is
        // answered by waiting.
        return json({ error: "actor not deployed", actor }, 404);
      }
      // Found, and then failed. Not folded into the 404: "does not exist" and
      // "exists and is broken" call for different actions.
      return json({ error: "actor request failed", actor, detail }, 502);
    }
  },
};
