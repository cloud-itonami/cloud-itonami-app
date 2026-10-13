import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, sign, randomBytes } from 'node:crypto';
import { readFileSync, mkdirSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { createAgency, canonical, digest, tools, EVENT_COLLECTION } from '../runtime/agency-tools.mjs';

function fixture(overrides = {}, { missingRecordStatus = 200 } = {}) {
  const { privateKey, publicKey } = generateKeyPairSync('ed25519');
  const config = { ...JSON.parse(readFileSync(new URL('../config/agency-ad-operator.example.json', import.meta.url))),
    enabled: true, client: 'customer-a', ownerPublicKey: publicKey.export({ type: 'spki', format: 'pem' }), ...overrides };
  const env = { AGENCY_AD_MAIL_TOKEN: 'fixture-mail', AGENCY_AD_AOZORA_TOKEN: 'fixture-pds', AGENCY_AD_EVENT_KEY: randomBytes(32).toString('hex') };
  const now = 1800000000000;
  const records = new Map(), emails = new Map(), submissions = new Map(), calls = [];
  let emailState = 'email-1', submissionState = 'submission-1', writes = 0;
  function approved(tool, arguments_, claimsOverride = {}) {
    const claims = { version: 1, bot: config.bot, client: config.client, role: config.role, tool,
      argsHash: digest(arguments_), notBefore: now - 1000, expiresAt: now + 10000, nonce: randomBytes(16).toString('hex'), ...claimsOverride };
    return { ...arguments_, authorization: { claims, signature: sign(null, Buffer.from(canonical(claims)), privateKey).toString('base64') } };
  }
  const response = (data, status = 200) => new Response(JSON.stringify(data), { status });
  async function fetch(urlValue, options) {
    const url = new URL(urlValue), body = options.body ? JSON.parse(options.body) : undefined;
    calls.push({ url: url.href, body, options });
    if (url.hostname === 'mail.example.invalid') {
      const [method, args, tag] = body.methodCalls[0];
      assert.equal(args.accountId, config.mail.accountId);
      const out = { accountId: args.accountId, state: emailState };
      switch (method) {
        case 'Identity/get': out.list = [{ id: config.mail.identityId, email: config.mail.from }]; break;
        case 'Email/query': assert.equal(args.filter.inMailbox, config.mail.mailboxId); out.ids = [...emails.keys()]; out.queryState = 'query-1'; break;
        case 'Email/get': out.list = args.ids.map(id => emails.get(id)).filter(Boolean); break;
        case 'Email/set':
          if (args.ifInState !== emailState) return response({ methodResponses: [['error', { type: 'stateMismatch' }, tag]] });
          emails.set('draft-1', { id: 'draft-1', ...args.create.draft });
          out.created = { draft: { id: 'draft-1' } }; out.newState = emailState = 'email-2'; writes++; break;
        case 'EmailSubmission/get': out.list = args.ids.map(id => submissions.get(id)).filter(Boolean); out.state = submissionState; break;
        case 'EmailSubmission/query': out.ids = [...submissions.values()].filter(s => args.filter.emailIds.includes(s.emailId)).map(s => s.id); out.total = out.ids.length; break;
        case 'EmailSubmission/set':
          if (args.ifInState !== submissionState) return response({ methodResponses: [['error', { type: 'stateMismatch' }, tag]] });
          submissions.set('send-1', { id: 'send-1', ...args.create.send, undoStatus: 'final' });
          out.created = { send: { id: 'send-1' } }; out.newState = submissionState = 'submission-2'; writes++; break;
        default: assert.fail(`Unexpected JMAP method ${method}`);
      }
      return response({ methodResponses: [[method, out, tag]] });
    }
    assert.equal(url.hostname, 'aozora.app');
    if (options.method === 'GET') assert.equal(options.headers.Authorization, undefined, 'public aozora reads must not carry a write Biscuit');
    else assert.equal(options.headers.Authorization, `Bearer ${env.AGENCY_AD_AOZORA_TOKEN}`);
    if (url.pathname.endsWith('getRecord')) {
      const row = records.get(`${url.searchParams.get('collection')}/${url.searchParams.get('rkey')}`);
      return row ? response(row) : response({ error: 'RecordNotFound' }, missingRecordStatus);
    }
    if (url.pathname.endsWith('createRecord')) {
      const row = { uri: `at://${body.repo}/${body.collection}/${body.rkey}`, cid: `cid-${digest(body.record)}`, value: body.record };
      records.set(`${body.collection}/${body.rkey}`, row); writes++;
      return response({ uri: row.uri, cid: row.cid });
    }
    if (url.pathname.endsWith('listRecords')) return response({ records: [...records.values()].filter(r => r.value.$type === EVENT_COLLECTION) });
    if (url.pathname.endsWith('getPosts')) return response({ posts: url.searchParams.getAll('uris').map(uri => ({ uri, likeCount: 3 })) });
    assert.fail(`Unexpected URL ${url}`);
  }
  const agency = createAgency(config, { env, fetch, now: () => now });
  return { agency, config, env, approved, records, emails, calls, fetch, now, writes: () => writes };
}

test('disabled examples start, expose status, and perform no channel calls', async () => {
  for (const role of ['ad-operator', 'sales-agent']) {
    const config = JSON.parse(readFileSync(new URL(`../config/agency-${role}.example.json`, import.meta.url)));
    const a = createAgency(config, { env: {}, fetch: () => assert.fail('No network') });
    const status = await a.call('agency_status');
    assert.equal(status.enabled, false);
    assert.equal(status.liveVerified, false);
    await assert.rejects(a.call('agency_mail_list'), /tool_not_granted/);
  }
});

test('exact signed authorization refuses alteration, wrong customer, role, bot, expiry and foreign owner before effects', async () => {
  const f = fixture(), action = { text: '[PR] Example', createdAt: '2027-01-15T08:00:00Z' };
  await assert.rejects(f.agency.call('agency_aozora_publish', { ...f.approved('agency_aozora_publish', action), text: '[PR] Changed' }), /scope_mismatch/);
  for (const claims of [{ client: 'customer-b' }, { bot: 'another-bot' }, { role: 'itonami-sales-agent' }, { expiresAt: f.now }, { notBefore: f.now + 1 }]) {
    await assert.rejects(f.agency.call('agency_aozora_publish', f.approved('agency_aozora_publish', action, claims)), /authorization/);
  }
  const bad = f.approved('agency_aozora_publish', action); bad.authorization.signature = Buffer.alloc(64).toString('base64');
  await assert.rejects(f.agency.call('agency_aozora_publish', bad), /invalid_owner_signature/);
  assert.equal(f.calls.length, 0);
});

test('sales role cannot be widened into publishing; no shell, wallet, credential or account-management tools', () => {
  const f = fixture();
  assert.throws(() => createAgency({ ...f.config, role: 'itonami-sales-agent' }), /tool_outside_role/);
  assert.ok(tools.every(t => !/shell|wallet|credential|account_create|delete/.test(t.name)));
  assert.throws(() => createAgency({ ...f.config, mail: { ...f.config.mail, apiUrl: 'http://mail.invalid' } }), /https_endpoint/);
});

test('advertising publish requires PR, is idempotent across fresh hosts, and verifies remote content', async () => {
  const f = fixture();
  await assert.rejects(f.agency.call('agency_aozora_publish', f.approved('agency_aozora_publish', { text: 'Undisclosed', createdAt: '2027-01-15T08:00:00Z' })), /disclosure/);
  const args = f.approved('agency_aozora_publish', { text: '[PR] Sponsor example', createdAt: '2027-01-15T08:00:00Z' });
  const first = await f.agency.call('agency_aozora_publish', args);
  const restarted = createAgency(f.config, { env: f.env, fetch: f.fetch, now: () => f.now });
  assert.equal((await restarted.call('agency_aozora_publish', args)).uri, first.uri);
  assert.equal(f.writes(), 1);
  assert.equal(first.status, 'verified_record');
});

test('aozora named missing-record errors work under 200/400/404, but an unavailable API never permits a write', async () => {
  for (const missingRecordStatus of [200, 400, 404]) {
    const f = fixture({}, { missingRecordStatus });
    const args = f.approved('agency_aozora_publish', { text: '[PR] Compatibility', createdAt: '2027-01-15T08:00:00Z' });
    assert.equal((await f.agency.call('agency_aozora_publish', args)).status, 'verified_record');
  }
  for (const status of [200, 404]) {
    const f = fixture(); let writes = 0;
    const a = createAgency(f.config, { env: f.env, now: () => f.now, fetch: (_, options) => {
      if (options.method === 'POST') writes++;
      return new Response(JSON.stringify({ error: 'MethodNotImplemented' }), { status });
    } });
    await assert.rejects(a.call('agency_aozora_publish', f.approved('agency_aozora_publish', { text: '[PR] Missing API', createdAt: '2027-01-15T08:00:00Z' })), status === 200 ? /aozora_method_not_implemented/ : /channel_http_404/);
    assert.equal(writes, 0);
  }
});

test('private records encrypt lead data, recover on another host, detect tampering and never accept another customer', async () => {
  const f = fixture(), action = { kind: 'lead', data: { recipient: 'lead@example.invalid', stage: 'qualified' }, parents: [], createdAt: '2027-01-15T08:00:00Z' };
  const args = f.approved('agency_event_append', action);
  const first = await f.agency.call('agency_event_append', args);
  assert.ok(!JSON.stringify([...f.records.values()]).includes('lead@example.invalid'));
  const a = createAgency(f.config, { env: f.env, fetch: f.fetch, now: () => f.now });
  await a.call('agency_event_append', args);
  assert.equal(f.writes(), 1);
  const read = await a.call('agency_events_read');
  assert.equal(read.events[0].id, first.eventId); assert.deepEqual(read.events[0].data, action.data);
  assert.equal(read.historyComplete, true);
  const readOnly = createAgency({ ...f.config, allowedTools: ['agency_events_read'] }, { env: f.env, fetch: f.fetch, now: () => f.now });
  assert.equal((await readOnly.call('agency_events_read')).events.length, 1);
  await assert.rejects(readOnly.call('agency_event_append', args), /tool_not_granted/);
  const other = createAgency({ ...f.config, client: 'customer-b' }, { env: f.env, fetch: f.fetch });
  await assert.rejects(other.call('agency_events_read'), /event_client_mismatch/);
  const row = [...f.records.values()][0]; row.value.tag = Buffer.alloc(16).toString('base64');
  await assert.rejects(a.call('agency_events_read'));
});

test('forks and pagination cannot masquerade as a single complete history', async () => {
  const f = fixture(), parent = 'a'.repeat(64);
  for (const stage of ['contacted', 'meeting']) await f.agency.call('agency_event_append', f.approved('agency_event_append', { kind: 'lead', data: { stage }, parents: [parent], createdAt: '2027-01-15T08:00:00Z' }));
  const history = await f.agency.call('agency_events_read');
  assert.deepEqual(history.missingParents, [parent]); assert.equal(history.branches.length, 1);
  assert.equal((await f.agency.call('agency_events_read', { cursor: 'page-2' })).historyComplete, false);
});

test('draft/read/send uses the owned mailbox and sender, survives two-host contention with one submission', async () => {
  const f = fixture();
  const context = await f.agency.call('agency_mail_context');
  const draft = await f.agency.call('agency_mail_draft', f.approved('agency_mail_draft', { to: 'lead@example.invalid', subject: 'Proposal', text: 'Approved body', emailState: context.emailState }));
  assert.equal(draft.status, 'draft_verified');
  assert.deepEqual((await f.agency.call('agency_mail_list')).ids, ['draft-1']);
  const args = f.approved('agency_mail_send', { emailId: draft.email.id, emailHash: draft.emailHash, submissionState: context.submissionState });
  const otherNode = createAgency(f.config, { env: f.env, fetch: f.fetch, now: () => f.now });
  const results = await Promise.allSettled([f.agency.call('agency_mail_send', args), otherNode.call('agency_mail_send', args)]);
  assert.equal(results.filter(r => r.status === 'fulfilled').length, 1);
  const result = results.find(r => r.status === 'fulfilled').value;
  assert.equal(result.status, 'submission_verified'); assert.equal(result.delivered, false);
  assert.equal(f.writes(), 2); // one draft, one submission
  const receipts = await f.agency.call('agency_mail_submissions', { emailId: draft.email.id });
  assert.equal(receipts.submissions.length, 1);
  const foreign = { ...f.emails.get('draft-1'), id: 'foreign', mailboxIds: { elsewhere: true } }; f.emails.set('foreign', foreign);
  await assert.rejects(f.agency.call('agency_mail_read', { id: 'foreign' }), /not_owned/);
});

test('suppression and content tampering refuse send before submission', async () => {
  const f = fixture();
  const draft = await f.agency.call('agency_mail_draft', f.approved('agency_mail_draft', { to: 'lead@example.invalid', subject: 'Proposal', text: 'Approved', emailState: 'email-1' }));
  const args = f.approved('agency_mail_send', { emailId: draft.email.id, emailHash: draft.emailHash, submissionState: 'submission-1' });
  f.emails.get('draft-1').subject = 'Changed';
  await assert.rejects(f.agency.call('agency_mail_send', args), /draft_changed/);
  f.emails.get('draft-1').subject = 'Proposal';
  await f.agency.call('agency_event_append', f.approved('agency_event_append', { kind: 'suppression', data: { recipient: 'lead@example.invalid' }, parents: [], createdAt: '2027-01-15T08:00:00Z' }));
  await assert.rejects(f.agency.call('agency_mail_send', args), /recipient_suppressed/);
  assert.equal(f.calls.filter(c => c.body?.methodCalls?.[0]?.[0] === 'EmailSubmission/set').length, 0);
});

test('reply drafts preserve the original owned Message-ID and stale draft state is refused', async () => {
  const f = fixture();
  f.emails.set('original', { id: 'original', mailboxIds: { [f.config.mail.mailboxId]: true }, messageId: ['original@example.invalid'] });
  const args = f.approved('agency_mail_draft', { to: 'lead@example.invalid', subject: 'Re: Proposal', text: 'Reply', emailState: 'email-1', replyToEmailId: 'original' });
  const draft = await f.agency.call('agency_mail_draft', args);
  assert.deepEqual(draft.email.inReplyTo, ['original@example.invalid']);
  await assert.rejects(f.agency.call('agency_mail_draft', args), /mail_state_mismatch/);
});

test('metrics preserve unknown counters and do not invent impressions or attribution', async () => {
  const f = fixture(), uri = `at://${f.config.aozora.did}/app.bsky.feed.post/example`;
  const metrics = await f.agency.call('agency_aozora_metrics', { uris: [uri] });
  assert.equal(metrics.posts[0].likeCount, 3); assert.equal(metrics.posts[0].replyCount, null);
  for (const field of ['impressions', 'clicks', 'conversions', 'revenue']) assert.equal(metrics[field], null);
  await assert.rejects(f.agency.call('agency_aozora_metrics', { uris: ['at://did:key:other/app.bsky.feed.post/example'] }), /not_owned/);
});

test('transport failures do not retry or leak tokens', async () => {
  const f = fixture(); let calls = 0;
  const a = createAgency(f.config, { env: f.env, now: () => f.now, fetch: () => { calls++; throw new Error('private-secret'); } });
  await assert.rejects(a.call('agency_mail_list'), /transport_outcome_unknown_do_not_retry_writes/);
  assert.equal(calls, 1);
});

test('actual stdio launcher initializes, lists role tools, returns status and rejects writes without credentials', () => {
  const temporaryRoot = new URL('../target/agency-tests/', import.meta.url).pathname;
  mkdirSync(temporaryRoot, { recursive: true });
  const directory = mkdtempSync(join(temporaryRoot, 'stdio-'));
  try {
    const config = JSON.parse(readFileSync(new URL('../config/agency-sales-agent.example.json', import.meta.url)));
    const filename = join(directory, 'config.json'); writeFileSync(filename, JSON.stringify(config));
    const messages = [
      { jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-11-25' } },
      { jsonrpc: '2.0', method: 'notifications/initialized' },
      { jsonrpc: '2.0', id: 2, method: 'tools/list' },
      { jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'agency_status' } },
      { jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'agency_mail_send', arguments: {} } }
    ];
    const child = spawnSync(process.execPath, [new URL('../bin/itonami-agency-mcp.mjs', import.meta.url).pathname], {
      env: { ITONAMI_AGENCY_CONFIG: filename }, input: `${messages.map(JSON.stringify).join('\n')}\n`, encoding: 'utf8'
    });
    assert.equal(child.status, 0, child.stderr);
    const output = child.stdout.trim().split('\n').map(JSON.parse);
    assert.equal(output.length, 4);
    assert.equal(output[0].result.serverInfo.name, 'itonami-agency');
    assert.ok(!output[1].result.tools.some(t => t.name === 'agency_aozora_publish'));
    assert.equal(JSON.parse(output[2].result.content[0].text).enabled, false);
    assert.equal(output[3].result.isError, true);
    const bootstrap = spawnSync(process.execPath, [new URL('../bin/itonami-agency-mcp.mjs', import.meta.url).pathname], {
      env: { ITONAMI_AGENCY_ROLE: 'itonami-sales-agent' }, input: `${JSON.stringify(messages[2])}\n`, encoding: 'utf8'
    });
    assert.equal(bootstrap.status, 0, bootstrap.stderr);
    assert.equal(JSON.parse(bootstrap.stdout).result.tools.length, config.allowedTools.length);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('owner CLI prepares and signs an exact action; the signature works without giving the worker the issuer key', async () => {
  const f = fixture();
  const { privateKey, publicKey } = generateKeyPairSync('ed25519');
  const config = { ...f.config, ownerPublicKey: publicKey.export({ type: 'spki', format: 'pem' }) };
  const temporaryRoot = new URL('../target/agency-tests/', import.meta.url).pathname;
  mkdirSync(temporaryRoot, { recursive: true });
  const directory = mkdtempSync(join(temporaryRoot, 'issuer-'));
  try {
    const configFile = join(directory, 'config.json'), keyFile = join(directory, 'issuer.pem');
    writeFileSync(configFile, JSON.stringify(config));
    writeFileSync(keyFile, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
    const input = { tool: 'agency_aozora_publish', arguments: { text: '[PR] CLI-approved content', createdAt: '2027-01-15T08:00:00Z' } };
    const run = mode => spawnSync(process.execPath, [new URL('../scripts/agency-authorize.mjs', import.meta.url).pathname, mode], {
      env: { ITONAMI_AGENCY_CONFIG: configFile, AGENCY_OWNER_PRIVATE_KEY_FILE: keyFile }, input: JSON.stringify(input), encoding: 'utf8'
    });
    const prepared = run('--prepare'); assert.equal(prepared.status, 0, prepared.stderr);
    assert.deepEqual(JSON.parse(prepared.stdout).arguments, input.arguments);
    const signed = run('--sign'); assert.equal(signed.status, 0, signed.stderr);
    assert.ok(!signed.stdout.includes('PRIVATE KEY'));
    const action = JSON.parse(signed.stdout);
    const worker = createAgency(config, { env: f.env, fetch: f.fetch });
    assert.equal((await worker.call(action.tool, action.arguments)).status, 'verified_record');
    await assert.rejects(worker.call(action.tool, { ...action.arguments, text: '[PR] Altered' }), /scope_mismatch/);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
