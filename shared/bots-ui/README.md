# Shared Bots UI

`cloud.itonami.bots-ui` is dependency-free CLJC Hiccup. Desktop, mobile and
itonami.cloud consume this source. Hosts own state, authentication, loading,
navigation and transactions. Do not copy this component into another repo.

Desktop and mobile consume `shared/bots-ui/src` on their classpaths. The site
pins this repository's commit with `:deps/root "shared/bots-ui"`, avoiding the
desktop JVM dependency graph. English and Japanese copy live with the view;
other locales fall back to English.

## Current scope

The shared component presents Business Bot activity and three participation
options: USDC lending, data sales and Human Computing. Desktop and mobile
currently embed the participation component; the website also projects its
existing public activity feeds into the shared Bot list. `cloud.itonami.my-bots-ui` now supplies a private Bot list, thread and approval
view for the website. Desktop/mobile private threads and cross-device account
transport still need to adopt it.

No transaction option is enabled by default. A host may supply a capability
with `:available? true` and a same-origin `:href` only once the corresponding
review/transaction route exists. A public Bot status or connected wallet does
not establish user identity, ownership, a deposit receipt or an order.

## Remaining integration

- Replace the mobile fleet-first entry with authenticated My Bots after the
  human-session API is available to the mobile host. The existing device-pairing
  command token is an agent credential, not a human login.
- Adopt `cloud.itonami.my-bots-ui` in desktop/mobile private Bot hosts, preserving
  their authenticated human session and existing tool approvals.
- Connect the existing Human Computing eligibility/accept/submit/review domain
  to per-Bot pages. Preserve qualifications and existing authorization gates.
- Bind data offers to a Bot, buyer, license, price and delivery/settlement state.
- Implement verified USDC deposit receipts and account-owned lending positions.
  The public site's current pool is simulation-only and lacks a configured
  settlement signer. Do not enable lending from a generic live status flag.
- Unify WebAuthn smart-account enrolment and Web3 signature authentication with
  server-verified nonce/origin/expiry and a session established on the originating
  browser. Preserve account identity across devices, not credential IDs across
  WebAuthn RP domains. A predicted smart-account address is not a deployment.

The requested Web3-only product direction permits replacing the old passkey-only
entry policy; it does not make a linked address an authenticated session. Do not
add SSO as a fallback. Service OAuth connectors used by a Bot remain separate.

## Test

From the repository root, with nbb installed:

```
nbb -cp shared/bots-ui/src shared/bots-ui/test/cloud/itonami/bots_ui_test.cljs
```

The tests cover capability isolation, unsafe destinations, independent action
availability and unavailable activity without fabricated work.
