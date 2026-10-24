#!/usr/bin/env node

import { readFileSync } from 'node:fs';
import { isIP } from 'node:net';
import { X509Certificate } from 'node:crypto';
import { request as upstreamRequest, Agent as HttpAgent } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';
import { pathToFileURL } from 'node:url';

const MAX_HEADER_BYTES = 16 * 1024;
const MAX_URL_BYTES = 2048;
const MAX_ACTIVE_REQUESTS = 8;
const UPSTREAM_TIMEOUT_MS = 8_000;
const REQUEST_TIMEOUT_MS = 12_000;
const ALLOWED_UPSTREAM_STATUSES = new Set([200, 400, 403, 404, 409, 503]);
const ALLOWED_RESPONSE_HEADERS = [
  'content-security-policy',
  'permissions-policy',
  'referrer-policy',
  'vary'
];

const routeTable = new Map([
  ['GET /', { id: 'launcher', maxResponseBytes: 64 * 1024,
    contentTypes: ['text/html;'] }],
  ['GET /awai-launcher.js', { id: 'launcher-script', maxResponseBytes: 256 * 1024,
    contentTypes: ['text/javascript;', 'application/javascript;'] }],
  ['GET /api/awai/app-registry', { id: 'registry', maxResponseBytes: 16 * 1024 * 1024,
    contentTypes: ['application/json;'] }],
  ['GET /api/awai/domain-proof', { id: 'domain-proof', maxResponseBytes: 64 * 1024,
    contentTypes: ['application/json;'], query: 'domain' }],
  ['POST /api/awai/install-plan', { id: 'install-plan', maxResponseBytes: 2 * 1024 * 1024,
    contentTypes: ['application/json;'], query: 'domain', originRequired: true }]
]);

const limiterRules = {
  launcher: { capacity: 60, refillPerSecond: 1 },
  'launcher-script': { capacity: 60, refillPerSecond: 1 },
  registry: { capacity: 30, refillPerSecond: 0.5 },
  'domain-proof': { capacity: 6, refillPerSecond: 0.1 },
  'install-plan': { capacity: 6, refillPerSecond: 0.1 }
};

function failure(status, reason) {
  return { ok: false, status, reason };
}

function validDnsName(value) {
  if (typeof value !== 'string' || value.length < 4 || value.length > 253
      || value !== value.toLowerCase() || value.endsWith('.') || isIP(value)) {
    return false;
  }
  const labels = value.split('.');
  return labels.length >= 2 && labels.every((label) =>
    label.length >= 1 && label.length <= 63
    && /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(label));
}

const cloudflareHostRoots = [
  'workers.dev', 'pages.dev', 'r2.dev', 'cfargotunnel.com',
  'cloudflare.com', 'cloudflare.net', 'cloudflare-dns.com'
];

function validIndependentHost(value) {
  return validDnsName(value)
    && !cloudflareHostRoots.some((root) => value === root || value.endsWith(`.${root}`));
}

function validPort(value) {
  return Number.isInteger(value) && value >= 1 && value <= 65535;
}

function assertCertificateForHost(certificate, host) {
  let parsed;
  try {
    parsed = new X509Certificate(certificate);
  } catch (_error) {
    throw new Error('invalid-gateway-tls-certificate');
  }
  const notBefore = Date.parse(parsed.validFrom);
  const notAfter = Date.parse(parsed.validTo);
  if (parsed.checkHost(host) !== host || !Number.isFinite(notBefore)
      || !Number.isFinite(notAfter) || Date.now() < notBefore || Date.now() > notAfter) {
    throw new Error('gateway-tls-certificate-host-or-validity-mismatch');
  }
}

function expectedAuthority(host, port) {
  return port === 443 ? new Set([host, `${host}:443`]) : new Set([`${host}:${port}`]);
}

function configuredHosts(config) {
  const hosts = config.hosts ?? [config.host];
  if (!Array.isArray(hosts) || hosts.length < 1 || hosts.length > 64
      || new Set(hosts).size !== hosts.length
      || !hosts.every((host) => validIndependentHost(host))) {
    return null;
  }
  return new Set(hosts);
}

function hasBody(headers) {
  return headers['transfer-encoding'] !== undefined
    || (headers['content-length'] !== undefined && headers['content-length'] !== '0');
}

function parseDomainQuery(rawQuery) {
  const parameters = new URLSearchParams(rawQuery);
  const values = parameters.getAll('domain');
  if ([...parameters.keys()].length !== 1 || values.length !== 1
      || !validIndependentHost(values[0])) {
    return null;
  }
  return values[0];
}

export function authorizeRequest(request, config) {
  const { port } = config;
  const hosts = configuredHosts(config);
  const servername = typeof request.servername === 'string'
    ? request.servername.toLowerCase()
    : '';
  if (!hosts || !validIndependentHost(config.host) || !validPort(port)
      || !hosts.has(config.host)
      || typeof request.servername !== 'string'
      || !hosts.has(servername)
      || typeof request.hostHeader !== 'string'
      || !expectedAuthority(servername, port).has(request.hostHeader.toLowerCase())) {
    return failure(421, 'host-mismatch');
  }

  if (typeof request.target !== 'string' || request.target.length > MAX_URL_BYTES
      || !request.target.startsWith('/') || request.target.startsWith('//')
      || /[\\\u0000-\u001f\u007f#]/.test(request.target)) {
    return failure(400, 'invalid-request-target');
  }

  const headers = request.headers ?? {};
  const connection = typeof headers.connection === 'string'
    ? headers.connection.toLowerCase().split(',').map((value) => value.trim())
    : [];
  if (headers.upgrade !== undefined || connection.includes('upgrade')) {
    return failure(400, 'upgrade-not-supported');
  }
  if (hasBody(headers)) return failure(400, 'request-body-not-accepted');
  if (headers.expect !== undefined) return failure(417, 'expectation-not-supported');

  const queryIndex = request.target.indexOf('?');
  const pathname = queryIndex < 0 ? request.target : request.target.slice(0, queryIndex);
  const rawQuery = queryIndex < 0 ? '' : request.target.slice(queryIndex + 1);
  const route = routeTable.get(`${request.method} ${pathname}`);
  if (!route) return failure(404, 'route-not-public');

  const origin = port === 443 ? `https://${servername}` : `https://${servername}:${port}`;
  if (headers.origin !== undefined && headers.origin !== origin) {
    return failure(403, 'origin-mismatch');
  }
  if (route.originRequired && headers.origin !== origin) {
    return failure(403, 'same-origin-request-required');
  }
  if (route.originRequired && headers['sec-fetch-site'] !== undefined
      && headers['sec-fetch-site'] !== 'same-origin') {
    return failure(403, 'same-origin-request-required');
  }

  let target = pathname;
  if (route.query) {
    const domain = parseDomainQuery(rawQuery);
    if (!domain) return failure(400, 'one-canonical-domain-query-required');
    target += `?domain=${encodeURIComponent(domain)}`;
  } else if (queryIndex >= 0) {
    return failure(400, 'query-not-accepted');
  }

  return { ok: true, route: { ...route, target }, origin };
}

export function buildUpstreamHeaders(requestHeaders, config, origin) {
  const accepted = typeof requestHeaders.accept === 'string'
    ? requestHeaders.accept.slice(0, 512)
    : '*/*';
  const result = {
    host: `127.0.0.1:${config.upstreamPort}`,
    accept: accepted
  };
  if (requestHeaders.origin === origin) result.origin = origin;
  // Cookies, authorization, forwarding headers and client-supplied host values
  // are intentionally never copied to the loopback application host.
  return result;
}

export function publicResponseHeaders(upstreamHeaders, bodyLength, route, config) {
  const headers = {
    'cache-control': 'no-store',
    'content-length': String(bodyLength),
    'x-content-type-options': 'nosniff',
    'referrer-policy': 'no-referrer'
  };
  const contentType = upstreamHeaders['content-type'];
  if (typeof contentType === 'string' && !/[\r\n]/.test(contentType)) {
    headers['content-type'] = contentType;
  }
  for (const name of ALLOWED_RESPONSE_HEADERS) {
    const value = upstreamHeaders[name];
    if (typeof value === 'string' && !/[\r\n]/.test(value) && value.length <= 8192) {
      headers[name] = value;
    }
  }
  if (route.id === 'launcher') {
    headers['content-security-policy'] =
      "default-src 'none'; script-src 'self'; connect-src 'self'; style-src 'self'; "
      + `frame-src http://*.localhost:${config.upstreamPort}; `
      + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    headers['permissions-policy'] =
      'camera=(), microphone=(), geolocation=(), payment=(), usb=()';
  }
  return headers;
}

export function validUpstreamResponse(route, status, headers) {
  const contentType = headers['content-type'];
  const typeAccepted = typeof contentType === 'string'
    && route.contentTypes.some((prefix) => contentType.startsWith(prefix));
  if (!ALLOWED_UPSTREAM_STATUSES.has(status) || !typeAccepted) return false;
  // The gateway replaces launcher CSP and Permissions-Policy on every public
  // response. Requiring an upstream CSP here would make that public boundary
  // depend on a separately deployed loopback application version.
  return true;
}

export function createRateLimiter({ now = Date.now, maximumEntries = 4096 } = {}) {
  const buckets = new Map();
  return function consume(key, rule) {
    const currentTime = now();
    const previous = buckets.get(key);
    const tokens = previous
      ? Math.min(rule.capacity,
        previous.tokens + Math.max(0, currentTime - previous.updatedAt)
          * rule.refillPerSecond / 1000)
      : rule.capacity;
    if (!previous && buckets.size >= maximumEntries) {
      buckets.delete(buckets.keys().next().value);
    }
    buckets.set(key, { tokens: Math.max(0, tokens - 1), updatedAt: currentTime });
    return tokens >= 1;
  };
}

function jsonResponse(response, status, reason) {
  const body = Buffer.from(JSON.stringify({ status: 'unavailable', reason }));
  response.writeHead(status, {
    'cache-control': 'no-store',
    'content-length': String(body.length),
    'content-type': 'application/json; charset=utf-8',
    'referrer-policy': 'no-referrer',
    'x-content-type-options': 'nosniff'
  });
  response.end(body);
}

function readBoundedResponse(response, maximumBytes) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    response.on('data', (chunk) => {
      total += chunk.length;
      if (total > maximumBytes) {
        response.destroy(new Error('upstream-response-too-large'));
        reject(new Error('upstream-response-too-large'));
        return;
      }
      chunks.push(chunk);
    });
    response.on('end', () => resolve(Buffer.concat(chunks, total)));
    response.on('error', reject);
  });
}

function proxyToLoopback(request, route, config, origin, agent) {
  return new Promise((resolve, reject) => {
    const upstream = upstreamRequest({
      hostname: '127.0.0.1',
      port: config.upstreamPort,
      method: request.method,
      path: route.target,
      headers: buildUpstreamHeaders(request.headers, config, origin),
      agent,
      timeout: UPSTREAM_TIMEOUT_MS
    }, async (response) => {
      try {
        if (!ALLOWED_UPSTREAM_STATUSES.has(response.statusCode)) {
          response.destroy();
          resolve({ status: 502, headers: {}, body: Buffer.from('') });
          return;
        }
        if (!validUpstreamResponse(route, response.statusCode, response.headers)) {
          response.destroy();
          resolve({ status: 502, headers: {}, body: Buffer.from('') });
          return;
        }
        const body = await readBoundedResponse(response, route.maxResponseBytes);
        resolve({ status: response.statusCode, headers: response.headers, body });
      } catch (_error) {
        reject(new Error('upstream-response-unavailable'));
      }
    });
    upstream.on('timeout', () => upstream.destroy(new Error('upstream-timeout')));
    upstream.on('error', () => reject(new Error('upstream-unavailable')));
    request.on('aborted', () => upstream.destroy(new Error('client-aborted')));
    upstream.end();
  });
}

export function createPublicGateway(config) {
  const hosts = configuredHosts(config);
  if (!validDnsName(config.host) || !hosts || !hosts.has(config.host) || !validPort(config.port)
      || !validPort(config.upstreamPort)
      || typeof config.bindAddress !== 'string' || !isIP(config.bindAddress)
      || !Buffer.isBuffer(config.cert) || !Buffer.isBuffer(config.key)) {
    throw new Error('invalid-gateway-configuration');
  }
  for (const host of hosts) assertCertificateForHost(config.cert, host);
  const limiter = createRateLimiter();
  const upstreamAgent = new HttpAgent({ keepAlive: true, maxSockets: MAX_ACTIVE_REQUESTS });
  let activeRequests = 0;
  const server = createHttpsServer({
    cert: config.cert,
    key: config.key,
    minVersion: 'TLSv1.2',
    maxHeaderSize: MAX_HEADER_BYTES
  }, async (request, response) => {
    if (activeRequests >= MAX_ACTIVE_REQUESTS) {
      jsonResponse(response, 503, 'gateway-busy');
      return;
    }
    activeRequests += 1;
    response.setTimeout(REQUEST_TIMEOUT_MS, () => response.destroy());
    try {
      const authorization = authorizeRequest({
        hostHeader: request.headers.host,
        servername: request.socket.servername,
        method: request.method,
        target: request.url,
        headers: request.headers
      }, config);
      if (!authorization.ok) {
        jsonResponse(response, authorization.status, authorization.reason);
        return;
      }
      const address = request.socket.remoteAddress ?? 'unknown';
      const rule = limiterRules[authorization.route.id];
      if (!limiter(`${address}:${authorization.route.id}`, rule)) {
        response.setHeader('retry-after', '1');
        jsonResponse(response, 429, 'rate-limited');
        return;
      }
      const proxied = await proxyToLoopback(
        request, authorization.route, config, authorization.origin, upstreamAgent);
      response.writeHead(proxied.status,
        publicResponseHeaders(proxied.headers, proxied.body.length,
          authorization.route, config));
      response.end(proxied.body);
    } catch (_error) {
      if (!response.headersSent && !response.destroyed) {
        jsonResponse(response, 503, 'upstream-unavailable');
      } else if (!response.destroyed) {
        response.destroy();
      }
    } finally {
      activeRequests -= 1;
    }
  });
  server.headersTimeout = 8_000;
  server.requestTimeout = 12_000;
  server.keepAliveTimeout = 5_000;
  server.maxHeadersCount = 32;
  server.on('checkContinue', (_request, response) => {
    jsonResponse(response, 417, 'expectation-not-supported');
  });
  server.on('upgrade', (_request, socket) => socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n'));
  server.on('connect', (_request, socket) => socket.end('HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n'));

  return {
    server,
    reloadCertificate(cert, key) {
      for (const host of hosts) assertCertificateForHost(cert, host);
      server.setSecureContext({ cert, key, minVersion: 'TLSv1.2' });
    },
    close() {
      upstreamAgent.destroy();
      return new Promise((resolve, reject) => {
        server.close((error) => error ? reject(error) : resolve());
      });
    }
  };
}

function positiveInteger(value, fallback) {
  if (value === undefined || value === '') return fallback;
  if (!/^[1-9][0-9]{0,4}$/.test(value)) return null;
  const parsed = Number(value);
  return validPort(parsed) ? parsed : null;
}

function loadConfig(environment = process.env) {
  const hostList = environment.ITONAMI_GATEWAY_HOSTS === undefined
    ? null
    : environment.ITONAMI_GATEWAY_HOSTS.split(',').map((value) => value.trim());
  const host = environment.ITONAMI_GATEWAY_HOST ?? hostList?.[0];
  const hosts = hostList ?? [host];
  const bindAddress = environment.ITONAMI_GATEWAY_BIND ?? '127.0.0.1';
  const port = positiveInteger(environment.ITONAMI_GATEWAY_PORT, 8443);
  const upstreamPort = 1338;
  const certFile = environment.ITONAMI_GATEWAY_CERT;
  const keyFile = environment.ITONAMI_GATEWAY_KEY;
  if (!validDnsName(host) || !hosts.includes(host) || !configuredHosts({ host, hosts })
      || !port || !isIP(bindAddress)
      || (environment.ITONAMI_GATEWAY_UPSTREAM_PORT !== undefined
        && environment.ITONAMI_GATEWAY_UPSTREAM_PORT !== String(upstreamPort))
      || !certFile || !keyFile) {
    throw new Error('set a canonical gateway host, fixed upstream port 1338, bind address, and TLS cert/key paths');
  }
  return {
    host,
    hosts,
    bindAddress,
    port,
    upstreamPort,
    certFile,
    keyFile,
    cert: readFileSync(certFile),
    key: readFileSync(keyFile)
  };
}

async function main() {
  const config = loadConfig();
  const gateway = createPublicGateway(config);
  gateway.server.listen(config.port, config.bindAddress, () => {
    process.stdout.write(`Itonami public launcher gateway listening on ${config.bindAddress}:${config.port} for ${config.hosts.join(',')}\n`);
  });
  process.on('SIGHUP', () => {
    try {
      gateway.reloadCertificate(readFileSync(config.certFile), readFileSync(config.keyFile));
      process.stdout.write('Itonami public launcher gateway reloaded TLS certificate\n');
    } catch (_error) {
      process.stderr.write('Itonami public launcher gateway kept its current TLS certificate; reload failed\n');
    }
  });
  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => gateway.close().then(() => process.exit(0)));
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    await main();
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  }
}
