# ADR-0018: Every operation is a command, and the CLI starts its own server

## Status

Accepted. 2026-08-05. 2026-09-02: `deps.edn` aliases `:server` and `:cli`
are gone. Do not run `clojure -M:server` or `clojure -M:cli`. The loopback
host is `bin/cloud-itonami-server`. `bin/itonami` is closed as a CLI.

## Context

Two separate things made the terminal a second-class way to use this app, and
each hid the other.

**The CLI covered almost nothing.** `server.clj` serves 225 routes. The CLI had
seventeen commands. Nothing anywhere reported that difference, because the
commands were a hand-written list, and a hand-written list of what an app can do
drifts in exactly one direction: a route lands, nobody adds the command, and the
gap stays invisible until somebody goes looking for a command that was never
written. The seventeen were not a considered subset — they were the ones somebody
had needed so far.

**Every command needed the desktop app already open.** `bin/cloud-itonami-app`
starts a server *and* a native shell window. The CLI is an HTTP client of the
server (for good reason — below), so it could only talk to one somebody had
started that way. Reading your own inbox from a terminal meant opening a desktop
app first, and on a machine where nobody had, an agent could do nothing at all.

Why the CLI is a client is not incidental and is not revisited here.
`store/state` is `(defonce state (atom (load-state)))` — read once when a process
starts and never re-read. Two processes holding one data directory each act on a
snapshot frozen at their own start and each drops what the other wrote, in both
directions, silently. Exactly one process may own the store.

## Decision

### The commands are derived from the routes

`cloud.itonami.app.route-scan` reads `handler`'s own `cond` — method, path, which
session gate the clause applies, which keys its body reads — and produces a
registry. `dev/gen_commands.cljs` writes it to
`resources/cloud-itonami-app.commands.edn`, `cloud.itonami.app.commands` loads
it, and `cli` dispatches over it.

The scanner is `.cljc` because it has two callers in two runtimes: the generator
under nbb and `commands-test` under the JVM. Written twice they would disagree
eventually, and the disagreement would read as drift in the routes rather than in
the two scanners.

`commands-test` re-scans and compares. **Adding a route without regenerating the
registry fails the suite.** That test, not the registry, is what makes the
coverage claim still true a month from now.

Command names come from the path. A verb is appended only where two routes would
otherwise collide, so `business bind` stays short while `/api/business` becomes
`business list` and `business create`. Capturing path segments become named
parameters — `--document`, `--envelope`, `--work-item` — fillable by flag or
positionally.

Seventeen commands stay hand-written, each for the same reason: the generated
form would send a string where the route wants a list (`business bind --repos
a,b`), a file's contents (`tenant repository-write --file`), or a nested object
(`tenant connect`). Teaching the generator those shapes would mean writing a
schema for every route from the outside, so the few that need one keep theirs.

Unknown flags are passed through rather than refused. The `:flags` in the
registry are read off the source and cannot be trusted to be complete;
validating against them would refuse calls the server would have accepted.

### The CLI starts a headless server when there is not one

`cloud.itonami.app.server-process` probes `/health`; if nothing answers it runs
`clojure -M:server` — not `bin/cloud-itonami-app`, which is the script that opens
the window — waits for the health check, and records the pid. Every command that
makes a request goes through one `ensure-server!` call site, so a command added
later cannot forget it. `up`, `down` and `status` drive the lifecycle explicitly.

This does not weaken the single-writer rule; it satisfies it. The server is what
owns the store, so the CLI makes sure one exists and then talks to it as before.

Three details are load-bearing:

- **A lock file, not probe-then-spawn.** Probe-then-spawn races over a window as
  wide as JVM startup, and the failure it produces is two servers on one data
  directory — the thing this is meant to prevent. `CREATE_NEW` is atomic, so one
  caller spawns and the rest wait. A lock older than the startup budget is a
  crash, not a competitor, and is cleared.
- **A slow start is not a failed start.** Measured: a cold `clojure -M:server`
  takes over a minute to answer `/health`, longer with a cold classpath cache.
  The budget is 300s, and on expiry a child that is still alive is *left running*
  and reported as starting. Killing it would throw away the minutes it had spent
  and make the next invocation begin again, which is how a slow first run becomes
  one that never succeeds.
- **Only a pid this process owns is recorded.** The wait is long enough for
  another install to come up inside it. If the child died and something else
  answered, no pid is written — otherwise `down` would kill a server it did not
  start.

`CLOUD_ITONAMI_API_URL` disables all of it: nothing local is started, stopped or
reported for a CLI pointed at a hosted control plane.

### `bin/itonami` resolves the app directory

`clojure -M:cli` only works from the app's own directory. The launcher resolves
that directory from its own path and runs the CLI there, so the command works
from anywhere. It parses no flags and interprets no results — a launcher that
understood the commands would be a second place for them to be wrong. Written in
nbb, per the workspace rule that new scripts are ClojureScript.

The resident layout is one further part of that resolution. A launcher located
at `~/.cloud-itonami/app/bin/itonami` defaults `CLOUD_ITONAMI_DATA_DIR` to
`~/.cloud-itonami/data`, matching the launchd service; otherwise `auth login`
would silently read `app/data/agent-enrollment.key` while the server owns the
sibling store. Explicit configuration continues to win. `bin/itonami-mcp`
applies the same rule to the stdio adapter, so CLI and MCP resolve one Keychain
session against one local ownership root.

### What is deliberately not a command

- **The 18 funding, settlement and governed-approval routes.** `approve/finish`
  needs a WebAuthn user-verifying assertion. No CLI can produce one and no agent
  can either (ADR-0006, ADR-0009). These are absent rather than
  present-and-certain-to-refuse, because a command that always refuses invites a
  caller to try and tells them nothing about why. `itonami commands` reports the
  count, so the boundary is a number an operator reads rather than a silence they
  discover.
- **The 49 unauthenticated routes.** Page rendering, `/health`, `.well-known`,
  webhook receivers, and the passkey/OAuth handshake steps. Either not
  operations, or not answerable outside a browser.
- **The MCP surface is unchanged.** ADR-0004 keeps mail, calendar, drive and chat
  off MCP deliberately — they sit behind the Passkey session on `/api/*`, and
  reaching around that from a surface whose consent model belongs to the client
  would weaken a gate the app means. Widening the CLI does not re-decide that:
  the operator runs the CLI, a client's model runs MCP.

## What building it caught

Two defects worth recording, because both were invisible failures rather than
loud ones.

**Routes behind a named pattern were not scanned at all.** `page-route?` matches
three paths through `(def ^:private page-pattern #"…")`, and a scanner reading
only regex literals never saw the clause. No command was generated *and no test
reported one missing*, because the gate compares the registry against the same
scanner. A gate that shares a blind spot with the thing it checks is not a gate.
`expand-pattern-vars` inlines the definitions first, and the three routes are now
pinned by name in the test.

**The two runtimes scanned different routes.** On the JVM, `str/replace` reads
`\` and `$` in a *replacement string* as escapes, so a pattern containing
`(\d+)` came back as `(d+)`; ClojureScript substituted it literally. The nbb
generator called the registry current and the JVM test called it stale — which is
precisely the disagreement the shared `.cljc` exists to make visible, and it
showed up the first time the two had anything to disagree about. A function
replacement is literal in both.

## Consequences

- 158 of 225 routes are commands, up from 17, and the remaining 67 are accounted
  for by category and count rather than left unexamined.
- A route added without regenerating the registry breaks the suite. Coverage is a
  maintained property rather than a claim made once.
- `itonami <anything>` works from any directory with no app window, starting a
  server if none is running. First run on a cold classpath blocks for about a
  minute; after that the server is resident and a command costs only the CLI's
  own JVM start.
- A **fresh** data directory still needs the browser once. `agent-session` enroll
  refuses with `no-owner` until a Passkey has been enrolled and an organization
  created, and that flow is WebAuthn — the same boundary as above, met at the
  beginning rather than at a payment. The CLI cannot bootstrap an install from
  nothing and should not be described as if it could.
- The CLI still cannot approve a PAYMENT. It may ask, record, and carry out what
  a human already approved. (It is not a general refusal any more: ADR-0060
  lets an agent session decide a delegated Bot's card. Payment consent is a
  WebAuthn assertion an agent cannot produce, so that boundary is structural
  and did not move.)
