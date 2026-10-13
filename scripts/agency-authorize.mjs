#!/usr/bin/env node
// Run by the owner, OUTSIDE the Bot environment. Never expose the signing key
// to the MCP process. Stdin: {tool, arguments}; stdout: exact signed envelope.
import { readFileSync } from 'node:fs';
import { randomBytes, sign, createPrivateKey, createPublicKey } from 'node:crypto';
import { canonical, digest, createAgency } from '../runtime/agency-tools.mjs';

try {
  const config = JSON.parse(readFileSync(process.env.ITONAMI_AGENCY_CONFIG, 'utf8'));
  const agency = createAgency(config);
  const input = JSON.parse(readFileSync(0, 'utf8'));
  if (!agency.tools.some(t => t.name === input.tool) || input.arguments?.authorization) throw new Error();
  const now = Date.now();
  const claims = { version: 1, bot: config.bot, client: config.client, role: config.role,
    tool: input.tool, argsHash: digest(input.arguments), notBefore: now, expiresAt: now + 15 * 60 * 1000,
    nonce: randomBytes(16).toString('hex') };
  if (process.argv.includes('--prepare')) {
    // Concrete reviewable action: the owner sees the EXACT content and scope.
    process.stdout.write(`${JSON.stringify({ claims, arguments: input.arguments }, null, 2)}\n`);
  } else {
    if (!process.argv.includes('--sign')) throw new Error();
    const privateKey = createPrivateKey(readFileSync(process.env.AGENCY_OWNER_PRIVATE_KEY_FILE));
    if (privateKey.asymmetricKeyType !== 'ed25519' ||
        createPublicKey(privateKey).export({ type: 'spki', format: 'pem' }) !== config.ownerPublicKey) throw new Error();
    const authorization = { claims, signature: sign(null, Buffer.from(canonical(claims)), privateKey).toString('base64') };
    process.stdout.write(`${JSON.stringify({ tool: input.tool, arguments: { ...input.arguments, authorization } }, null, 2)}\n`);
  }
} catch {
  process.stderr.write('Authorization failed. Supply the host configuration, exact action JSON and matching owner Ed25519 key; use --prepare or --sign.\n');
  process.exitCode = 1;
}
