# Contributing

1. Create a focused branch.
2. Keep product-neutral behavior in `src/`.
3. Put organization-specific, non-secret defaults in `profiles/`.
4. Pin runtime Git dependencies to immutable SHAs.
5. Run `clojure -M:test` and `clojure -M:lint`.
6. Never commit runtime data, credentials, mailbox exports, or OAuth tokens.

Changes to authentication, DID, relay, provider policy, or persistence should
include tests and an update to the threat model or architecture documentation.
