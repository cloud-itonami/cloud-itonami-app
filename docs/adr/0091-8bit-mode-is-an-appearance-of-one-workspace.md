# ADR-0091: 8-bit mode is an appearance of the one workspace

**Status**: accepted · 2026-09-02 · owner instruction「cloud-itonami app の 8bit mode を設計デザイン, app に統合」

## Context

itonami.cloud's public cockpit already has an **8-BIT MODE**: a CSS-only,
top-down pixel office where each room is a business function, each sprite a
Bot, and each signal a state (`cloud_itonami.site.home/bots-workspace`).
People liked it enough that the owner asked for the same mode *inside* the
workspace app — the loopback Bots/Chat/Drive surface this repository serves.

The workspace had no appearance system at all: it inlines `jp_go_dds/dds.css`
and hand-writes its rules in `web.clj`. The cockpit, by contrast, carries a
`data-appearance` attribute on `<html>` (kotoba-ui's `appearance-attr`) — but
its dark blocks are stripped at build time, so that attribute has one value
in production. Neither surface could switch.

Two ways were open: a second document (a "retro" page with its own bundle),
or one attribute plus one stylesheet layer. The single-page rule
(ADR-2608080100) settles it: **a mode is state, not a location**, and a second
document is exactly how the DAW/NLE apps ended up shipping two shells with
one of them unstyled.

## Decision

1. **One attribute is the whole mode.** `.workspace[data-appearance="8bit"]`.
   The server renders the configured default (`[:ui :appearance]`,
   `light` when absent); `interaction.js` applies, in order, `?appearance=`
   from the URL, the device's remembered choice (`localStorage`
   `cloud-itonami-appearance`), then the server value. The topbar toggle
   flips it. Nothing else in the page changes — same ids, same ARIA, same
   tab order, same reading order. A screen reader hears the same document.
2. **One stylesheet layer,** `cloud.itonami.app.appearance/css`, concatenated
   last into `web/app-css` so that its overrides win and the existing token
   guard covers it (every `--eightbit-*` it references it also declares).
   Every rule is scoped under the attribute; the light workspace is
   byte-for-byte what it was.
3. **The palette is the cockpit floor's palette** — sixteen named colours,
   the same hex the public floor draws with, so a Bot looks like the same Bot
   on itonami.cloud and in the workspace. Sixteen is the system; an 8-bit
   palette that keeps growing stops being one.
4. **Design rules** (in the order the eye meets them):
   - *grid*: 4px unit; spacing 4/8/12/16/24.
   - *shape*: radius 0 everywhere; 3px ink borders; hard 4px offset shadows;
     pressing a button moves it 2px into its shadow.
   - *type*: pixel stack (DotGothic16 / Press Start 2P / Silkscreen when
     installed, else the system monospace), 13px/1.6, +.02em tracking,
     uppercase eyebrows and chips. **No network font** — the page's
     zero-external-request default stays true.
   - *colour*: night ink on cream paper; indigo chrome; the sun for the one
     primary action; leaf / sky / orange / red for the four states. DADS
     semantics of state (success/error) are kept; only their pigment changes.
   - *motion*: `steps()` timing only; `prefers-reduced-motion` turns it off.
   - *pixels*: `image-rendering: pixelated` on every raster.
   - *the floor*: the Bots rail becomes the office — grass tile, each Bot a
     sprite with a hard shadow, its status as the sprite's colour (working =
     leaf and bobbing, waiting-approval = pink, waiting-connection = orange).
   - *CRT*: a 6 % ink scanline at 4px pitch on the main surface only, which is
     below the threshold that changes measured contrast on cream.
5. **What 8-bit never does**: hide an element, add a control, change a route,
   or fetch anything. The design is shape and colour.

## Consequences

- `cloud.itonami.app.appearance` is `.cljc` and the portable suite executes
  it; its tests assert the scoping, the token closure, the absence of hidden
  elements and network fonts, and that the palette is the cockpit's.
- Settings persistence is client-side on purpose: `server.clj`'s dispatch
  method is at the JVM's 64 KB ceiling, and a preference that a device can
  hold does not need a route.
- The cockpit's own 8-BIT MODE keeps its markup; a later change may move
  its floor CSS onto the same `--eightbit-*` tokens so both surfaces share
  one source. That is a refactor, not a second design.
- A dark appearance is *not* implied. DADS ships no dark palette
  (2026-07-26 owner decision) and this ADR adds none.
