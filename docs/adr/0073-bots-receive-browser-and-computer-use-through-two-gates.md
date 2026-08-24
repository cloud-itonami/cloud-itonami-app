# ADR-0073: Bots receive browser and Computer Use through two gates

Status: accepted (2026-08-24)

## Context

Cloud Itonami already had two useful but disconnected capabilities:

- `agent-browser` could run a headed browser in a named, isolated profile;
- the ADR-0059 macOS helper could read an accessibility tree, capture a named
  application, and perform digest-bound accessibility actions without moving
  the pointer or taking keyboard focus.

`agent-control` could use both, but an ordinary Bot could use only the browser.
Settings did not expose the machine gate, the packaged checkout did not prepare
the helper, and a person could not see why either capability was unavailable.
That made a screenshot in a conversation evidence of one manually driven run,
not evidence that a Bot could reliably perform the same work.

## Decision

Admission is the intersection of two explicit grants:

1. the machine Settings enable the browser or Computer Use; and
2. the selected Bot carries `:bot/browser?` or `:bot/computer?`.

The grants are independent. Creating a Bot never infers Computer Use from
`writes?`, `omakase?`, browser availability, or a macOS permission that happens
to be present. Existing Bots therefore remain computer-disabled.

The browser uses `agent-browser` and `AGENT_BROWSER_SESSION` derived from the
immutable Bot id. Allowed domains remain a machine-level allowlist. LaunchAgent
PATH drift is handled by resolving the explicit override, PATH, Homebrew ARM,
and Homebrew Intel locations in that order.

Computer Use exposes only:

- reads: `computer_tree`, `computer_menu`, `computer_screenshot`;
- digest-bound writes: `computer_press`, `computer_menu_press`,
  `computer_set_value`, `computer_scroll`.

It does not expose coordinate click, synthetic key, or free-form typing. Reads
run immediately. Writes go through the same Bot approval card as connector,
workspace, and browser writes; an omakase Bot may decide the card but cannot
widen either grant. `computer_tree` supplies the digest and each write refuses
when the target tree changed. Passwords, 2FA, CAPTCHAs, payments, and security
prompts remain human work.

Settings owns diagnosis and preparation. Production prefers the signed and
notarized `CuaDriver.app` daemon (`com.trycua.driver`) because macOS TCC grants
belong to the responsible application identity. Invoking the source-built Swift
helper from launchd made Java the responsible process, so a grant observed from
Terminal did not prove the resident could use it. The Swift helper remains a
reviewable fallback, not the packaged production identity.

Passive diagnosis calls `check_permissions` with `prompt:false`; it never opens
a system dialog. Only the signed-in person's explicit preparation action may
run `cua-driver permissions grant` (or the fallback helper's explicit prompt).
The Cua adapter admits only AX-tree reads, exact menu paths, and element-token
actions. It does not forward CuaDriver's coordinate, key, typing, clipboard,
launch, kill, or foreground-escalation tools. Before each element write it
obtains a new snapshot, recomputes the same tree digest, refuses drift, and then
uses the fresh opaque element token from that snapshot.

## Acceptance and score

Hard gates (all required):

| Gate | Evidence at acceptance | Result |
| --- | --- | --- |
| machine plus Bot admission | focused Bot tests | PASS |
| no coordinate/key/type tools | tool-contract tests | PASS |
| read immediate, write classified for hold | Bot loop tests | PASS |
| browser profiles isolated | two live sessions on different origins | PASS |
| signed host and macOS permissions | CuaDriver daemon attributed to `com.trycua.driver`; Accessibility + Screen Recording true | PASS |
| focus-free, token-only desktop behavior | 15 live tests / 108 assertions, including no-coordinate adapter contract | PASS |
| UI and command registry do not drift | core and route-registry tests | PASS |

Feature readiness is **93/100**:

- execution wiring 25/25;
- authority and stale-screen safety 25/25;
- live browser/helper connection 20/20;
- settings and per-Bot UX 15/15;
- model-output evidence 8/15.

The missing seven points are deliberate. The repository-wide Bot output gate is
still red: its checked receipt now has the required 20 scored tasks, but factual
grounding is 0.75 rather than the required 0.95. Infrastructure acceptance does
not turn that independent quality result green. A release may carry this bounded
capability, but must not claim Grok-level autonomous output quality until
ADR-0072's grounding gate passes on resident runs that actually use these tools.

## Consequences

- A Bot can now inspect a screenshot and act on a named application without
  sharing another Bot's browser state or interrupting the person's pointer.
- A model call made before Settings changed is refused again at execution time.
- Missing installation and missing macOS permissions have distinct remedies.
- Computer Use remains off on every existing and newly created Bot until the
  person opens that Bot's settings and enables it.
- Browser installation and the signed CuaDriver daemon are operational
  dependencies. The source-built desktop helper remains the bounded fallback.
