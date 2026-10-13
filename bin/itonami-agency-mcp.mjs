#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { createAgency } from '../src/cloud/itonami/app/agency_tools.mjs';

// One process serves one Bot/client. No ambient app store, account discovery,
// browser cookies, shell tool, credentials endpoint or key-generation tool.
let agency;
try {
  const role = process.env.ITONAMI_AGENCY_ROLE;
  const examples = { 'itonami-ad-operator': 'agency-ad-operator', 'itonami-sales-agent': 'agency-sales-agent' };
  const path = process.env.ITONAMI_AGENCY_CONFIG ?? (examples[role] ? new URL(`../config/${examples[role]}.example.json`, import.meta.url) : null);
  if (!path) throw new Error('configuration_required');
  const configuration = JSON.parse(readFileSync(path, 'utf8'));
  if (role && configuration.role !== role) throw new Error('profile_role_mismatch');
  agency = createAgency(configuration);
} catch {
  process.stderr.write('Agency configuration is missing or invalid. See docs/agency-bots.md.\n');
  process.exit(1);
}
const lineReader = createInterface({ input: process.stdin, crlfDelay: Infinity });
function reply(id, result, error) {
  process.stdout.write(`${JSON.stringify({ jsonrpc: '2.0', id, ...(error ? { error } : { result }) })}\n`);
}
for await (const line of lineReader) {
  if (Buffer.byteLength(line) > 65536) { reply(null, null, { code: -32600, message: 'Request too large' }); continue; }
  let message;
  try { message = JSON.parse(line); } catch { reply(null, null, { code: -32700, message: 'Parse error' }); continue; }
  if (!message || typeof message !== 'object' || Array.isArray(message)) {
    reply(null, null, { code: -32600, message: 'Invalid request' }); continue;
  }
  if (message.id === undefined) continue;
  if (message.jsonrpc !== '2.0') { reply(message.id, null, { code: -32600, message: 'Invalid request' }); continue; }
  switch (message.method) {
    case 'initialize':
      if (!['2024-11-05', '2025-03-26', '2025-06-18', '2025-11-25'].includes(message.params?.protocolVersion)) {
        reply(message.id, null, { code: -32602, message: 'Unsupported protocol version' }); break;
      }
      reply(message.id, { protocolVersion: message.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: 'itonami-agency', version: '1.0.0' } }); break;
    case 'ping': reply(message.id, {}); break;
    case 'tools/list': reply(message.id, { tools: agency.tools }); break;
    case 'tools/call':
      try {
        const result = await agency.call(message.params?.name, message.params?.arguments ?? {});
        reply(message.id, { content: [{ type: 'text', text: JSON.stringify(result) }], isError: false });
      } catch (error) {
        // Only our machine-readable refusal codes are returned; library error
        // text and provider responses may contain secrets or private content.
        const code = /^[a-z][a-z0-9_]{0,100}$/.test(error.message) ? error.message : 'agency_operation_failed';
        reply(message.id, { content: [{ type: 'text', text: code }], isError: true });
      }
      break;
    default: reply(message.id, null, { code: -32601, message: 'Method not found' });
  }
}
