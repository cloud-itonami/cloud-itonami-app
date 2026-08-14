# ADR-0048: A proof is re-measured, and mail is its own authority

**Status:** accepted and implemented — 2026-08-14.

## Context

ADR-0043 gave a tenant one `:domain` set by two proofs, and left two things out.
One of them was left out for a reason that turned out to be false.

**The timer.** ADR-0043 said "this application has no scheduler to hang a
periodic check on, and inventing one for this is a larger decision than this
ADR", and made `recheck!` a route. `updater`, `mail-sync`, `folder-sync` and
`work-reconciler` each run a `ScheduledExecutorService` from `server/start!`.
The claim was written from memory rather than from the source. It mattered:
a `:live` name is a claim about the present tense — `identity` publishes a
`did:web` from it — and between two owner-initiated checks a name could stop
resolving here while the tenant went on carrying it.

**Mail.** That one was scoped out for a reason that still holds: sending as a
domain needs SPF and DKIM alignment, and a TXT proof of naming establishes
neither. What was missing was the other authority, not a reason to fold it into
the first.

## Decision

### One timer, two sweeps, counted separately

`binding-sweep` runs `domain-verification/recheck-all!` and
`mail-domain-authority/recheck-all!` from one `ScheduledExecutorService`, on
`:domain-binding {:recheck? true :interval-hours 12 :initial-delay-minutes 15}`.
The interval must stay well inside `probe-freshness` (7 days) or a live name's
evidence goes stale between sweeps and the next one demotes it for want of a
measurement rather than for a failure.

The two counts are reported side by side and neither is summed into the other:
four names and no mail domains is a different fact from two of each, and one
number cannot tell them apart.

**Three properties the sweeps hold, and each is a failure this repository has
already had somewhere:**

- **An evidence floor.** `{:scanned 0}` is in the return value. A tick that
  measured nothing — empty store, or a read that threw before the loop — must
  not be reportable as a tick where everything was fine.
- **One zone's failure does not stop the rest.** A DNS timeout on one customer's
  domain is ordinary; letting it abort the sweep would freeze every other
  tenant's evidence at whatever it last said.
- **The error text survives.** `:failed` carries the message. Which of DNS, TLS
  and routing broke is written in that sentence, and a count is not something an
  operator can act on.

The scheduled task swallows `Throwable` at exactly one point, and the comment
says why: a scheduled task that throws is cancelled by the executor and never
runs again, which is the failure where a sweep looks configured and has been
dead for weeks.

### Mail authority is proven from its own three records

`mail-domain-authority` reads, for one domain and one DKIM selector:

| record | at | what it has to say |
|---|---|---|
| SPF | `<domain>` | present **and closed** — terminal `-all` or `~all` |
| DKIM | `<selector>._domainkey.<domain>` | present, with a non-empty `p=` |
| DMARC | `_dmarc.<domain>` | present; whether it *enforces* is carried, not required |

Three states — `:pending → :authorized → :lapsed` — and no second gate, because
unlike naming there is no separate resolution fact: all three records are
published in the same zone by the same act.

**Two records that exist and are not evidence.** `v=spf1 +all` authorizes every
host on the internet to send as the domain; counting its presence as proof would
be counting a blank page as a signature. A DKIM record whose `p=` is empty is
how a key is **revoked** — a statement that the key is gone, wearing the shape
of presence. Both are refused, and both are asserted in the case table rather
than described.

**`p=none` is not one of those.** A domain publishing a monitoring policy has
done the work and is reading reports; requiring enforcement would refuse most
domains that have. So DMARC's presence is required and its strictness is
reported. The asymmetry with SPF is the whole judgement in this record.

Records are picked out of a zone by their `v=` tag and never by position: a name
can hold many TXT records, and reading the first is how a verification token
gets parsed as a mail policy.

### The enforcement, and its honest limit

This app does not sign outbound mail. `mail-send` delivers through the account's
own provider, which does its own signing, so **nothing here can promise a
message will authenticate**. What this deployment can decide is who may use a
domain inside it, and `mail-send` now refuses a send whose From-domain another
tenant has proven mail authority for.

It is silent for a domain nobody has proven — nearly all of them — because an
unclaimed domain reserves nothing and gating on it would refuse ordinary mail to
make a point. It is silent for a member of the holding tenant. The refusal is
derived from the membership table via `user-did` rather than passed in, so a
caller cannot hand itself permission.

A new selector resets the record to `:pending`: a different key is a different
claim, and carrying `:authorized` across a rotation would be a proof about
something nobody read.

### What is still deliberately separate

Holding either binding confers nothing about the other. A tenant can own
`example.co.jp` as a name with no mail posture, or run mail from a domain it has
never claimed as a name. `service-owned-name?` and `normalize-domain` are shared
because they are the same questions; the states, the proofs and the consequences
are not.

## Consequences

- `mail-send/send!` can now refuse before the message is built. The refusal is
  new and reachable, which is the point; it is also narrow enough that no
  existing deployment sees it until two tenants want one domain.
- `:organization-domain-overrides`' removal in ADR-0043 has a sibling here:
  nothing about a mail domain is configured either. Both authorities are proven
  or absent.
- Outbound DNS happens on a timer. No outbound HTTP is added beyond the probe
  ADR-0043 already introduced, and both sweeps visit only records that have
  already passed their gates — a deployment that has proven nothing makes no
  query at all.
- `redirect=` in an SPF record is not followed, so a domain that delegates its
  terminal mechanism reads as not-closed. A real limit; the way past it is an
  explicit `-all` or `~all`.

## Alternatives

**Leave `recheck!` a route and call the limit acceptable.** What ADR-0043 did,
on a premise that was false. Even with a true premise it would be wrong here:
the freshness window exists precisely because the app publishes a `did:web` from
a `:live` binding, and an unmeasured window is an unbounded one.

**Require `p=quarantine` or `p=reject` for mail authority.** Stricter, and it
would refuse most domains that have actually configured mail. `p=none` is a
posture, not a failure, and the record's presence is what proves the owner has
been here.

**Fold mail into the naming binding as a fifth state.** One record, one
lifecycle, and it would make proving a name imply something about mail. The
sentence ADR-0043 wrote to scope mail out is the reason: they are different
authorities and the shape has to say so.

**Generate a DKIM keypair per tenant here.** Only correct if this app signed
outbound mail, and it does not — the provider does. A key this deployment minted
and never used would be exactly the write-only field ADR-0043 spent itself
removing.

## Verified

`clojure -M:test` — 1477 tests, 8861 assertions, 0 failures, 0 errors on the
branch. Re-measured on the merged default branch at closing: see ADR-0049.
`bin/test-oracle-cljs` — 172 cases over 13 shipped cores.

**Watched failing:**

| break | what failed |
|---|---|
| `recheckable` widened to include `:pending` | the sweep's count and change-list assertions, and nothing else |
| the per-binding `catch` made to rethrow | `one-broken-domain-does-not-freeze-every-other-tenants-evidence`, as an error rather than a failure — which is the shape of the bug |
| `spf-closed` dropped from `mail-authorized?` | the `+all` refusal test **and** the `oracle-cases` table |
| `assert-sender-permitted!` made a no-op | its own test, and `mail-send-test` — the latter by *attempting a socket*, which is how it demonstrated the gate runs before the message leaves rather than after |

**Not verified:** that a message actually authenticates at a recipient. Nothing
here signs mail, and the ADR says so in the decision rather than in a footnote.
The three records are read through the same injected resolver the naming gates
use, so no test reaches real DNS.
