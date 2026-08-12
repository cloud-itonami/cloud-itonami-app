# ADR-0034: A Bot is an identity over capabilities that already existed

**Status:** accepted — 2026-08-12

## Context

This application already had every mechanism a named AI teammate needs.
`agent-control` runs a bounded loop that executes read-only tools and holds
everything else for a person. `connectors` derives, from a registry of eight
connector repositories and 37 tools, exactly which scopes a deployment asks
for — and computes the default enabled set from a recorded historical grant so
that wiring the registry in cannot widen anybody's consent. `work-governance`
says what an artificial performer is and refuses to let one acquire person
authority. `work-approval` requires a Passkey. `chronicle` keeps memory
device-local and opt-in.

What was missing was something a person could point at. A run is not a
colleague: you cannot give a run a standing brief, ask it what it did
yesterday, or notice that it is stuck. `agent-control` has a single session
name, `"cloud-itonami-agent"` — one agent, not several with names.

Two things were also missing that are not identity at all, and finding them is
the reason this is worth a decision record rather than a screen:

- **Nothing in `src/` ever called `connector.invoke/call`.** Measured
  2026-08-12: zero call sites. `connector.ports` requires the host to supply
  `IHttp` and `ITokens`, and this application supplied neither. The registry
  described tools that no code path could run — a menu with no kitchen.
- **Token resolution was per *provider*, and a provider stopped being an
  identifier some time ago.** `identity` has kept one connection row and one
  Keychain slot per external account since subjects qualified the prefix, with
  the comment that sharing one slot "was how the second Google account
  overwrote the first". `connection-for` already refuses to guess between two
  of somebody's accounts. But nothing above it offered a way to *choose*, so
  the finer structure was unreachable.

## Decision

A **Bot** is a durable record — id, tenant, owner, name, avatar, standing
brief, a tool grant, an account binding, two permissions — and nothing else.
It is an identity placed over the capabilities above, and the design work is
making sure that is all it is.

### The name is not the authority

`bot/->performer` derives a `work-governance` performer of kind `:system` with
an `:agent` actor and hands it to `governance/performer` to validate. Kind and
DoDAF types are derived, not accepted, so there is no field through which a Bot
can claim to be a person; the refusal stays with the organizational model.

`bot_core.kotoba` decides admission from four booleans — is the tool enabled by
this deployment, is it in this Bot's grant, is its connector connected, may
this Bot write — and the Bot's name, colour, glyph and brief are not among
them. Not "must not be consulted": there is no persona parameter to consult, in
the file where consulting one would matter.

### A grant narrows and is never silently repaired

`bot/admitted-tools` is the only path to what a Bot may call, and it
intersects the grant with the deployment's enabled set. A Bot naming a tool
nobody enabled is reported through `grant-widens?` and surfaced on screen
rather than pruned: "an operator turned this off" and "a tool name was written
into a Bot that was never offered" need different answers, and both need a
person to see them.

### A Bot may never approve

`bot/may-approve?` tests the actor kind first and alone. No combination of the
other facts reaches the human branch for an agent. `/api/bots` is additionally
gated by `require-human-session!` rather than `require-app-session!`, because a
boundary that holds only at the innermost check is one refactor from not
holding.

The fact the card *does* carry is named `identified`, not `user-verified`: it
is `require-passkey!` having passed, which is strictly weaker than the WebAuthn
user-verifying assertion `work-approval` requires. A Bot write that needs
assertion-level authority is a governed WorkItem, not a card in a chat window.

### Accounts, not providers

A Bot binds to **accounts** (`:bot/accounts`, connection ids), and
`bots/tokens-port` resolves through `connection-access-token!`, which is keyed
by connection id. `bot_core`'s `account-disposition` returns connect / use /
**ask**: with two accounts at one provider and no choice in effect, the Bot
asks with a lettered choice card and the answer becomes durable configuration.
It never takes the first — that is the failure `connection-for` already refuses
one layer down, and guessing here would have made that refusal pointless.

`declared` is a separate field from `bound` being zero, because "named no
accounts, so inherit the person's" and "named accounts which have all since
been disconnected" are different states. Measured: with one field the second
read as the first, and a Bot would have silently inherited the very accounts it
was configured to avoid.

Adding a second account passes `prompt=select_account`; without it the consent
screen reuses the browser's current account and the round trip returns the
connection that already exists, which looks — to the person who clicked — like
nothing happened.

### A Bot's own computer is this machine

The product this is modelled on gives each Bot a cloud VM. This application's
thesis is that anything leaving this machine has to be named before it can
happen, and a per-Bot VM inverts it: the work would happen where the
fail-closed policy does not reach, and "local-first" would become a description
of the chat window. A Bot's computer is therefore this machine entered through
a narrow door — its own grant, its own account selection, its own
conversation — and long-running work goes to the externally supervised
OrganismWorkers that already exist for it.

**The honest cost: a Bot does not run while this machine is asleep.** That is a
real difference from the product, and a consequence of the thesis rather than
an oversight.

### Two loops, not one

The Bot loop runs connector tools; `agent-control` runs this machine. They stay
separate because a Gmail send and a click on this laptop are not the same risk
and should not share an approval prompt that has to describe both. A Bot may
hold both — `:bot/browser?` opts it into `agent-control`'s surface for sites
with no API at all, which is what connectors structurally cannot cover.

## Consequences

- The connector registry is reachable for the first time. Anything that can
  supply `IHttp` and `ITokens` can now run a connector tool; `bots` is the
  first such host, and `connectors/catalog-rows` grew a `:provider` key so a
  surface can ask once for the Drive/Gmail/Calendar consent they share.
- `identity` gained `session-did`, `accounts-for`, `connection-by-id` and
  `label-connection!`. The first removes the need for callers holding only a
  session to reach into `store/snapshot`; the rest make an account nameable.
- `:bot/status` is not stored. It is computed by the core from what is
  outstanding, ordered so that waiting for a person outranks working — a Bot
  blocked on an approval while reporting itself as busy is the exact failure
  the screen exists to prevent.
- The Bots view is two panes inside the existing single-page shell, and the
  onboarding grid is derived from the registry, so it lists what this build can
  actually offer rather than a picture of an integrations page.
- `bot_core.kotoba` is the sixth shipped decision core and is native
  word-typed qualified. `kotoba-oracle-test` already gates every declared core
  against a fresh compile, so it needed no new drift test.

## The JVM suite cannot see the client, measured

`test/browser/bots_view.cljs` drives the view in a real browser. On its first
run it found two defects that 1343 passing tests did not, both the same shape —
a value crossing the JSON boundary and quietly becoming something else:

- `bot/avatar` read `:avatar/color`, and the wire sends `{color}` because JSON
  has no namespaces. Every Bot came back the default blue however it was drawn.
  The function whose entire docstring is "refusing rather than substituting"
  was substituting, and its refusal could not fire because the unknown value
  was never reached.
- `grant-widens?` compared the grant against `admitted-tools`, which is
  narrowed by whether a connector is CONNECTED as well as by what the
  deployment enables. So a brand-new Bot, before its owner had clicked
  Authorize, displayed "this Bot names tools this deployment has not enabled" —
  on every ordinary Bot. `enabled-grant` is now the separate, correct
  narrowing. A warning that fires on the ordinary case is not a warning.

Neither raises an exception; both render. The tests missed the second because
they only ever asked the question with everything connected — the fixture was
the blind spot, not the assertion.

The harness also confirmed the single-page property this application requires
(ADR-2608080100): a value set on `window` survives crossing to Chat and back,
so the view change is state and not navigation.

## What this does not do

- **No cadence.** A Bot answers when spoken to. A standing brief that runs on a
  schedule is `work-runtime`'s job and is not wired to Bots yet.
- **No Bot-scoped memory.** `chronicle` remains device-scoped and opt-in; a Bot
  does not yet accumulate preferences across conversations.
- **No parallel Bots.** Turns are synchronous, one at a time.
- **`:bot/browser?` is recorded but not yet dispatched.** The field exists and
  the two-loop boundary is decided; nothing calls `agent-control` from a Bot
  turn.
