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
