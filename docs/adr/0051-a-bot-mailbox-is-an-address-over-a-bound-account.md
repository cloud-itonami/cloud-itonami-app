# ADR-0051: A Bot mailbox is an address over a bound account

**Status:** accepted — 2026-08-15

## Context

Bots were durable identities and could call Gmail tools, while Mail already
merged Gmail, Microsoft, IMAP and POP3 accounts into one local mailbox. The two
surfaces did not meet. A Bot had no RFC email address, inbound mail could not be
selected for one Bot, and sending as a Bot would have required a caller to
invent a `From` address.

The hosted webhook Worker already received provider change signals but kept no
message bodies. `itonami.cloud` already publishes Cloudflare Email Routing MX
records, and its Resend account is the deployment-owned outbound authority.

## Decision

Every Bot receives one immutable address when it is created:

```text
<bot-id>@mail.itonami.cloud
```

The local part comes from the immutable Bot ID, never its editable display
name. Pre-existing records without `:bot/email` derive the same address when
read, so migration does not rewrite the Bot store.

The address is registered at the authenticated `itonami-cloud-webhooks` relay
with the Bot ID, organization and exactly one bound destination mailbox.
Cloudflare Email Routing accepts only registered addresses and forwards the
original RFC message to that account. The Worker stores only a change signal;
the existing local provider sync retrieves and persists the body. The Bot
mailbox projection selects messages whose original `To` header contains the Bot
address, so it does not expose the owner's whole unified inbox.

Outbound mail crosses the same relay and uses Resend. The relay accepts a send
only when the registered address, Bot ID and organization all match. A client
cannot choose an arbitrary `From`. Locally, a disabled Bot or a Bot without
`:bot/writes?` is refused before the request leaves the machine, and each
accepted Resend receipt is persisted under `[:bot-mail :sent <bot-id>]`.

Provisioning requires one unambiguous owned mail account. An explicit Bot
account binding narrows the candidates; an empty binding inherits only when the
owner has exactly one mailbox. Zero or multiple candidates fail closed and the
UI asks the owner to connect/select one.

HTTP surface:

```text
GET  /api/bots/{id}/mailbox
POST /api/bots/{id}/mailbox/provision
POST /api/bots/{id}/mailbox/send
```

All three routes use the existing human-session ownership boundary. Writes also
require same-origin and CSRF checks.

## Consequences

- Renaming a Bot never breaks its address or thread history.
- An inbound message is durable in the already-connected provider and local
  mail store, not duplicated as plaintext in Cloudflare KV.
- A hosted relay outage can leave a newly created Bot pending provisioning, but
  cannot roll back the durable Bot. Retrying `mailbox/provision` is idempotent.
- Email remains a message, not an instruction or approval. A later automated
  mail-processing loop must pass the existing Bot tool and approval gates; this
  change does not feed inbound bodies directly to a model.
- The deployment needs the Worker `RESEND_API_KEY` secret and a Cloudflare Email
  Routing API token. Provisioning creates one literal Worker rule per Bot;
  Cloudflare does not permit a catch-all rule to target a Worker, and the
  existing catch-all therefore remains `drop` for every unregistered address.
  `mail.itonami.cloud` is used rather than the apex because it is the
  Resend-verified sending domain and also publishes Cloudflare Email Routing MX.

## Verification

- `itonami-cloud-webhooks`: TypeScript check passes.
- `bots-test`: 39 tests / 145 assertions, zero failures and errors, including
  stable addresses, recipient isolation, bound-account provisioning and the
  write-authority refusal. The generated command registry was regenerated from
  the three new routes and its route-count consistency test passes.
- Production Worker deployment `34823e6e-ef60-4601-bb87-f510d5fb9aa1` exposes
  the final `bot-mail` contract, including per-Bot routing-rule creation.
- A literal Bot rule was created through the production provision endpoint.
  Gmail then sent two messages to the address; Cloudflare EmailEvent completed
  `Ok` and two `bot-mail` change records remained readable from relay KV until
  explicitly acknowledged.
- A registered Bot sent through production Resend to Gmail. Resend id
  `fc3ac8af-2184-448b-a4f4-b954be343d22` arrived in Gmail Inbox, unread, with
  the registered Bot address as `From`.
- Verification-only Bot rules, KV registrations and two relay events were
  removed after measurement. The pre-existing `test@mail.itonami.cloud` rule
  was not changed.

## Session closure

Closed 2026-08-15 with the implementation merged to
`cloud-itonami/cloud-itonami-app` main at
`70f59af1a4a463963ec5f295ede5e3dcb470cef2`. The superproject advanced the
`cloud-itonami-app` west pin to that commit in
`c140f34fcf592d2c02d6badf63caae7a0d3ba5fa` after server-side default-branch
reachability and forward-only checks (`ahead=2`, `behind=0`).

The production observations above establish the two directions separately:
Cloudflare accepted inbound mail and retained its durable signal until ack;
Resend accepted outbound mail and Gmail received it. They do not claim that a
self-addressed Bot-to-Bot message is a distinct third path: Resend suppressed a
repeat after the verification recipient had bounced. No production routing
rule or KV registration created solely for verification remains.
