# ADR-0036: A Bot's computer is this machine's isolated browser

**Status:** accepted — 2026-08-13

## Context

ADR-0034 gave a Bot a face, a standing brief, a connector grant, and two
permissions: `:bot/writes?` and `:bot/browser?`. The second was recorded and
not dispatched. A Bot turn called `connector.invoke/call` and nothing else.
`agent-control` already had per-principal browser profiles (`session-for`) so
two Bots would not share cookies, and still nothing in a Bot turn bound them.

xAI's Grok Bot (https://x.ai/bot, 2026-08-11) is the product this is compared
against. Its teammates share one persistent cloud PC: they sign into sites
there, keep the desktop running while the person sleeps, and come back for
approval on sensitive steps. That shape is the thing this application refuses.
Anything leaving this machine has to be named and reviewed before it can
happen. A per-Bot VM inverts it — the work would happen somewhere the
fail-closed policy does not reach, so the review would cover the chat window
and nothing the Bot actually did.

The remaining Grok-Bot-shaped gap that fits the thesis is therefore not
always-on cloud compute. It is: a Bot that asked for the browser, on a machine
that has enabled it, can drive an isolated browser of its own, with writes held
for a person.

## Decision

`:bot/browser?` opts a Bot into **this machine's isolated browser**, not into
computer-use on the frontmost app, and not into a cloud VM.

| Grok Bot | this application |
|---|---|
| Shared persistent cloud PC | This machine. Asleep means stopped. |
| Sign into apps / sites with no API | Connectors, plus one isolated browser profile per Bot |
| Finish e2e, return for approval | Reads run, writes hold. Passkey / human session. |
| Scheduled routines | Already: traces → routine → `fire-due!` on live sessions |
| Multi-bot / handoff | Already: `hand-off!`. Grants do not cross. |
| All bots share cookies on one VM | Same-owner Bots share this machine's computer (ADR-0042). Different owners stay apart. Not a cloud VM. |

Dispatch:

- Isolated-browser tools join a Bot turn iff `:bot/browser?` **and**
  `agent-control/browser-enabled?`. They are not written into `:bot/tools`
  (that set is connector names; mixing them would make `grant-widens?` fire on
  every ordinary browser Bot).
- `browser_snapshot` is a read and runs. `browser_open`, `browser_click`,
  `browser_type`, `browser_press`, `browser_scroll` are writes and hold for
  approval, the same way a Gmail send holds.
- `call-browser-tool!` binds `*browser-session*` to `(computer-for owner)`
  and `*browser-screen*` to `(screen-for bot-id)` so same-owner Bots share
  cookies and keep separate screens (ADR-0042 superseded the per-Bot cookie
  jar).
- Computer-use tools (`computer_*`) stay off this path. A Bot that asked for
  the browser did not ask to type into the frontmost app.
- If the Bot asked for the browser and this machine has it off: the field
  stays, the tools do not grow, and the screen reports `:browser-ready?` /
  `:browser-available?` rather than silently widening.

The Bot cannot approve. An agent session cannot approve. That refusal is
unchanged.

## Consequences

- A Grok-Bot-shaped teammate that needs a site with no connector can now reach
  it, on this machine, behind the same approval card the connector writes use.
- Closing a laptop still stops every Bot. That is the cost of the thesis, not a
  defect to patch with a cloud VM.
- Parallel turns that outlive a laptop sleep, and watch-and-learn from DOM
  coordinates, remain open. Same-owner cookie sharing and per-Bot memory are
  ADR-0042.
