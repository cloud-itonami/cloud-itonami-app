'use strict';
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const {webcrypto} = require('node:crypto');

const source = fs.readFileSync('resources/cloud/itonami/app/signal.js', 'utf8');
const registry = new Map();

const register = (principal, request) => {
  const devices = registry.get(principal) || new Map();
  const previous = devices.get(request['device-id']) || {consumed:new Set()};
  devices.set(request['device-id'], {...request, consumed:previous.consumed});
  registry.set(principal, devices);
  return {'device-id':request['device-id']};
};
const directory = (principal) => ({principal, devices:[...(registry.get(principal) || new Map()).values()].map((d) => ({
  id:d['device-id'], 'identity-signing-key':d['identity-signing-key'],
  'identity-key':d['identity-key'], 'identity-version':1,
  'signed-prekey-id':d['signed-prekey-id'], 'signed-prekey':d['signed-prekey'],
  'signed-prekey-signature':d['signed-prekey-signature'],
  'one-time-prekey-count':d['one-time-prekeys'].filter((p) => !d.consumed.has(p.id)).length
}))});
const bundles = (principal) => ({principal, bundles:[...(registry.get(principal) || new Map()).values()].map((d) => {
  const prekey = d['one-time-prekeys'].find((p) => !d.consumed.has(p.id));
  if (prekey) d.consumed.add(prekey.id);
  return {principal, 'device-id':d['device-id'],
    'identity-signing-key':d['identity-signing-key'], 'identity-key':d['identity-key'],
    'identity-version':1, 'signed-prekey-id':d['signed-prekey-id'],
    'signed-prekey':d['signed-prekey'], 'signed-prekey-signature':d['signed-prekey-signature'],
    ...(prekey ? {'one-time-prekey-id':prekey.id, 'one-time-prekey':prekey.key} : {})};
})});

const makeClient = (principal) => {
  const memory = new Map();
  const context = {console, crypto:webcrypto, indexedDB:{}, TextEncoder, TextDecoder,
    Uint8Array, ArrayBuffer, structuredClone,
    btoa:(value) => Buffer.from(value, 'binary').toString('base64'),
    atob:(value) => Buffer.from(value, 'base64').toString('binary')};
  context.window = context; context.globalThis = context;
  vm.createContext(context); vm.runInContext(source, context, {filename:'signal.js'});
  const postJSON = async (path, body) => {
    if (path.endsWith('/devices')) return register(principal, body);
    if (path.endsWith('/device-directory')) return directory(body.principal);
    if (path.endsWith('/prekey-bundles')) return bundles(body.principal);
    throw new Error(`unexpected mock path: ${path}`);
  };
  context.ItonamiSignal.configure({principal, prefix:'/api/messenger', postJSON,
    storage:{get:(key) => memory.get(key), put:(key, value) => memory.set(key, value),
      delete:(key) => memory.delete(key)}});
  return context.ItonamiSignal;
};

(async () => {
  const alice = makeClient('human:alice');
  const bob = makeClient('human:bob');
  const carol = makeClient('human:carol');
  await alice.initialize(); await bob.initialize(); await carol.initialize();
  await alice.verifyPrincipal('human:bob', async () => true);
  await bob.verifyPrincipal('human:alice', async () => true);

  const direct = {id:'conversation-direct', kind:'direct', members:['human:alice', 'human:bob']};
  const first = await alice.encryptConversation({conversation:direct, plaintext:'hello bob'});
  assert.equal(await bob.decryptEnvelope({sealed:first, sender:'human:alice', conversationId:direct.id}), 'hello bob');
  const reply = await bob.encryptConversation({conversation:direct, plaintext:'hello alice'});
  assert.equal(await alice.decryptEnvelope({sealed:reply, sender:'human:bob', conversationId:direct.id}), 'hello alice');

  await alice.verifyPrincipal('human:carol', async () => true);
  await carol.verifyPrincipal('human:alice', async () => true);
  const group = {id:'conversation-group', kind:'group',
    members:['human:alice', 'human:bob', 'human:carol']};
  const groupEnvelope = await alice.encryptConversation({conversation:group, plaintext:'hello group'});
  const parsed = JSON.parse(groupEnvelope);
  assert.equal(parsed.kind, 'group');
  assert.ok(parsed.group.epoch);
  assert.ok(parsed.group.membershipHash);
  assert.equal(await bob.decryptEnvelope({sealed:groupEnvelope, sender:'human:alice', conversationId:group.id, conversation:group}), 'hello group');
  assert.equal(await carol.decryptEnvelope({sealed:groupEnvelope, sender:'human:alice', conversationId:group.id, conversation:group}), 'hello group');
  process.stdout.write('signal browser direct ratchet + group sender-key: ok\n');
})().catch((error) => { console.error(error); process.exitCode = 1; });
