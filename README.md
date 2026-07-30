# Cloud Itonami App

Cloud Itonami is a local-first AI workspace built with
[`kotoba-lang/kotoba`](https://github.com/kotoba-lang/kotoba) and
[`kotoba-lang/shell`](https://github.com/kotoba-lang/shell). It combines chat,
mail, projects, drive, calendar, Passkey identity, and delegated service
connections while keeping local data and cloud authority boundaries explicit.

The repository is the tenant-neutral application. `gftd.ai` is represented by
the optional [`profiles/gftd.edn`](profiles/gftd.edn) distribution profile, not
by a fork of the application.

## Status

This is an early public development release. The loopback server, local model
adapters, chat UI, background worker runs, read-only workspace integrations,
Passkey registration, User `did:key`, organization membership, OAuth/PKCE
connections, and optional private email relay client are implemented. Production multi-tenant hosting,
domain verification, DID document publication, and signed desktop packages
remain separate deployment responsibilities.

## Requirements

- macOS 14 or later for the native shell, EventKit, and Keychain integrations
- Java 21+
- Clojure CLI
- `jq` and `curl`
- Ollama or another configured OpenAI-compatible provider

Pure tests and the loopback web surface also run on Linux.

## Run

```bash
clojure -P
clojure -M:server
open http://localhost:1338
```

On macOS, `bin/cloud-itonami-app` uses a sibling `kotoba-lang/shell` checkout
or `CLOUD_ITONAMI_SHELL_DIR` when available and otherwise opens the web surface.

```bash
bin/cloud-itonami-app
```

The server binds to `127.0.0.1` by default. The browser intentionally uses
`http://localhost:1338`, which is required for the WebAuthn localhost
development exception.

## Background worker runs

The Worker tab queues prompts that take longer than an interactive turn. Runs
share the local model with chat under a small concurrency limit (default 2,
`:worker :max-concurrency`) and go through the same fail-closed provider policy,
so a background run cannot reach a cloud provider that chat could not.

Runs are kept in memory and **are lost when the server restarts** — the durable
store keeps only a bounded completion event per run, because persisting streamed
output would rewrite the whole state file on every token. Output is capped at
16,000 characters per run. Cancellation takes effect at the next streamed
chunk, so a stalled provider request can stay open until it times out.

## Identity and organizations

First launch requires only a Passkey. The verified ES256/P-256 public key is
encoded as the stable User `did:key`; the private key remains in the
authenticator. Organization information can be entered later.

The default public profile uses managed addresses below `cloud-itonami.app`,
but does **not** claim `did:web`. A deployment may enable Organization
`did:web` only after it controls the generated domains and publishes the
corresponding DID documents. Operators that do not control that domain must
override both identity domains before inviting production users.

Identity concepts remain separate:

- Installation: one local application state
- User: Passkey-rooted person with a stable `did:key`
- Tenant: internal immutable organization/workspace ID
- Organization ID: human-readable, immutable slug
- Domain: managed or independently verified DNS name
- Membership: User-to-Tenant role
- Relay address: optional provider-managed mail alias

## Distribution profiles

Set a named profile or an EDN file path:

```bash
CLOUD_ITONAMI_PROFILE=gftd clojure -M:server
CLOUD_ITONAMI_PROFILE=/secure/path/company.edn clojure -M:server
```

Profiles contain branding and non-secret service coordinates. Secrets remain
in environment variables or the operating-system credential store. Local
`data/config.edn` is merged after the selected profile.

The included gftd profile maps:

- the corporate `gftd` organization to `gftd.ai`;
- managed user organizations to `{organization-id}.gftd.ai`;
- public addresses to `@gftd.ai`;
- relay calls to `https://relay.gftd.ai`.

Enabling `:publish-did-web?` is a deployment assertion: the operator must
actually serve the DID documents over HTTPS.

## Dependencies

Release and CI dependencies are immutable Git SHAs in `deps.edn`. A `:dev`
alias overrides them with sibling `kotoba-lang` checkouts for the west
workspace:

```bash
clojure -M:dev:test
```

Do not commit `:local/root` dependencies to the release dependency map.
See [the dependency policy](docs/dependencies.md), including the binary
distribution license gate.

## Data and secrets

Runtime state is stored below `data/` and is ignored by Git. OAuth tokens use
macOS Keychain; only references and non-secret metadata enter the local state.
Provider and relay credentials are read from environment variables.

See [`.env.example`](.env.example), [the architecture](docs/architecture.md),
and [the tenant model](docs/tenant-model.md).

## Verify

```bash
clojure -M:test
clojure -M:lint
```

## License

Code in this repository is available under Apache License 2.0. Dependencies
remain under their respective licenses; see [NOTICE](NOTICE) and
[the dependency policy](docs/dependencies.md).
