# Itonami public launcher gateway

This Node service is the first public ingress component for `itonami.app`. It
terminates standard HTTPS and proxies a small, fixed set of launcher and
discovery routes to the Itonami app host on `127.0.0.1`. It has no Cloudflare
SDK, Worker, Pages, Tunnel, R2, or DNS dependency.

The gateway serves only the common launcher and its public discovery surface:

| Method | Path | Behavior |
|---|---|---|
| `GET` | `/` | Launcher HTML |
| `GET` | `/awai-launcher.js` | Launcher client |
| `GET` | `/api/awai/app-registry` | Signed registry candidates, after Itonami verifies envelopes |
| `GET` | `/api/awai/domain-proof?domain=...` | DNSSEC proof for a registry candidate |
| `POST` | `/api/awai/install-plan?domain=...` | Same-origin manifest and capability preview; may cache the verified manifest locally |

The gateway does not expose `/health`, install-content, install, app-launch, or
app-operation routes. It never forwards cookies, authorization, client Host,
or forwarding headers. Its upstream address is fixed to IPv4 loopback and its
route table is compiled into the program. The canonical `itonami.app` host and
any explicitly configured product-domain aliases share this route table; every
request's Host must exactly match its TLS SNI, and each host must be in the
configured allowlist. The single TLS certificate must contain a valid SAN for
every configured host. The launcher filters the registry to the matching
domain when a visitor enters through an alias.
From a public host, each candidate also offers a user-initiated handoff to the
local Itonami launcher at `http://127.0.0.1:1338/`, carrying only that exact
canonical domain. The local launcher re-reads its configured Awai peer and
filters to the requested binding; malformed or duplicate domain parameters
fail closed. Install, content staging, capability consent, and app launch stay
on the user's loopback host. The user must start that local host before using
the link. It targets the default local port; installations using another port
need a separately configured launcher. This handoff has local DOM and query
tests, but it has not been exercised in a browser against a running local host.
Requests, URLs, query parameters, response sizes, concurrency, and per-client
rates are bounded. TLS certificate files are read from the host and can be
reloaded with `SIGHUP`.

This is a bootstrap directory gateway, not the finished public app-serving
plane. The installed-app runtime is still loopback-only. External product
domains must not be pointed at this gateway until signed domain-to-app routing,
multi-peer registry freshness, public app capabilities, and independent-host
recovery are implemented and qualified.

## Local verification

Run the built-in Node tests from this directory:

```sh
npm test
```

To start locally, provide a TLS certificate and private key for the configured
host. The default listener is `127.0.0.1:8443`; it is not publicly reachable.
The matching Itonami app host must listen on `127.0.0.1:1338`. Set
`ITONAMI_APP_ORIGIN` to the exact external origin, including the port when it
is not 443 (for example, `https://itonami.app:8443` for a local TLS run).

Required environment:

```text
ITONAMI_GATEWAY_HOST=itonami.app
# Leave ITONAMI_GATEWAY_HOSTS unset until an individual alias passes its migration gates.
ITONAMI_GATEWAY_BIND=127.0.0.1
ITONAMI_GATEWAY_PORT=8443
ITONAMI_GATEWAY_CERT=/etc/itonami/tls/itonami-public-san.crt
ITONAMI_GATEWAY_KEY=/etc/itonami/tls/itonami-public-san.key
```

When `ITONAMI_GATEWAY_HOSTS` is omitted, the gateway accepts only
`ITONAMI_GATEWAY_HOST`. Add one hostname at a time after its product release,
accepted Awai domain binding, DNSSEC proof, direct-DNS plan, complete path and
subdomain inventory, and matching certificate have passed review. The static
TLS/HTTP allowlist does not itself authorize an app or establish domain
ownership.

Production staging requires a host with direct public A/AAAA records and
working inbound TCP 443. Obtain and renew the certificate using an independent
DNS/TLS operator; the gateway does not automate certificate issuance. Set the
listener address and port explicitly, keep the private key readable only by
the gateway account, use a currently valid certificate whose SAN matches the
configured host, and send `SIGHUP` after an atomic certificate renewal. Startup
and reload both check the certificate name and validity period.
Do not enable a proxy/CDN in front of the origin: direct DNS and the gateway's
own TLS endpoint are part of the qualification.

The supplied systemd unit expects an `itonami-gateway` account, the project at
`/opt/itonami/awai-public-gateway`, and an operator-owned environment file at
`/etc/itonami/public-gateway.env`. It grants only the low-port bind capability
needed for TCP 443. No production host, account, certificate, DNS zone, or
domain setting is provisioned by this source package.
