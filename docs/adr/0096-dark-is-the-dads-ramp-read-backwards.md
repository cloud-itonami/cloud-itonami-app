# ADR-0096: dark is the DADS ramp read backwards, not a fourth palette

**Status**: accepted · 2026-09-10 · owner instruction「dads をダーク対応して」

## Context

The workspace had three appearances — `light`, `8bit`, `grok` — and no dark
one. The reason recorded in `appearance.cljc` was that the デジタル庁
デザインシステム ships no dark palette, which is true of upstream and is still
true of upstream.

What had gone stale was the consequence drawn from it. `jp-go-dds.dark` landed
upstream on 2026-08-04 (794ec5c) and does not need a dark palette: it mirrors
each ramp by INDEX, so `--color-primitive-red-800` in dark is the literal that
`--color-primitive-red-400` holds in light. No colour is invented. Because the
inversion happens at the PRIMITIVE layer, `--color-key-*`, `--color-semantic-*`,
the `--hig-*` bridge and every `dads-*` component follow with no rule of their
own.

So the library could do this before this repository noticed. Two things had
kept the capability out of reach, and both were invisible:

1. **This namespace's own docstring** said DADS had no dark palette and left
   the reader to conclude that a DADS-based app therefore cannot offer dark.
   The fact was right and the conclusion was wrong.
2. **`deps.edn` pinned jp-go-dds at 277e8c3 (2026-07-26)** — 74 commits behind,
   and nine days before `dark.cljc` existed. `tools.deps` picks the newest sha
   it is shown, and nothing else in this closure names the library, so the
   upstream work could not arrive until that line moved. `manifest/west.yml`
   already pinned the tip, so the checkout on disk had `dark.cljc` while the
   build could not see it. West pins are gated; this coordinate is not.

## Decision

`dark` is a fourth appearance on the existing `data-appearance` axis
(ADR-0091), and it is **the only one that carries no palette**.

- `appearance_core.kotoba` decides, as the other three do: `mode-of` gains
  `dark` and `night`; `next-of` becomes `light → dark → 8bit → grok → light`.
  `dark` sits directly after `light` because the two are one palette and its
  inversion — one press answers the question a person has at night. The two
  authored appearances keep the positions they had, so an existing press still
  lands where it did.
- `appearance/dark-css` emits a snapshot block and one scoped block, and
  nothing else. The authored appearances need a per-component rule for every
  surface they change; this one needs none.
- The scope is `:root:has(.workspace[data-appearance="dark"])`, not
  `.workspace[data-appearance="dark"]`, for two measured reasons. `body` is
  outside `.workspace` and paints the page ground, so scoping to `.workspace`
  would leave a white margin around a dark workspace with no rule wrong
  anywhere. And `jp-go-dds.tokens/a11y-css` declares `:root{color-scheme:light}`
  while the dark block carries `color-scheme:dark`; a plain `:root` here would
  tie on specificity and lose on order.
- `deps.edn` moves to 581b68c with the reason written next to it, as a floor.

`dark-chat` continues to name `grok`. That spelling predates this mode and
points at the authored chat-dark appearance, which is a different thing from
the inverted palette; both rows now sit in the case table together.

## What was measured, and one thing that was not fixed

Resolved through `jp-go-dds.dark/resolve-dark` against the shipped vendor:

    --color-neutral-white            #1a1a1a   (surfaces)
    --color-neutral-solid-gray-900   #f2f2f2   (mirrored to the lightest step)
    --color-key-800                  #7096f8   (blue-400 — readable on dark)
    --color-semantic-error-1         #ff7171
    --color-semantic-success-1       #259d63   (green-600 is its ramp's midpoint
                                                and mirrors to itself)

The page renders `data-appearance="dark"` and differs from the light render by
**six bytes** — which is the claim ADR-0091 makes about a mode, now measured
for this one too.

**The ground and the raised surfaces land on the same value.** `base-css`
paints `body` with `--color-neutral-solid-gray-50`, which inverts to `#1a1a1a`;
`--color-neutral-white`, which every card, rail and topbar uses, is also
`#1a1a1a`. There is no darker step to move the ground to — `jp-go-dds.dark`
deliberately keeps pure black out, so the darkest grey is the floor and the
surfaces already stand on it.

This is flat rather than broken: cards and the sidebar carry
`1px solid var(--color-neutral-solid-gray-200)`, which inverts to `#4d4d4d`, so
every edge is still drawn and separation is carried by borders instead of by a
fill difference. An override was written here first and it set `body` to
`var(--dds-light-neutral-solid-gray-900)` — which is `#1a1a1a`, the value it
already had. It changed nothing while reading as though it had fixed the
collision, and it was removed. Lifting the surfaces instead means
per-component rules, which is exactly what this layer does not have, so it is
left to be asked for rather than made here.

## Consequences

- The KIR artifact was regenerated (`clojure -M:test:gen`). Only
  `appearance-core.kir.edn` changed; the other 23 are byte-identical.
- `dark-css-only-references-design-system-tokens-that-exist` guards the new
  string, because `web/app-css` is a load-time def and cannot contain a value
  derived from the vendored css at request time — the existing guard never
  reads it. The guard was shown to fail for the reason it names by pointing one
  declaration at a token nothing defines.
- The appearance tests fixed the three-mode cycle and had to be updated; they
  produced fourteen failures naming exactly the changed behaviour first, which
  is what says they were guarding it.
- `docs/adr/0091` is not superseded. This extends its axis; it does not
  replace its decision.
