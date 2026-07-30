# itonami.cloud mail webhooks

Cloudflare Worker event gateway for Cloud Itonami mail synchronization.

## Public endpoints

- `GET https://hooks.itonami.cloud/health`
- `POST https://hooks.itonami.cloud/v1/webhooks/google`
- `POST https://hooks.itonami.cloud/v1/webhooks/microsoft`
- `GET https://hooks.itonami.cloud/v1/events/poll`
- `POST https://hooks.itonami.cloud/v1/events/ack`
- `POST https://hooks.itonami.cloud/v1/account-links/upsert`
- `GET https://hooks.itonami.cloud/v1/account-links?subjectDid=did:...`

The webhook endpoints validate provider-specific secrets and place only change
signals in Cloudflare KV. OAuth tokens and message bodies are never sent to the
Worker. A local Cloud Itonami process polls with a bearer token from macOS
Keychain, runs Gmail `history.list` or Microsoft Graph delta sync locally, then
acknowledges the queued signal.

## Passkey / Wallet Account Links

Account Link endpoints store versioned public proofs that bind a Cloud Itonami
Passkey User DID to a CAIP-10 wallet account. The local app verifies the SIWE
signature before upload and again after download. KV never receives a private
key, seed phrase, Passkey credential secret, OAuth token, mailbox content, or
storage content. Revoked link IDs cannot be reactivated.

These endpoints use the same authenticated relay boundary as event polling.
The Worker validates size and immutable/revoked state but is a transport, not
the cryptographic authority; consuming apps must verify every signed proof.

## Gmail Pub/Sub

1. Create a Pub/Sub topic such as `projects/PROJECT/topics/itonami-gmail`.
2. Grant `gmail-api-push@system.gserviceaccount.com` publisher access.
3. Create an authenticated push subscription targeting:
   `https://hooks.itonami.cloud/v1/webhooks/google?token=VERIFICATION_TOKEN`.
4. Put the same verification token in the Worker secret
   `GOOGLE_VERIFICATION_TOKEN` and local Keychain account
   `cloud-itonami-app.webhooks/google-verification`.
5. Set `ITONAMI_GMAIL_TOPIC` for the local app. It renews Gmail `watch` daily.
6. For authenticated Pub/Sub push, also set Worker secrets
   `GOOGLE_PUSH_AUDIENCE` and `GOOGLE_PUSH_SERVICE_ACCOUNT`.

## Microsoft Graph

The local app creates and renews an Inbox message subscription using:

`https://hooks.itonami.cloud/v1/webhooks/microsoft`

The Worker returns Graph's validation token as plain text and accepts
notifications only when every `clientState` matches `GRAPH_CLIENT_STATE`.
The same value is stored in the local Keychain account
`cloud-itonami-app.webhooks/graph-client-state`.

## Secrets

The Worker requires:

- `RELAY_ACCESS_TOKEN`
- `GOOGLE_VERIFICATION_TOKEN`
- `GRAPH_CLIENT_STATE`

Optional strict Pub/Sub OIDC validation:

- `GOOGLE_PUSH_AUDIENCE`
- `GOOGLE_PUSH_SERVICE_ACCOUNT`

Never store these values in this repository.
