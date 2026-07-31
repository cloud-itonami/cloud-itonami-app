# ADR-0008: A business is the join of the planes, and nothing else in the app was

Status: accepted (2026-07-30)

Upstream: `com-junkawasaki/root` ADR-2607309600, which is authoritative for this
decision. This file is the app-local record of what shipped here.

## Context

The app could already show the fleet directory (`Fleet`), what one operator can
run (`事業者`), and the contracts that hold (`Contracts`). What it could not show
is a *business*: the thing that has a hypothesis, some repositories that
implement it, a legal entity behind it, and a growth loop that either compounds
or does not.

Those four descriptions exist in this workspace, and none of them can be joined
to another:

| plane | key | grain |
|---|---|---|
| BMC / Lean canvas | `:canvas/product` | 12 products; `cloud-itonami` is ONE of them |
| system dynamics | entity `:id` in `loop-system-dynamics` | one org |
| fleet | `:repo/path`, `:company/lei` | 1,213 actors, 185 legal entities |
| 参与 | `:adoption/repo` (`operator.clj`) | one blueprint |

The grains disagree with each other and with this app: in the BMC plane
`cloud-itonami` is a single product, while here it is the whole portfolio.

## Decision

`cloud.itonami.app.business` holds one key from each plane on one entity, so
「この事業の仮説と、それを検証する repo と、その repo の成熟度」 is one lookup:

```clojure
{:business/slug      "cloud-itonami-5820"
 :organization-id    "org-…"                      ; scoped like a funding account
 :business/canvas    :cloud-itonami               ; → :canvas/product
 :business/model     "…xmile"                     ; → XMILE model
 :business/adoptions ["cloud-itonami-isic-5820"]  ; → :adoption/repo
 :business/repos     ["orgs/cloud-itonami/…"]     ; → :repo/path
 :business/lei       "ZSN2LWNPYW6ISMRUC664"}      ; → :company/lei
```

The entity comes before the analysis views. Without it each view invents its own
notion of a business and the split above is reproduced rather than closed.

### Five states, because "empty" hides which thing went wrong

A face is `:unbound` (no key), `:unresolvable` (a key, but no workspace checkout
to resolve it against), `:missing` (resolvable, not found), `:unreadable` (found,
would not parse) or `:resolved`. Collapsing these into "0 件" would make *nothing
was measured* read as *measured and empty*, which is the same rule `funding`
applies to an unknown balance and `fleet` applies to an unreachable probe.

`:workspace-root` is nil by default: this app is released on its own and cannot
assume a west checkout beside it. So the shipped default is *every plane-backed
face is `:unresolvable`, naming the setting* — not "not found".

### An adoption is not a business

`:operator-adoptions` is keyed by repository, and `Business` sits above it: one
business binds many adoptions. Adoptions bound to no business are reported as a
separate 未割当 bucket rather than mixed in, and the other ~1,200 directory
entries are never counted there — a directory is not a portfolio.

The bucket states its own scope, because `operator/profile` is a single
installation-wide record while a business belongs to an organization. Rather than
imply a scoping that is not there, the payload says `:scope :installation`.

### Nothing here writes to an analysis plane

`canvas-ledger.edn` is a governed append-only event log, `repo-taxonomy.edn` is
generated, and the BMC base datoms are marked 書き換え禁止. This namespace reads
them and writes only `:businesses`. `binds-only-locally?` returns that write
surface and a test asserts it, so adding a plane write has to change the vector.

### The menu separates what you work ON from what you work WITH

Twelve flat nav items had both kinds in one list, so "add a business" and "add a
tool" looked like the same operation. The sidebar is now grouped BUSINESS
(Portfolio / Fleet / 事業者 / Contracts), WORKSPACE (Chat … Storage), SETTINGS.
The group headings are presentational: each button still carries its own
`data-view`, so a group cannot become something that must be opened first.

## What is not in this phase

Values. The Portfolio pane reports whether each face *resolves*, not what it
says: no canvas items, no leverage band, no maturity score, no revenue from the
`market-intel` join. Proving the join before building three analysis views on top
of it is the point of the phase. Canvas, Loops, Repos and Metrics panes follow.

## Phase 2: the Canvas pane

Reading a face's contents needed one thing the workspace did not have: the folded
canvas as data. `gftd canvas md` renders prose into `:doc/body`, which cannot be
queried, and nothing outside that tool could draw the nine blocks.

So the fold stayed where it is and learned to emit data —
`gftd canvas datoms` (superproject) writes
`90-docs/business/<product>-canvas.datoms.edn`, tagged
`:source/dataset "canvas-projection"` so a query can tell it from the pre-fold
base datoms, which are what `90-docs` carried until now (the ledger events are
not in that plane at all, so a `:find` over it answered with the canvas as first
written). `cloud.itonami.app.canvas` reads that projection and folds nothing.

**The write path does not exist.** `canvas-ledger.edn` is append-only and every
event passes `gftd.react/governor`. This app has no governor, so `propose!`
records the proposal in the app's own store and renders the `gftd` command that
would apply it. `writes-only-locally?` returns `[[:canvas-proposals]]` and a test
asserts it, alongside one that reads the projection file's bytes before and after
a proposal and requires them identical.

**Landed-ness is measured.** A proposal's state is derived on every read by
looking for its value in the projection — `:landed` is only true after the
governor accepted it and the projection was rebuilt. A retraction counts as
landed only if its block was actually found, because 「消えた」 and 「見ていない」
would otherwise be the same answer. With no checkout the state is
`:unverifiable`, which is neither.

### The wire collision this uncovered

`clojure.data.json` drops a keyword key's namespace, and the projection has three
pairs that collide once stripped: `:db/id`/`:canvas/id`, `:hyp/status`/`:gate/status`,
`:hyp/evidence`/`:gate/evidence`. A collision does not error — one wins by map
iteration order — and the two `status` values are exactly what this pane exists to
show apart: what the ledger says about a hypothesis versus what the metrics say.
So the payload is re-keyed explicitly, ids travel as strings that keep their
namespace, and a test asserts that no two keys in the shape strip to the same
name. (The same wire rule had already left `renderOperator` reading
`['operator/name']` as undefined; that was fixed in Phase 1.)

## Phase 3: the Loops pane

`:business/leverage` joins the bindings as a sixth face — a leverage ranking and a
stock-flow model are different artifacts, and inferring one's path from the
other's is the guess `fleet` refuses when it will not invent an endpoint.

Both libraries are now dependencies: `org-oasis-open-xmile` owns the model and the
Euler/RK4 simulator (ADR-2607072350 makes it authoritative for this workspace) and
`dynamics` owns the Meadows bands. `cloud.itonami.app.loops` parses the XML into
the shape `xmile.xml/parse-doc` expects, resolves document-level `<sim_specs>` into
the model per XMILE 1.0, and hands it over. It integrates nothing and restates no
band label.

**It refuses two things.** A trajectory it could not compute: `run`'s exception —
array-dimensioned variable, unsupported method — becomes `unsimulatable` carrying
that message, with no series. And a strength score from guessed inputs: nil from
`loop-structural-strength` is `uncomputable-until-measured`, never 0. (The first
implementation shipped `:state :computed :value false`, because `if-some` treats
the `false` from `(and (map? nil) …)` as a present value. A test caught it.)

**Form: small multiples, not one chart.** XMILE variables carry their own units,
so a stock in `repos` and a flow in `repos/day` on one y-axis is the dual-axis
mistake. One panel per variable with its own scale, one series per panel — so no
legend to omit and no categorical palette to get wrong — plus a table view where
an absent value is `—` and never 0. Kind is carried by colour *and* named in text.
The three kind colours are DADS primitives (blue-800 / orange-700 / cyan-700) that
pass the categorical six-checks under all-pairs CVD separation; dark mode is out
of scope because DADS is light-only (ADR-2607262000).

**Two limits stated in the UI.** Which model was simulated when a document
declares several, and that today's leverage ledgers model the fleet's own
repository registration backlog rather than a business's economics — the gap
ADR-2607309600 recorded as still open. There is no `.xmile` file in the workspace
yet either, so the engine path is proven by tests rather than by a shipped model.

## Phase 4: the Repos and Metrics panes

Both read generated files out of the configured workspace and neither has a write
path. What they share is the discipline the earlier phases built: a number nobody
measured is not zero.

**Repos** joins `repo-taxonomy.edn` and `repo-maturity.edn` on `:repo/path`. The
maturity generator's own header promises 「nil when not computable — no fabricated
defaults」 and delivers: `:maturity/stage-score` is nil for 2,732 of 3,899 repos,
because most carry no stage marker to parse. So an unscored axis renders as no bar
at all (a zero-width bar and a 0.0 score are indistinguishable), the mean
composite covers only scored repos, and the excluded count sits beside the mean.
Each axis carries its `-method`, so `:impl`'s size-and-scaffold heuristic is not
mistaken for a parsed declaration.

**Metrics** reads `metrics/<product>.edn` and leads with freshness, because the
files are not uniformly fresh — eleven of twelve were same-day when this was
built and one was 28 days old. `undated` is a third state beside `fresh`/`stale`.

Two refusals in the metrics pane are worth naming. It does not unify `:funnel`:
the three real shapes disagree, and mapping a `freeClaim` onto a `signup` is a
product judgement with no basis here, so unrecognised keys pass through under
their own names marked product-specific. And `requests-7d` never travels without
`probe-4xx-pct`/`error-5xx-pct` in the same map — one real file reports 508,284
requests at 80% 5xx, so the count alone is the wrong fact.

A bug this phase's tests caught **twice in the same namespace**, worth recording
because the shape recurs: `:detail` was built with `cond->` chains that `assoc`
the same key more than once, so the last condition silently overwrote every
earlier one. A blueprint absent from the fleet catalog *and* undeclared reported
only the second reason; then, after that was fixed, a row whose path was also
missing from the generated plane overwrote both. `cond->` threading the same key
twice is a quiet overwrite, not an accumulation — so the append is now a named
function (`add-note`) rather than a habit to remember.

## Sensitivity: leverage measured instead of judged

The Loops pane could show a leverage ranking only by reading a ledger, and the
ledgers rank interventions for the fleet's registration backlog, not for a
business. Producing a business ranking the same way would mean scoring each
intervention's tractability in [0,1] — a judgement nobody has made, and inventing
one would put a guessed number at the centre of the answer.

So the ranking is computed out of the model instead. Each leaf constant is
nudged by 10%, the model is re-run through the same engine, and the result is an
**elasticity**: percent change in a stock per percent change in the parameter.
It is dimensionless, which is the only reason a `tenants/day` rate and a `days`
window can be ordered against each other at all — the same argument that keeps
them off a shared y-axis in the trajectory panes.

Three refusals make it honest.

**Only leaf constants are parameters.** Nudging a variable whose equation
references others would replace a computed value with a fixed one; that is a
different model, not a sensitivity.

**A zero elasticity is disambiguated structurally.** 0.0000 can mean 「動かしても
効かない」 or 「そもそも繋がっていない」, and the second is decidable from the
model's own text — so a constant no equation references is reported as
disconnected, with the referencing variables listed for the rest. On the shipped
`cloud-itonami-saas-funnel.xmile` this is the whole finding:
`Weekly_Human_Uniques` and `Agent_Runs_Per_Week` come back disconnected, which is
the model stating that traffic cannot move the funnel while the traffic→signup
rate is unmeasured. A bare 0 would have hidden that.

**An elasticity with no scale is `:undefined`, not 0.** A parameter whose
baseline is 0 cannot be nudged by a percentage, and a stock whose baseline
outcome is 0 has no denominator. Both are named with their reason.

Measured on the real model (10% perturbation, effect on `Paying_Tenants`):
`Tenant_Signup` +0.58, initial `Non_Paying_Tenants` +0.42, the conversion upper
bound +0.18, `Observation_Window_Days` −0.20, and the two traffic constants
disconnected. The ranking is local to this operating point and says so.

This does **not** close the gap that the leverage ledgers model repository
backlog rather than business economics — it routes around it for models that
exist, and that limit is still stated on the ledger section of the same pane.

## Maturity: the second markdown blob, opened

`maturity-scores.edn` had the same shape as the canvas projection before Phase 2:
a markdown table wrapped in `:doc/body`, readable and unqueryable, so no consumer
outside `70-tools/bmc` could read a score at all. It was recorded as an open gap
when Phase 4 landed. The fix is the same one Phase 2 used — the tool that already
computes the numbers emits them as data (`gftd score datoms`) — and the Canvas
pane reads them, because three of the five BMC dimensions are computed from the
very blocks and hypotheses it renders.

Two facts travel that the markdown could not carry.

**`source`: computed or entered.** Eleven of the fourteen dimensions are
judgements recorded in `maturity-facts.edn`; three are derived from the canvas
and the ledger. The markdown flattened both into one row of decimals.

**`recorded?`: was the judgement actually made.** `score-product` reads facts
with a 0 default, so an unrecorded dimension scores as the *worst* value and the
composite absorbs it silently. Measured on 2026-07-30 this is **latent, not
firing** — all twelve products carry all eleven fact dimensions — and it is
carried anyway, because the first product added without facts would otherwise
appear assessed and poor rather than unassessed. The projection reports the
arithmetic; it does not change it.

## The wire may not carry a File

`business/workspace` keeps a live `java.io.File` under `:file` so the face
resolvers can open things beneath the root. `portfolio` returned that map whole,
and `clojure.data.json/write-str` throws on a `File` — so `GET /api/business`
answered **500 `Don't know how to write JSON of class java.io.File`** for every
installation that had actually set `:business :workspace-root`. That is not an
edge case: it is the only configuration in which the Portfolio has anything to
resolve. An installation with no workspace worked, and configuring the setting
the pane exists for broke it.

The suite did not catch it because `business_http_test`'s config sets no
workspace root, so only the `:unset` branch — which carries no `:file` — ever
reached the serializer, and `business_test` calls `portfolio` directly and never
serializes at all. Every assertion passed against the one shape that could not
fail.

`wire-workspace` now strips `:file` at that boundary, named rather than inlined
so the next payload carrying a workspace has something to call. The regression
test configures a real root and asserts both that the route is 200 and that
`:file` is absent from the wire; it fails five assertions without the fix.

Third instance of one rule in this pane: **what the server holds and what the
browser reads are different shapes, and only a test that speaks the wire can
tell them apart.** Phase 1 found it as `renderOperator` reading
`['operator/name']` after `write-str` dropped the namespace; Phase 2 found it as
`:hyp/status` and `:gate/status` colliding once stripped; here it is a value
`write-str` cannot express at all.

## Consequences

- A business is created and bound by hand. Nothing is derived — which repo or
  canvas belongs to which business is a judgement, not a computation, and a
  constructor guessing from a name prefix would invent the binding this entity
  exists to record. An installation where nobody creates one has an empty
  Portfolio, correctly.
- `GET /api/business` answers 409 `organization-required` for a session with no
  Organization ID, rather than the 401 `funding` returns for the same shape: the
  client is logged in, and 401 sends them to fix something that is not broken.
