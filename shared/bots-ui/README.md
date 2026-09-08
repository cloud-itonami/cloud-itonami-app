# Shared Bot application views

The production site's existing `cloud.itonami.ui` and Markdown components are
retained from its pinned revision 1cbfa9b85d44abcb42c67688c1cdbdba6c34b0c8.
The public org/repo directory and capital views now live alongside them so the
app owns the interface while the site supplies its authenticated transport.

`capital_ui.cljc` is the Japanese/English DADS-based view. `capital_client.cljs`
coordinates exact transaction review, wallet approval, confirmation, pending
transaction recovery and Bot/CLI deep links. It receives authenticated HTTP and
EIP-1193 wallet adapters; it never owns keys or invents financial balances.

The capital API and contracts are in cloud-itonami/cloud-itonami-api; the apex
router transports /api/capital to the existing authenticated ingress. Base USDC
and Aave V3 fixed-term rounds are the implemented strategy. Other chains and
protocols are not advertised as operational. Contract creation and financial
execution require a matching wallet signature.

Validation: the consuming cloud-itonami site release build and
`scripts/check-capital-browser.mjs` exercise round creation, exact USDC approval,
deposit confirmation, deep links and mobile layout using explicit fixtures.
The API repository runs actual local-EVM contract/receipt tests separately.
