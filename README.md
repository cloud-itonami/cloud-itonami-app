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

## OpenAI-compatible clients

Any tool that speaks the OpenAI chat API can use the local models through the
loopback server. Point it at `http://localhost:1338/v1` with any API key — the
compatibility slice is `GET /v1/models` and `POST /v1/chat/completions`, and it
is reachable without a session because the loopback bind is what protects it.
The management API under `/api` is not part of this surface and does require
one.

```bash
curl -N http://localhost:1338/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"gemma4:e2b","stream":true,
       "messages":[{"role":"user","content":"hello"}]}'
```

Streaming is Server-Sent Events in the `chat.completion.chunk` format, ending
with `data: [DONE]`. `stream_options.include_usage` adds the usage-only chunk
before it. Omit `stream` for a single `chat.completion` response.

A request the provider policy refuses — a cloud provider behind a shut gate —
fails with its real status code even under `stream: true`, rather than a 200
stream that carries an error a client would read as an empty answer.

The non-OpenAI extensions are `provider`, `agent_id` and `session_id`: they
select a configured provider, one of the local agents, and the stored
conversation the turn joins. Function calling, embeddings and the Responses API
are not implemented.

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
- OrganismWorker: independently supervised AO assigned to a Tenant
- Relay address: optional provider-managed mail alias

Users may belong to multiple Organizations. The sidebar selector changes the
active membership for the current session; all workspace and OrganismWorker
reads remain scoped to that one active Organization.
Existing Users join another Organization only after Passkey authentication
and explicit acceptance of a one-time, expiring, User-bound invitation.

An OrganismWorker is not a background WorkerRun. It retains its own identity,
memory, lifecycle, and repository authority while Cloud Itonami provides the
organization directory, redacted activity projection, and human intent and
approval surface. See
[ADR-0002](docs/adr/0002-external-artificial-organism-workers.md).

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

Runtime state is stored outside the repository in the host's stable per-user
application-data directory. On macOS this is
`~/Library/Application Support/Cloud Itonami/`. `CLOUD_ITONAMI_DATA_DIR`
remains an explicit override for tests and managed deployments. On first use,
a valid legacy `./data` tree is copied into the stable directory without
deleting or modifying the source.

Every installation has a durable installation ID. Before `state.edn` is
replaced, the previous version is sealed as an AES-256-GCM recovery snapshot;
the device recovery key is kept in macOS Keychain, never in the repository or
EDN state. OAuth tokens likewise use macOS Keychain; only references and
non-secret metadata enter the local state. Provider and relay credentials are
read from environment variables.

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
