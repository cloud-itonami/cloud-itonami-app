# ADR-0093: A credential is a card, not a message

**Status**: accepted · 2026-09-09 · owner instruction「cloud-itonami-app の uiux で、grok bots を参考に 安全に secret を保存する仕組みを設計統合して」

## Context

Measured on 2026-09-09, in this application's own Bot workspace:

1. A Bot was asked to deploy `kotoba-lang.org`. It had no Cloudflare API
   token, so it said so in prose.
2. The person pasted the token into the conversation.
3. The Bot answered `deploy は開始していません。提供された token がプロセスの
   環境変数に載っていません。` — and it was right. `cloudflare/api-token` read
   `CLOUDFLARE_API_TOKEN` from the process environment and nowhere else, and a
   desktop application started by a double-click has no exported environment.
   There was no path by which a value typed into a chat window could become
   one.
4. The token was now in the transcript. The transcript is what the next turn
   sends to a model.

Every step of that is a consequence of one missing thing, and it is not the
Cloudflare integration. This application already knows how to hold a
credential a Bot must not see: an OAuth token is obtained by an authorization
flow, kept in the macOS Keychain, and resolved by `identity` at the moment of
the call, so the model never holds it. What it had no answer for was a
credential a **person types** — one with no flow to run.

The failure mode was worse than "unsupported". A Bot that needs something it
cannot obtain will say so, in prose, because prose is all it has; a person who
reads a request will answer it, in the only field on the screen. **Asking in
prose for a value that cannot be received in prose is a design that produces
leaked credentials on purpose.**

### What "grok bots を参考に" names

`network-awai/local-murakumo`'s AWAI Grok Bots clean-room
(`docs/grok-bots-clean-room.md`, ADR-2608300200) states the property directly:
its capability sandbox has **no shell, filesystem, arbitrary evaluation, or
secret forwarding**. A bot there reaches an external service through a governed
tool. It never holds the service's key.

That is the boundary adopted here. It is not a new idea in this repository —
it is what the OAuth path already does — and the decision below is to make it
reach the one class of credential that had been left outside it.

## Decision

1. **A Bot names a credential; it never receives one.**
   `cloud.itonami.app.secret-request/catalogue` is a **closed** set of the
   credentials this build can be handed. A card can only be offered for an
   entry written there. The closure is the security property: a Bot that could
   invent a request could ask for a bank password, and a field rendered by this
   application looks trustworthy no matter who asked for it. The worst a
   prompt-injected model can do is ask for one of the credentials this build
   already needs.

2. **The card carries the request and cannot carry the value.**
   `bot/secret-card` holds a catalogue id, a state, and — after a successful
   store — a `keychain://service/account` locator. It **refuses** a map
   containing `:value`, `:token`, `:password` or `:credential` rather than
   dropping those keys, because a caller that passed one has misunderstood
   which side of the boundary it is on and a silent drop would leave it
   believing the credential was recorded. This is the refusal
   `cloud-itonami/kagi` already makes as `400 plaintext-value-received`.

3. **The value enters by one door, and that door is not the message path.**
   `POST /api/bots/:id/cards/:card-id/secret` → `bots/provide-secret!` →
   `secret-store/put!`. The value is an argument and is gone when the call
   returns. What is written into the conversation is a state and a locator.
   The route is deliberately separate from `/answer` and `/decide`, which take
   values that belong in a transcript: sharing a handler would make the
   difference between "record this" and "never record this" a branch, and a
   branch is something a later edit falls through.

4. **A person supplies it, not an agent session.** `decide!` admits an agent
   session under an owner's standing delegation, because approving a proposed
   write is delegable. Supplying a credential is not: no delegation produces a
   value the owner never typed, and an agent session at this endpoint is either
   holding a credential it should not hold or answering a question that was
   asked of a person.

5. **The host half is `.cljc`, and its non-JVM branch refuses.** `security` is
   a macOS binary and there is no consumer of this namespace off the JVM, so
   the `:cljs` branch throws `:secret/unsupported-host` rather than answering
   `nil`. A `nil` there would be indistinguishable from "this machine has no
   such item" — a check that could not run returning what a check that ran and
   found nothing returns (ADR-2608136000).

6. **Resolution is environment, then the macOS Keychain.** The environment
   stays first — an operator who exported the variable is being explicit, and a
   value typed months ago must not silently outrank what the process was
   started with. `cloudflare/api-token` and `cloudflare/account-id` now read
   that ladder, which is the change that makes the measured failure go away.

   **kagi is not on the read path.** It is the workspace's vault, and reading
   it here would still be wrong: `kagi get` can prompt, and this resolution
   runs at the moment of a tool call. `mail-age-key` reaches for it once, when
   a message is filed, and even there it is last. This mechanism writes to the
   Keychain, so it reads from the Keychain, and the two halves cannot disagree
   about where a value is.

7. **The tools are offered even when the credential is missing.**
   `domain-tool-definitions` gates on `domain-tools/answerable?` — an agent
   session and an enabled domain authority — instead of `available?`. Under the
   old gate a deployment without a token offered **no** domain tools, so the
   model never reached for one, so nothing ever said what was missing. The
   call is stopped instead by `secret-blocks`, beside the existing `:blocked`
   map that stops a call on a missing connector, and for the same reason and at
   the same moment: the Bot has reached for the tool, so the question is now
   necessary. The other half of `available?` is unchanged — no session, or a
   disabled authority, still yields no tools, because no field in a
   conversation resolves those and a field is a promise that filling it in will
   help.

8. **A credential pasted into the composer is refused before anything is
   written.** While a request is open, a message whose whole trimmed text
   matches that requirement's shape is rejected: no direction increment, no
   message appended, no context stored. Only while a card is open, and only
   against the shape that card asked for — this is not a scanner over the
   conversation.

   The trade is stated rather than hidden. A 40-character lowercase hex string
   is a git revision and also matches the Cloudflare shape, so pasting a sha
   while that card is open is refused as if it were the token. A refused
   message is retyped; a credential in a transcript is sent to a model. The
   guard is anchored, so a value surrounded by prose gets through — the field
   is the prevention, and this is containment for the common case, which is a
   bare paste.

9. **The card reports the keychain, not what it said when it was written.**
   `public-card` recomputes a secret card's state from `secret-store/source`,
   the same treatment a connection card's 接続済み gets and for the same
   reason: the item can appear or disappear without this application doing
   anything. A card that reports `environment` says so, because re-entering a
   value changes nothing while the variable is set. A **declined** card is left
   alone — that is a fact about what the person said, and recomputing it would
   erase an answer.

10. **A coordinate is not a credential, and is not treated as one.**
   `cloudflare-account-id` is in the same catalogue, because a desktop app
   cannot be given an environment variable whether the value is secret or not,
   so it needs the same affordance. It carries `:secret/concealed? false`: the
   field is not masked and the composer guard does not apply, because typing
   your own account id into a conversation is not a mistake. The default is
   `true`, so an entry added without thinking about it is masked and guarded.

## Consequences

- The measured failure is gone: a person can supply a Cloudflare token from
  the conversation, it is kept in the Keychain, and the deploy path finds it.
- One card at a time. Two coordinates are missing on a fresh install; the
  second is offered on the next attempt. Asking for two at once asks somebody
  to go and find two things before either does anything.
- **The value is on `security`'s argument list.** `add-generic-password` has no
  stdin form — `-w` with no argument prompts a terminal this application does
  not have — so for the duration of one short-lived subprocess the value is
  visible in `ps` to other processes of the same user. That is the exposure
  `identity/keychain-put!` and `mail-account/keychain-put!` already carry; it
  is written down here rather than left to be rediscovered, and it is not a
  reason to keep the credential in a transcript instead. A transcript is
  durable, is read by a model, and is read again by every later turn.
- **The status vocabulary was not widened.** The decision core has no code for
  "waiting for a credential", and adding one is `bot_core.kotoba` plus a
  regenerated KIR artifact plus the parity gate. Instead the host applies the
  refinement it already applies for a blocked continuation: a Bot the core
  calls `idle` while a field is unanswered is reported as `blocked`. A `status`
  written in two places would be a second implementation of a decision the core
  owns.
- **MCP is unchanged.** `mcp/tools` still gates on `available?`. There is no
  card surface on that transport, so offering a tool that cannot run would be
  an error with nowhere to answer it.

## What was rejected

- **Storing it in the conversation and redacting on the way to the model.**
  Redaction is a filter, and a filter is a list of the cases somebody thought
  of. The value would still be in the durable store, in an export, and in a
  backup.
- **A settings screen instead of a card.** It works and nobody finds it: the
  moment a person is willing to go and get a token is the moment a Bot has just
  told them it is stuck. Both exist in the end — the card is where the request
  is answered, the keychain item is where it lives.
- **Letting the Bot ask for the value in prose and parsing it out.** That is
  the measured failure with a parser added to it.

## Evidence

- `secret_request_test.clj` — the catalogue is closed; `public` has no field a
  value could occupy; the card constructor refuses a value and does **not**
  refuse an ordinary unexpected key; admission is exercised at 39, 40 and 41
  characters so that flipping the shape's quantifier cannot leave the suite
  green; the composer guard is asserted in both directions and on a coordinate.
- `secret_store_test.clj` — reads use `-w` and never `-g` (ADR-2607152322: `-g`
  prints the password into an inherited stderr); items are named in full and
  there is no listing form; a refused value never reaches a subprocess at all;
  a write failure throws rather than being assumed.
- `secret_card_test.clj` — the missing credential becomes a card rather than a
  sentence; the Bot's own sentence does not ask for the value; an agent session
  is refused; the token appears nowhere in the stored conversation. That last
  assertion rests on `transcript-mentions?`, which is asserted **true** against
  a deliberately leaked message first, so the negative assertions are known to
  be capable of failing.
- Discrimination, measured 2026-09-09: disabling the composer guard turns 3
  assertions red; disabling `secret-blocks` turns 4 tests red, and they fail at
  `cloudflare.cljc:24` — `Cloudflare API token is not configured`, which is the
  behaviour this ADR replaces.
