# Awai external app runtime boundary

`cloud.itonami.app.awai-apps` is the host lifecycle coordinator for externally
published apps. It is separate from the built-in Itonami workspace view picker:
an external app has a signed app identity, content-addressed release, Awai cell
references, and an explicit local capability grant.

## Lifecycle

1. The DNS adapter canonicalizes a hostname, validates its DNSSEC TXT proof,
   and returns the exact `awai-app=<binding CID>` value with freshness metadata.
2. The Awai adapter verifies the signed domain binding and release chain and
   returns normalized binding/release facts. `resolve-domain` passes those facts
   to `kotoba.protocol.install` and returns a fetch-and-consent plan.
3. The content adapter retrieves every required CID from configured independent
   origins, recomputes each CID, and supplies only the verified CID set to
   `finish-install` or `finish-update`.
4. The host asks the user about every new capability. Cell changes require a
   runtime-issued migration receipt. The host persists the returned installation
   record atomically and retains its prior record until the new release starts.
5. `launch-decision` can admit a cached release without DNS, registry, or
   content-network access. The last domain proof is reported as origin status;
   it does not grant app capabilities.

## Host-owned adapters

The module does not choose DNS resolvers, Awai peers, content origins, local
storage, or a JavaScript/WASM execution sandbox. Those are injected host ports.
Each port must return verified facts or a structured failure; no port may fall
back to a Cloudflare Worker, Pages project, R2 object, or Tunnel origin. Network
and effect adapters remain outside the app bundle and must enforce the current
local capability intersection at every call.

This is a shared lifecycle contract, not evidence of a production Awai
transport, a qualified app sandbox, or a deployed `itonami.app` launcher. The
app registry, independent multi-origin retrieval, cell migration, sandbox
isolation, and cross-node state read/write still need implementation and
qualification before any external domain moves.
