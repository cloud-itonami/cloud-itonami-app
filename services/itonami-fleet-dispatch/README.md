# itonami-fleet-dispatch

The one public entrance to the itonami actor fleet.

```
GET  /{repo}/{path}   ->  the user Worker named {repo} in ai-gftd-repository-dispatch
```

`{repo}` is the actor's repository name — the same key the fleet catalog uses
as `:repo`, so `fleet_search` and this router agree without a second mapping to
keep in sync.

## Why a dispatch namespace

There are ~1,200 actors. Each wakes for a request and stops — the taxonomy says
so: `cloud-itonami-isic-*` is `:on-demand` and `:must-not [:hold-a-loop]`. A
route and a hostname per actor would be 1,200 pieces of infrastructure standing
idle, and it would run past the ordinary per-account script cap. User Workers in
a dispatch namespace are not bound by that cap, and they have no URL of their
own — which is why `ai-gftd-repository-dispatch` held nothing callable between
its creation on 2026-04-20 and 2026-07-30. This Worker is the binding that
makes them reachable.

## Measured

| request | response |
|---|---|
| `/` | `200` usage |
| `/cloud-itonami-isic-0111/health` | `503` from the actor — it is up and failing closed, see below |
| `/cloud-itonami-isic-9999/health` | `404 {"error":"actor not deployed"}` |
| `/BAD..NAME/x` | `400 {"error":"invalid actor name"}` |

The 503 is correct. `cloud-itonami-isic-0111` has no `KOTOBASE_SECRET_KEY` yet,
and `marketplace.edge` refuses to come up durable-looking without one; its
`/health` reports `did: null`. An actor that answered 200 without a key would
be the failure mode worth fearing.

## What this refuses to do

- **Authenticate.** Each actor gates its own writes. A router deciding who may
  call what would be a second, weaker copy of every governor's admission rule.
- **Retry, cache, or rewrite.** A response comes back as it came, failures
  included. A dispatcher that quietly retried a POST would turn one order into
  two.
- **Fall back.** 404 names the actor, so "not deployed yet" — true of 1,196 of
  them — is distinguishable from "the fleet is down".

## Not bound to a hostname

Deployed on workers.dev while exactly one real actor is behind it. Binding a
hostname is a separate decision from proving the path works, and doing both at
once makes a rollback ambiguous.
