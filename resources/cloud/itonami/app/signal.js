/* Cloud Itonami browser Signal transport.
 *
 * Private CryptoKeys and ratchet state stay in IndexedDB. The server receives
 * public prekey bundles and opaque envelopes only. X25519/Ed25519 support is
 * feature-detected: unsupported browsers fail closed instead of falling back
 * to a weaker algorithm under the Signal label.
 */
(() => {
  'use strict';
  const PROTOCOL = 'itonami-signal-v1';
  const DB_NAME = 'cloud-itonami-signal-v1';
  const STORE = 'keys';
  const MAX_SKIP = 100;
  const enc = new TextEncoder();
  const dec = new TextDecoder();
  let api = null;

  const bytes = (value) => value instanceof Uint8Array ? value
    : value instanceof ArrayBuffer ? new Uint8Array(value)
    : ArrayBuffer.isView(value) ? new Uint8Array(value.buffer, value.byteOffset, value.byteLength)
    : new Uint8Array(value || []);
  const concat = (...parts) => {
    const values = parts.map(bytes); const out = new Uint8Array(values.reduce((n, x) => n + x.length, 0));
    let offset = 0; values.forEach((x) => { out.set(x, offset); offset += x.length; }); return out;
  };
  const b64 = (value) => {
    let binary = ''; bytes(value).forEach((x) => { binary += String.fromCharCode(x); });
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  };
  const unb64 = (value) => {
    const padded = String(value).replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(value.length / 4) * 4, '=');
    const binary = atob(padded); return Uint8Array.from(binary, (x) => x.charCodeAt(0));
  };
  const hex = (value) => [...bytes(value)].map((x) => x.toString(16).padStart(2, '0')).join('');
  const canonical = (value) => Array.isArray(value) ? value.map(canonical)
    : value && typeof value === 'object'
      ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])])) : value;
  const stable = (value) => JSON.stringify(canonical(value));
  const random = (length) => crypto.getRandomValues(new Uint8Array(length));
  const uuid = () => crypto.randomUUID ? crypto.randomUUID() : b64(random(18));

  const openDB = () => new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE);
    request.onerror = () => reject(request.error);
    request.onsuccess = () => resolve(request.result);
  });
  const dbGet = async (key) => {
    if (api?.storage) return api.storage.get(key);
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const request = db.transaction(STORE).objectStore(STORE).get(key);
      request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
    }).finally(() => db.close());
  };
  const dbPut = async (key, value) => {
    if (api?.storage) { await api.storage.put(key, value); return; }
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, 'readwrite'); tx.objectStore(STORE).put(value, key);
      tx.oncomplete = resolve; tx.onerror = () => reject(tx.error);
    }).finally(() => db.close());
  };
  const dbDelete = async (key) => {
    if (api?.storage) { await api.storage.delete(key); return; }
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, 'readwrite'); tx.objectStore(STORE).delete(key);
      tx.oncomplete = resolve; tx.onerror = () => reject(tx.error);
    }).finally(() => db.close());
  };

  const exportRaw = async (key) => new Uint8Array(await crypto.subtle.exportKey('raw', key));
  const importX25519 = (raw) => crypto.subtle.importKey('raw', bytes(raw), {name:'X25519'}, false, []);
  const sha256 = async (value) => new Uint8Array(await crypto.subtle.digest('SHA-256', bytes(value)));
  const hmac = async (key, value) => {
    const imported = await crypto.subtle.importKey('raw', bytes(key), {name:'HMAC', hash:'SHA-256'}, false, ['sign']);
    return new Uint8Array(await crypto.subtle.sign('HMAC', imported, bytes(value)));
  };
  const hkdf = async (ikm, salt, info, length = 64) => {
    const key = await crypto.subtle.importKey('raw', bytes(ikm), 'HKDF', false, ['deriveBits']);
    return new Uint8Array(await crypto.subtle.deriveBits(
      {name:'HKDF', hash:'SHA-256', salt:bytes(salt), info:enc.encode(info)}, key, length * 8));
  };
  const dh = async (privateKey, publicRaw) => new Uint8Array(await crypto.subtle.deriveBits(
    {name:'X25519', public:await importX25519(publicRaw)}, privateKey, 256));
  const kdfRK = async (root, output) => {
    const material = await hkdf(output, root, `${PROTOCOL}/root`, 64);
    return {root:material.slice(0, 32), chain:material.slice(32)};
  };
  const kdfCK = async (chain) => ({message:await hmac(chain, new Uint8Array([1])),
                                   next:await hmac(chain, new Uint8Array([2]))});
  const aesEncrypt = async (keyBytes, plaintext, aad) => {
    const key = await crypto.subtle.importKey('raw', bytes(keyBytes), {name:'AES-GCM'}, false, ['encrypt']);
    const iv = random(12); const ciphertext = await crypto.subtle.encrypt(
      {name:'AES-GCM', iv, additionalData:enc.encode(aad), tagLength:128}, key, enc.encode(plaintext));
    return {iv:b64(iv), ciphertext:b64(ciphertext)};
  };
  const aesDecrypt = async (keyBytes, sealed, aad) => {
    const key = await crypto.subtle.importKey('raw', bytes(keyBytes), {name:'AES-GCM'}, false, ['decrypt']);
    const plaintext = await crypto.subtle.decrypt(
      {name:'AES-GCM', iv:unb64(sealed.iv), additionalData:enc.encode(aad), tagLength:128},
      key, unb64(sealed.ciphertext));
    return dec.decode(plaintext);
  };

  const supported = async () => {
    if (!globalThis.crypto?.subtle || !globalThis.indexedDB) return false;
    try {
      const x = await crypto.subtle.generateKey({name:'X25519'}, false, ['deriveBits']);
      const e = await crypto.subtle.generateKey({name:'Ed25519'}, false, ['sign', 'verify']);
      return Boolean(x.privateKey && e.privateKey);
    } catch (_) { return false; }
  };
  const deviceKey = () => `device:${api.principal}`;
  const sessionKey = (principal, deviceId) => `session:${api.principal}:${principal}:${deviceId}`;
  const verifiedKey = (principal, deviceId) => `verified:${api.principal}:${principal}:${deviceId}`;
  const groupSendKey = (conversationId) => `group-send:${api.principal}:${conversationId}`;
  const groupRecvKey = (conversationId, sender, deviceId, epoch) =>
    `group-recv:${api.principal}:${conversationId}:${sender}:${deviceId}:${epoch}`;

  const publicDevice = async (device) => ({
    'device-id':device.id,
    'identity-signing-key':b64(await exportRaw(device.signing.publicKey)),
    'identity-key':b64(await exportRaw(device.identity.publicKey)),
    'signed-prekey-id':device.signedPrekey.id,
    'signed-prekey':b64(await exportRaw(device.signedPrekey.keyPair.publicKey)),
    'signed-prekey-signature':b64(device.signedPrekey.signature),
    'one-time-prekeys':await Promise.all(device.oneTime.map(async (item) =>
      ({id:item.id, key:b64(await exportRaw(item.keyPair.publicKey))})))
  });
  const createDevice = async () => {
    const signing = await crypto.subtle.generateKey({name:'Ed25519'}, false, ['sign', 'verify']);
    const identity = await crypto.subtle.generateKey({name:'X25519'}, false, ['deriveBits']);
    const signedPair = await crypto.subtle.generateKey({name:'X25519'}, false, ['deriveBits']);
    const signedRaw = await exportRaw(signedPair.publicKey);
    const signature = new Uint8Array(await crypto.subtle.sign('Ed25519', signing.privateKey, signedRaw));
    const oneTime = [];
    for (let id = 1; id <= 20; id += 1) {
      oneTime.push({id, keyPair:await crypto.subtle.generateKey({name:'X25519'}, false, ['deriveBits'])});
    }
    return {id:`browser-${uuid()}`, signing, identity,
      signedPrekey:{id:1, keyPair:signedPair, signature}, oneTime, createdAt:new Date().toISOString()};
  };
  const ensureDevice = async () => {
    let device = await dbGet(deviceKey());
    if (!device) { device = await createDevice(); await dbPut(deviceKey(), device); }
    await api.postJSON(`${api.prefix}/devices`, await publicDevice(device), true);
    return device;
  };
  const fingerprintFor = async (device) => {
    const digest = await sha256(concat(unb64(device['identity-signing-key']), unb64(device['identity-key'])));
    return hex(digest).match(/.{1,4}/g).join(' ');
  };
  const verified = async (principal, device) => {
    const fingerprint = await fingerprintFor(device);
    return (await dbGet(verifiedKey(principal, device.id || device['device-id']))) === fingerprint;
  };
  const verifyPrincipal = async (principal, approve) => {
    const directory = await api.postJSON(`${api.prefix}/device-directory`, {principal}, true);
    if (!directory.devices?.length) throw new Error(`${principal} はSignal端末を登録していません。`);
    for (const device of directory.devices) {
      const fingerprint = await fingerprintFor(device);
      const old = await dbGet(verifiedKey(principal, device.id));
      if (old !== fingerprint) {
        const accepted = await approve({principal, deviceId:device.id, fingerprint, changed:Boolean(old)});
        if (!accepted) throw new Error(`${principal} / ${device.id} の端末確認を中止しました。`);
        await dbPut(verifiedKey(principal, device.id), fingerprint);
        await dbDelete(sessionKey(principal, device.id));
      }
    }
    return directory.devices.length;
  };

  const verifyBundleSignature = async (bundle) => {
    const key = await crypto.subtle.importKey('raw', unb64(bundle['identity-signing-key']),
      {name:'Ed25519'}, false, ['verify']);
    const ok = await crypto.subtle.verify('Ed25519', key,
      unb64(bundle['signed-prekey-signature']), unb64(bundle['signed-prekey']));
    if (!ok) throw new Error('signed prekey の署名が一致しません。');
    if (!(await verified(bundle.principal, bundle))) {
      throw new Error(`${bundle.principal} / ${bundle['device-id']} の端末鍵が未確認です。`);
    }
  };
  const x3dhSender = async (device, bundle) => {
    await verifyBundleSignature(bundle);
    const ephemeral = await crypto.subtle.generateKey({name:'X25519'}, false, ['deriveBits']);
    const pieces = [
      await dh(device.identity.privateKey, unb64(bundle['signed-prekey'])),
      await dh(ephemeral.privateKey, unb64(bundle['identity-key'])),
      await dh(ephemeral.privateKey, unb64(bundle['signed-prekey']))
    ];
    if (bundle['one-time-prekey']) pieces.push(await dh(ephemeral.privateKey, unb64(bundle['one-time-prekey'])));
    const secret = await hkdf(concat(...pieces), new Uint8Array(32), `${PROTOCOL}/x3dh`, 32);
    return {secret, ephemeral, prekey:{
      senderIdentityKey:b64(await exportRaw(device.identity.publicKey)),
      senderSigningKey:b64(await exportRaw(device.signing.publicKey)),
      ephemeralKey:b64(await exportRaw(ephemeral.publicKey)),
      signedPrekeyId:bundle['signed-prekey-id'], oneTimePrekeyId:bundle['one-time-prekey-id'] ?? null
    }};
  };
  const x3dhReceiver = async (device, prekey) => {
    const signed = device.signedPrekey;
    if (signed.id !== prekey.signedPrekeyId) throw new Error('signed prekey はこの端末に存在しません。');
    const pieces = [
      await dh(signed.keyPair.privateKey, unb64(prekey.senderIdentityKey)),
      await dh(device.identity.privateKey, unb64(prekey.ephemeralKey)),
      await dh(signed.keyPair.privateKey, unb64(prekey.ephemeralKey))
    ];
    if (prekey.oneTimePrekeyId !== null && prekey.oneTimePrekeyId !== undefined) {
      const oneTime = device.oneTime.find((x) => x.id === prekey.oneTimePrekeyId);
      if (!oneTime) throw new Error('one-time prekey は使用済み、またはこの端末に存在しません。');
      pieces.push(await dh(oneTime.keyPair.privateKey, unb64(prekey.ephemeralKey)));
      device.oneTime = device.oneTime.filter((x) => x.id !== prekey.oneTimePrekeyId);
      await dbPut(deviceKey(), device);
    }
    return hkdf(concat(...pieces), new Uint8Array(32), `${PROTOCOL}/x3dh`, 32);
  };

  const ratchetEncrypt = async (state, plaintext, base) => {
    if (!state.CKs) throw new Error('Signal sending chain がありません。');
    const {message, next} = await kdfCK(state.CKs); const n = state.Ns;
    const header = {dh:b64(state.DHsPublic), pn:state.PN, n};
    const aad = stable({...base, header}); const sealed = await aesEncrypt(message, plaintext, aad);
    state.CKs = next; state.Ns += 1; return {header, ...sealed};
  };
  const skipKeys = async (state, until) => {
    if (until - state.Nr > MAX_SKIP) throw new Error('Signal skipped-key 上限を超えました。');
    while (state.CKr && state.Nr < until) {
      const {message, next} = await kdfCK(state.CKr);
      state.skipped[`${b64(state.DHR)}:${state.Nr}`] = b64(message); state.CKr = next; state.Nr += 1;
    }
  };
  const dhRatchet = async (state, header) => {
    await skipKeys(state, header.pn); state.PN = state.Ns; state.Ns = 0; state.Nr = 0;
    state.DHR = unb64(header.dh);
    let out = await kdfRK(state.RK, await dh(state.DHsPrivate, state.DHR));
    state.RK = out.root; state.CKr = out.chain;
    const pair = await crypto.subtle.generateKey({name:'X25519'}, false, ['deriveBits']);
    state.DHsPrivate = pair.privateKey; state.DHsPublic = await exportRaw(pair.publicKey);
    out = await kdfRK(state.RK, await dh(state.DHsPrivate, state.DHR));
    state.RK = out.root; state.CKs = out.chain;
  };
  const ratchetDecrypt = async (state, sealed, base) => {
    const skippedId = `${sealed.header.dh}:${sealed.header.n}`;
    if (state.skipped[skippedId]) {
      const key = unb64(state.skipped[skippedId]); delete state.skipped[skippedId];
      return aesDecrypt(key, sealed, stable({...base, header:sealed.header}));
    }
    if (!state.DHR || b64(state.DHR) !== sealed.header.dh) await dhRatchet(state, sealed.header);
    await skipKeys(state, sealed.header.n);
    const {message, next} = await kdfCK(state.CKr); state.CKr = next; state.Nr += 1;
    return aesDecrypt(message, sealed, stable({...base, header:sealed.header}));
  };

  const encryptPacket = async (target, bundle, plaintext, conversationId) => {
    const device = await ensureDevice(); const deviceId = bundle['device-id'];
    const base = {protocol:PROTOCOL, conversationId, sender:api.principal, senderDevice:device.id,
      recipient:target, recipientDevice:deviceId};
    let state = await dbGet(sessionKey(target, deviceId)); let prekey = null;
    if (!state) {
      const init = await x3dhSender(device, bundle);
      const pair = await crypto.subtle.generateKey({name:'X25519'}, false, ['deriveBits']);
      let root = init.secret; const DHR = unb64(bundle['signed-prekey']);
      const derived = await kdfRK(root, await dh(pair.privateKey, DHR)); root = derived.root;
      state = {RK:root, CKs:derived.chain, CKr:null, DHsPrivate:pair.privateKey,
        DHsPublic:await exportRaw(pair.publicKey), DHR, Ns:0, Nr:0, PN:0, skipped:{},
        peerFingerprint:await fingerprintFor(bundle)};
      prekey = init.prekey;
    }
    const ratchet = await ratchetEncrypt(state, plaintext, base);
    await dbPut(sessionKey(target, deviceId), state);
    return {recipient:target, recipientDevice:deviceId, senderDevice:device.id, prekey, ratchet};
  };
  const decryptPacket = async (sender, packet, conversationId) => {
    const device = await ensureDevice();
    if (packet.recipient !== api.principal || packet.recipientDevice !== device.id) return null;
    const base = {protocol:PROTOCOL, conversationId, sender, senderDevice:packet.senderDevice,
      recipient:api.principal, recipientDevice:device.id};
    let state = await dbGet(sessionKey(sender, packet.senderDevice));
    if (!state) {
      if (!packet.prekey) throw new Error('Signal session がなく、prekey messageでもありません。');
      const pseudoBundle = {principal:sender, 'device-id':packet.senderDevice,
        'identity-signing-key':packet.prekey.senderSigningKey,
        'identity-key':packet.prekey.senderIdentityKey};
      if (!(await verified(sender, pseudoBundle))) throw new Error(`${sender} の端末鍵が未確認です。`);
      const secret = await x3dhReceiver(device, packet.prekey);
      state = {RK:secret, CKs:null, CKr:null,
        DHsPrivate:device.signedPrekey.keyPair.privateKey,
        DHsPublic:await exportRaw(device.signedPrekey.keyPair.publicKey), DHR:null,
        Ns:0, Nr:0, PN:0, skipped:{}, peerFingerprint:await fingerprintFor(pseudoBundle)};
    }
    const plaintext = await ratchetDecrypt(state, packet.ratchet, base);
    await dbPut(sessionKey(sender, packet.senderDevice), state); return plaintext;
  };

  const sealSelf = async (device, plaintext, conversationId) => {
    const ephemeral = await crypto.subtle.generateKey({name:'X25519'}, false, ['deriveBits']);
    const shared = await dh(ephemeral.privateKey, await exportRaw(device.identity.publicKey));
    const key = await hkdf(shared, new Uint8Array(32), `${PROTOCOL}/sent-copy`, 32);
    const header = {conversationId, sender:api.principal, deviceId:device.id,
      ephemeral:b64(await exportRaw(ephemeral.publicKey))};
    return {...header, ...(await aesEncrypt(key, plaintext, stable(header)))};
  };
  const openSelf = async (device, sealed) => {
    const shared = await dh(device.identity.privateKey, unb64(sealed.ephemeral));
    const key = await hkdf(shared, new Uint8Array(32), `${PROTOCOL}/sent-copy`, 32);
    const header = {conversationId:sealed.conversationId, sender:sealed.sender,
      deviceId:sealed.deviceId, ephemeral:sealed.ephemeral};
    return aesDecrypt(key, sealed, stable(header));
  };

  const bundlesFor = async (principal) => {
    const directory = await api.postJSON(`${api.prefix}/device-directory`, {principal}, true);
    for (const item of directory.devices || []) {
      if (!(await verified(principal, item))) throw new Error(`${principal} / ${item.id} の端末鍵が未確認です。`);
    }
    const missing = [];
    for (const item of directory.devices || []) {
      if (!(await dbGet(sessionKey(principal, item.id)))) missing.push(item.id);
    }
    const consumed = missing.length
      ? await api.postJSON(`${api.prefix}/prekey-bundles`, {principal}, true) : {bundles:[]};
    const fresh = new Map((consumed.bundles || []).map((x) => [x['device-id'], x]));
    return (directory.devices || []).map((item) => fresh.get(item.id) || {
      principal, 'device-id':item.id, 'identity-signing-key':item['identity-signing-key'],
      'identity-key':item['identity-key'], 'identity-version':item['identity-version'],
      'signed-prekey-id':item['signed-prekey-id'], 'signed-prekey':item['signed-prekey'],
      'signed-prekey-signature':item['signed-prekey-signature']
    });
  };
  const encryptGroup = async (conversation, plaintext, device, recipients) => {
    const membershipHash = b64(await sha256(enc.encode([...conversation.members].sort().join('\n'))));
    let senderState = await dbGet(groupSendKey(conversation.id));
    if (!senderState || senderState.membershipHash !== membershipHash) {
      senderState = {epoch:uuid(), membershipHash, chain:random(32), counter:0};
    }
    const currentChain = bytes(senderState.chain); const counter = senderState.counter;
    const {message, next} = await kdfCK(currentChain);
    const header = {sessionId:`${conversation.id}:${device.id}`, epoch:senderState.epoch,
      membershipHash, senderDevice:device.id, counter};
    const group = {...header, ...(await aesEncrypt(message, plaintext, stable(header)))};
    const distribution = JSON.stringify({type:'sender-key', conversationId:conversation.id,
      epoch:senderState.epoch, membershipHash, senderDevice:device.id,
      counter, chain:b64(currentChain)});
    const packets = [];
    for (const recipient of recipients) {
      const bundles = await bundlesFor(recipient);
      if (!bundles.length) throw new Error(`${recipient} にSignal端末がありません。`);
      for (const bundle of bundles) {
        packets.push(await encryptPacket(recipient, bundle, distribution, conversation.id));
      }
    }
    senderState.chain = next; senderState.counter += 1;
    await dbPut(groupSendKey(conversation.id), senderState);
    return {group, packets};
  };
  const decryptGroup = async (envelope, sender, conversationId, device) => {
    const packet = (envelope.packets || []).find((x) =>
      x.recipient === api.principal && x.recipientDevice === device.id);
    if (!packet) throw new Error('この端末宛てのgroup sender-keyがありません。');
    const distribution = JSON.parse(await decryptPacket(sender, packet, conversationId));
    const group = envelope.group;
    if (distribution.type !== 'sender-key' || distribution.conversationId !== conversationId ||
        distribution.epoch !== group.epoch || distribution.membershipHash !== group.membershipHash ||
        distribution.senderDevice !== group.senderDevice || distribution.counter !== group.counter) {
      throw new Error('group sender-key のconversation/epoch bindingが一致しません。');
    }
    const {message, next} = await kdfCK(unb64(distribution.chain));
    const header = {sessionId:group.sessionId, epoch:group.epoch,
      membershipHash:group.membershipHash, senderDevice:group.senderDevice, counter:group.counter};
    const plaintext = await aesDecrypt(message, group, stable(header));
    await dbPut(groupRecvKey(conversationId, sender, group.senderDevice, group.epoch),
      {membershipHash:group.membershipHash, chain:next, counter:group.counter + 1});
    return plaintext;
  };
  const encryptConversation = async ({conversation, plaintext}) => {
    const device = await ensureDevice(); const recipients = conversation.members.filter((x) => x !== api.principal);
    const kind = conversation.kind === 'direct' ? 'direct' : 'group';
    let packets = []; let group = null;
    if (kind === 'group') ({packets, group} = await encryptGroup(conversation, plaintext, device, recipients));
    else {
      for (const recipient of recipients) {
        const bundles = await bundlesFor(recipient);
        if (!bundles.length) throw new Error(`${recipient} にSignal端末がありません。`);
        for (const bundle of bundles) packets.push(await encryptPacket(recipient, bundle, plaintext, conversation.id));
      }
    }
    const envelope = {protocol:PROTOCOL, kind, conversationId:conversation.id,
      sender:api.principal, senderDevice:device.id, packets,
      self:await sealSelf(device, plaintext, conversation.id)};
    if (group) envelope.group = group;
    return JSON.stringify(envelope);
  };
  const decryptEnvelope = async ({sealed, sender, conversationId, conversation}) => {
    const envelope = JSON.parse(sealed);
    if (envelope.protocol !== PROTOCOL || envelope.conversationId !== conversationId) {
      throw new Error('Signal envelope のprotocol/conversation bindingが一致しません。');
    }
    const device = await ensureDevice();
    if (envelope.kind === 'group') {
      if (!conversation?.members) throw new Error('group membershipを検証できません。');
      const expected = b64(await sha256(enc.encode([...conversation.members].sort().join('\n'))));
      if (expected !== envelope.group?.membershipHash) throw new Error('group membership hashが現在の会話と一致しません。');
      if (sender === api.principal && envelope.self?.deviceId === device.id) return openSelf(device, envelope.self);
      return decryptGroup(envelope, sender, conversationId, device);
    }
    if (sender === api.principal && envelope.self?.deviceId === device.id) return openSelf(device, envelope.self);
    const packet = (envelope.packets || []).find((x) => x.recipient === api.principal && x.recipientDevice === device.id);
    if (!packet) throw new Error('この端末宛てのciphertextがありません。');
    return decryptPacket(sender, packet, conversationId);
  };

  const configure = (options) => { api = options; };
  const initialize = async () => {
    if (!api) throw new Error('Signal API が未設定です。');
    if (!(await supported())) throw new Error('このブラウザはX25519/Ed25519/IndexedDBを利用できません。');
    const device = await ensureDevice();
    return {deviceId:device.id, protocol:PROTOCOL};
  };
  window.ItonamiSignal = {configure, initialize, supported, verifyPrincipal,
    encryptConversation, decryptEnvelope, protocol:PROTOCOL};
})();
