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
route table is compiled into the program. Host and TLS SNI must match exactly.
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
ITONAMI_GATEWAY_BIND=127.0.0.1
ITONAMI_GATEWAY_PORT=8443
ITONAMI_GATEWAY_CERT=/etc/itonami/tls/itonami.app.crt
ITONAMI_GATEWAY_KEY=/etc/itonami/tls/itonami.app.key
```

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
