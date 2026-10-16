import test from 'node:test';
import assert from 'node:assert/strict';
import {
  authorizeRequest,
  buildUpstreamHeaders,
  createRateLimiter,
  createPublicGateway,
  publicResponseHeaders,
  validUpstreamResponse
} from '../server.mjs';

const config = { host: 'itonami.app', port: 443, upstreamPort: 1338 };

function request(overrides = {}) {
  return {
    hostHeader: 'itonami.app',
    servername: 'itonami.app',
    method: 'GET',
    target: '/',
    headers: {},
    ...overrides
  };
}

test('admits only the bootstrap launcher, registry, DNS proof, and plan preview routes', () => {
  assert.equal(authorizeRequest(request(), config).route.id, 'launcher');
  assert.equal(authorizeRequest(request({ target: '/awai-launcher.js' }), config)
    .route.id, 'launcher-script');
  assert.equal(authorizeRequest(request({ target: '/api/awai/app-registry' }), config)
    .route.id, 'registry');
  assert.equal(authorizeRequest(request({ target: '/api/awai/domain-proof?domain=murakumo.cloud' }), config)
    .route.target, '/api/awai/domain-proof?domain=murakumo.cloud');
  assert.equal(authorizeRequest(request({
    method: 'POST',
    target: '/api/awai/install-plan?domain=murakumo.cloud',
    headers: { origin: 'https://itonami.app' }
  }), config).route.id, 'install-plan');

  for (const target of [
    '/health',
    '/api/awai/install-content',
    '/api/awai/install',
    '/api/awai/app-launch',
    '/api/awai/app-operation',
    '/api/awai/platform-status'
  ]) {
    assert.equal(authorizeRequest(request({ target }), config).reason, 'route-not-public');
  }
});

test('requires matching TLS SNI and the exact public Host authority', () => {
  assert.equal(authorizeRequest(request({ servername: 'evil.example' }), config).status, 421);
  assert.equal(authorizeRequest(request({ hostHeader: 'evil.example' }), config).status, 421);
  assert.equal(authorizeRequest(request({ hostHeader: 'itonami.app:8443' }), config).status, 421);
  assert.equal(authorizeRequest(request({ hostHeader: 'ITONAMI.APP' }), config).ok, true);
});

test('routes only configured public-domain aliases and binds Origin to the SNI host', () => {
  const aliases = { ...config, hosts: ['itonami.app', 'murakumo.cloud'] };
  const aliasRequest = request({
    hostHeader: 'murakumo.cloud',
    servername: 'murakumo.cloud'
  });
  const authorization = authorizeRequest(aliasRequest, aliases);
  assert.equal(authorization.ok, true);
  assert.equal(authorization.origin, 'https://murakumo.cloud');

  const plan = authorizeRequest(request({
    hostHeader: 'murakumo.cloud',
    servername: 'murakumo.cloud',
    method: 'POST',
    target: '/api/awai/install-plan?domain=murakumo.cloud',
    headers: { origin: 'https://murakumo.cloud' }
  }), aliases);
  assert.equal(plan.ok, true);
  assert.equal(plan.origin, 'https://murakumo.cloud');

  assert.equal(authorizeRequest(request({
    hostHeader: 'murakumo.cloud',
    servername: 'itonami.app'
  }), aliases).reason, 'host-mismatch');
  assert.equal(authorizeRequest(request({
    hostHeader: 'unknown.example',
    servername: 'unknown.example'
  }), aliases).reason, 'host-mismatch');
  assert.equal(authorizeRequest(aliasRequest, {
    ...aliases, hosts: ['itonami.app', 'murakumo.cloud', 'murakumo.cloud']
  }).reason, 'host-mismatch');
});

test('refuses Cloudflare-managed hosts in the gateway and app-domain queries', () => {
  assert.equal(authorizeRequest(request(), {
    ...config, hosts: ['itonami.app', 'setup.workers.dev']
  }).reason, 'host-mismatch');
  assert.equal(authorizeRequest(request({
    target: '/api/awai/domain-proof?domain=assets.pages.dev'
  }), config).reason, 'one-canonical-domain-query-required');
  assert.equal(authorizeRequest(request({
    method: 'POST',
    target: '/api/awai/install-plan?domain=api.cloudflare.com',
    headers: { origin: 'https://itonami.app' }
  }), config).reason, 'one-canonical-domain-query-required');
});

test('rejects ambiguous paths, queries, and non-canonical DNS names', () => {
  for (const target of [
    '//evil.example/',
    'https://itonami.app/',
    '/api/awai/app-registry?other=value',
    '/api/awai/domain-proof?domain=one.example&domain=two.example',
    '/api/awai/domain-proof?domain=Upper.example',
    '/api/awai/domain-proof?domain=bad%2fpath.example',
    '/api/awai/domain-proof?domain=one.example&other=value'
  ]) {
    assert.equal(authorizeRequest(request({ target }), config).ok, false, target);
  }
});

test('the plan preview is same-origin and does not accept a body or expectation', () => {
  const base = {
    method: 'POST',
    target: '/api/awai/install-plan?domain=murakumo.cloud',
    headers: { origin: 'https://itonami.app', 'sec-fetch-site': 'same-origin' }
  };
  assert.equal(authorizeRequest(request(base), config).ok, true);
  assert.equal(authorizeRequest(request({
    ...base, headers: { origin: 'https://evil.example' }
  }), config).reason, 'origin-mismatch');
  assert.equal(authorizeRequest(request({
    ...base, headers: { origin: 'https://itonami.app', 'content-length': '1' }
  }), config).reason, 'request-body-not-accepted');
  assert.equal(authorizeRequest(request({
    ...base, headers: { origin: 'https://itonami.app', 'transfer-encoding': 'chunked' }
  }), config).reason, 'request-body-not-accepted');
  assert.equal(authorizeRequest(request({
    ...base, headers: { origin: 'https://itonami.app', expect: '100-continue' }
  }), config).status, 417);
  assert.equal(authorizeRequest(request({
    ...base, headers: { 'sec-fetch-site': 'cross-site', origin: 'https://itonami.app' }
  }), config).reason, 'same-origin-request-required');
});

test('upstream forwarding drops credentials, cookies, and proxy headers', () => {
  assert.deepEqual(buildUpstreamHeaders({
    accept: 'application/json',
    origin: 'https://itonami.app',
    cookie: 'session=secret',
    authorization: 'Bearer secret',
    'x-forwarded-host': 'evil.example',
    host: 'evil.example'
  }, { upstreamPort: 1338 }, 'https://itonami.app'), {
    host: '127.0.0.1:1338',
    accept: 'application/json',
    origin: 'https://itonami.app'
  });
});

test('public responses omit cookies, redirects, server banners, and caching', () => {
  const headers = publicResponseHeaders({
    'content-type': 'application/json; charset=utf-8',
    'content-security-policy': "default-src *; script-src *",
    'set-cookie': 'secret=1',
    location: 'https://evil.example',
    server: 'internal-host'
  }, 12, { id: 'launcher' }, { upstreamPort: 1338 });
  assert.equal(headers['content-length'], '12');
  assert.equal(headers['cache-control'], 'no-store');
  assert.equal(headers['x-content-type-options'], 'nosniff');
  assert.equal(headers['content-security-policy'],
    "default-src 'none'; script-src 'self'; connect-src 'self'; style-src 'self'; frame-src http://*.localhost:1338; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
  assert.equal(headers['permissions-policy'],
    'camera=(), microphone=(), geolocation=(), payment=(), usb=()');
  assert.equal(headers['set-cookie'], undefined);
  assert.equal(headers.location, undefined);
  assert.equal(headers.server, undefined);
});

test('launcher HTML accepts an upstream CSP because the gateway replaces it', () => {
  const route = { id: 'launcher', contentTypes: ['text/html;'] };
  assert.equal(validUpstreamResponse(route, 200, {
    'content-type': 'text/html; charset=utf-8',
    'content-security-policy': 'default-src *; script-src *'
  }), true);
  assert.equal(validUpstreamResponse(route, 200, {
    'content-type': 'text/html; charset=utf-8'
  }), true);
  assert.equal(validUpstreamResponse(route, 302, {
    'content-type': 'text/html; charset=utf-8',
    'content-security-policy': "default-src 'none'; script-src 'self'; frame-ancestors 'none'"
  }), false);
});

test('rate limits refill monotonically and evict old keys when bounded', () => {
  let now = 0;
  const consume = createRateLimiter({ now: () => now, maximumEntries: 1 });
  const rule = { capacity: 2, refillPerSecond: 1 };
  assert.equal(consume('client-a', rule), true);
  assert.equal(consume('client-a', rule), true);
  assert.equal(consume('client-a', rule), false);
  now = 1000;
  assert.equal(consume('client-a', rule), true);
  assert.equal(consume('client-b', rule), true);
  assert.equal(consume('client-a', rule), true);
});

test('refuses malformed TLS certificate material before opening a listener', () => {
  assert.throws(() => createPublicGateway({
    host: 'itonami.app',
    port: 443,
    upstreamPort: 1338,
    bindAddress: '127.0.0.1',
    cert: Buffer.alloc(0),
    key: Buffer.alloc(0)
  }), /invalid-gateway-tls-certificate/);
});
