# ADR-0005: Settle a payable through the authority spine, on a balance nobody guessed

## Status

Accepted.

## Context

The organization has a payable it has already failed to pay once. Its tax
advisor's monthly advisory fee is collected by direct debit from a PayPay Bank
current account; the June 2026 collection **failed for insufficient funds**, was
rolled into July, and on 2026-07-30 the advisor sent an invoice for the arrears
and asked for a bank transfer instead.

That failure is the design input. The thing that went wrong was not a missing
approval step — nobody was asked to approve an overdraft — it was that **nobody
knew the balance would not cover it until the bank said so.** Any design that
adds consent without adding a funds check would have let exactly this happen
again, with a signature on it.

The app already has the machinery for the consent half. `cloud.itonami.app.authority`
is a four-stage spine — deterministic pre-check, Passkey-bound consent, content
digest, hand-off to a governed actor — carrying eSIM, card and voice. The card
adapter's daily-limit gate is precisely the shape needed here: an arithmetic
refusal evaluated **before** a human is asked, so a request that cannot proceed
never becomes an approval prompt.

What was missing was anything for the money to be checked against. The app has
no bank connector and no account model at all.

## Decision

### 1. Funding accounts belong to the organization, and hold no instrument

`cloud.itonami.app.funding` records bank accounts against a **Tenant**, not a
User: a company's account outlives whichever member linked it, and user-scoping
would mean an owner change silently orphans the funding for every payable.
`:linked-by` records who did it without making them the owner.

An account **number is never stored.** It may be supplied once, and what is kept
is the last four digits and a SHA-256 digest. The digest answers the only
question consent needs — "the account you approved is the account on file" — and
cannot move money. The same reduction is applied to the payee's account inside
the pre-check, so a *proposal* — which is persisted to `state.edn` — never
carries a transferable account number either.

### 2. A balance is attested, dated by the bank, and never inferred

There is no bank connector and this ADR does not add one. A balance arrives
because a human read it and recorded it, with:

- `:amount-minor`, an integer in the currency's **minor unit**, with the exponent
  looked up from an allowlist. An unknown currency refuses rather than
  defaulting, because guessing an exponent is how a figure becomes wrong by 100×.
- `:as-of`, **the instant the bank stated** — required, and deliberately not
  defaulted to now. A figure copied from a three-day-old statement is three days
  old however recently it was typed in.
- `:source`, one of `:owner-attested` / `:statement` / `:api`.

`funding/freshness` is pure and returns `:never-recorded`, `:fresh` or `:stale`.
An unparseable, missing or **future** `:as-of` is `:stale`. That is the opposite
literal value from `authority.posture`'s fail-closed direction and the same
principle: resolve the unknown toward refusing. There, a timestamp gates a
restriction; here it gates a permission.

**An unknown balance is not zero and it is not unlimited.** It is unknown, and
it refuses. `account-services` already states this for usage meters ("an
unavailable meter is never presented as zero usage"); the stakes are higher for
money, because a balance defaulted large waves a payment through and one
defaulted to zero refuses every payment forever.

### 3. `:payment` is a fourth authority on the existing spine

`cloud.itonami.app.authority.payment` adds one op, `:payment/settle`, binding a
distinct Passkey context type. Its pre-check is pure — every fact is passed in —
and refuses in this order:

| refusal | why it is before the next one |
|---|---|
| `:payment/op-unsupported` | allowlist, never a default |
| `:payment/posture-unknown` / `:payment/spend-hold` | the cross-domain hold, below |
| `:payment/payee-missing`, `:payment/reference-missing`, `:payment/amount-invalid` | shape |
| `:payment/account-not-linked`, `:payment/account-inactive`, `:payment/currency-mismatch` | the source of funds |
| `:payment/settlement-history-unknown`, `:payment/duplicate-settlement` | telling someone they are short of funds for an invoice they already paid sends them to the wrong problem |
| `:payment/balance-unknown` | a stale or absent figure is not an answer |
| `:payment/insufficient-funds` | **the June failure, encoded as a gate** |

Deduplication is keyed on the invoice reference and is **organization-scoped
and `:committed`-only**. An invoice is owed by the company, so a colleague
settling it a second time is exactly the duplicate the check is for; and a
proposal that was rejected or that a governor refused has settled nothing, so
counting it would strand the payable.

### 4. A SIM swap holds a bank transfer, not just card spend

ADR-2607300300 D4 restricts card spend and issuance for seven days after an eSIM
ownership transfer for the same subject: whoever moved the line has the second
factor, and the classic sequence is *move the line, then spend*. That sequence
does not care whether the spending happens on a card or by bank transfer.
`:payment/settle` therefore inherits the hold. Excluding it would have left the
invariant with a door next to it.

This is expressible only because all four authorities' proposals share one store
partition — the local analogue of `kotobase.core/open` taking exactly one
`:ref-name`. Splitting them for write throughput would buy throughput and lose
the only invariant that justified integrating.

### 5. The facts are the server's, never the caller's

`authority.api` computes the posture, the funding account, the balance, its
freshness and the settlement history from the store and **overwrites** whatever
arrived in the request. This is the enforcement point; the adapter's
required-input checks only stop a fact being forgotten. Without the overwrite a
client would send `{:balance {:amount-minor 99999999}}` and buy itself past the
funds gate, and the whole gate would be a suggestion.

`:funding-account-id` is the one thing taken from the request, because naming
the account money comes out of is the caller's decision — but the account is
*looked up*, so an id belonging to another organization resolves to nil and
refuses. Defaulting to "the organization's only active account" was rejected: an
implicit funding source is the wrong thing to be convenient about.

## Consequences

### What a committed proposal means

**A governed settlement record. Not a transfer.** This app holds no banking
credential, stores no account number, and has no path to a payment network. A
human makes the transfer in their bank; this records what was owed, from which
account, approved by whom, bound to a Passkey assertion over a digest of the
exact terms. Like every other authority in this fleet the settlement actor is
propose-only, so `:committed` means *a governed proposal was recorded*. A UI
that renders it as "paid" is lying.

### The funds gate is evaluated at review time only

The balance is deliberately **not** in the digest material. Binding it would
invalidate a legitimate approval every time an unrelated deposit landed. The
cost is stated rather than hidden: a balance can fall between review and commit
and this adapter will not notice. `:balance-at-review` is recorded on the
proposal so a reader can see what it was judged on, and the authority's own
governor is the gate expected to see a later shortfall.

### `:payment` ships disabled

Like every authority in `defaults.edn`, and with `:endpoint nil`. A fresh
install has no settlement surface. Enabling it is a deployment decision.

### Error statuses

The spine's own refusals were previously unmapped and answered `502` — a
disabled authority read as "this server is broken" rather than "this surface is
deliberately off". `:authority/*`, `:funding/*` and `:payment/*` are now mapped;
notably `:payment/insufficient-funds` is `402`, which is what `402` is for.

### 6. The agent surface resolves a session; it does not reach around one

`cloud.itonami.app.mcp` states why mail, calendar, drive and chat are not on the
MCP surface: they sit behind the Passkey session on `/api/*`, and reaching around
that from a surface with no session would weaken a gate the app means. Money is
a stronger version of that objection, not a weaker one.

So `cloud.itonami.app.payment-tools` resolves a real session — an app session
token from `CLOUD_ITONAMI_MCP_SESSION` or the login Keychain, required to be
live, unrevoked, and belonging to a Passkey-enrolled user — and acts as it. With
no such token the funding and payment tools are **absent from the manifest**,
not merely certain to fail. Publishing a tool that will always refuse invites a
client to try and says nothing about why.

The token is memoized for the process; the **session is not**. `identity/session`
re-resolves it on every call, so expiry and revocation take effect immediately.

**No tool can approve.** `approve/start` and `approve/finish` have no descriptor
and no dispatch branch. This is structural rather than policy: consent is a
WebAuthn user-verifying assertion from an authenticator the operator holds, and
there is no assertion an agent could produce. `payment_commit` is exposed because
it acts on a proposal whose exact digest a human already signed — carrying an
approved thing to the authority is errand-running, not deciding — and the spine
refuses anything not in `:approved`, so it cannot step over the consent stage.

Verified end to end over real stdio JSON-RPC, not only in unit tests: an
unaffordable review refuses with `payment/insufficient-funds`, an affordable one
returns `awaiting-passkey` with a digest, and committing an unapproved proposal
refuses with `authority/proposal-not-found`.

## Alternatives considered

**Fetch the balance from the bank.** No API is available to this app, and
acquiring one would mean holding a credential that could also move money —
which would make every other refusal here decorative. Rejected on those grounds,
not on effort.

**Default an absent balance to zero.** Refuses every payment forever and reads
as a bug rather than as missing data, so the pressure would be to remove the
check. `:never-recorded` and `:stale` refuse *and say why*.

**Deduplicate per user.** Would pass for the one case deduplication exists to
catch — two members paying the same invoice.

**Skip the reference requirement.** Without one, a settlement can be recorded
twice and neither record is wrong. The reference is what makes the ledger
answerable.
