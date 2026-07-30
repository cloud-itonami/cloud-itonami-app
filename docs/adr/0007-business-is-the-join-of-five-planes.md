# ADR-0007: A business is the join of five planes, and nothing else in the app was

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

## Consequences

- A business is created and bound by hand. Nothing is derived — which repo or
  canvas belongs to which business is a judgement, not a computation, and a
  constructor guessing from a name prefix would invent the binding this entity
  exists to record. An installation where nobody creates one has an empty
  Portfolio, correctly.
- `GET /api/business` answers 409 `organization-required` for a session with no
  Organization ID, rather than the 401 `funding` returns for the same shape: the
  client is logged in, and 401 sends them to fix something that is not broken.
