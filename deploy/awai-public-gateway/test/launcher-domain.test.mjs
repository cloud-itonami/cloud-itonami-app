import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { runInNewContext } from 'node:vm';

const launcherSource = await readFile(new URL(
  '../../../resources/cloud/itonami/app/awai-launcher.js', import.meta.url), 'utf8');

class Element {
  constructor(tagName = 'div') {
    this.tagName = tagName;
    this.children = [];
    this.listeners = new Map();
    this.textContent = '';
    this.disabled = false;
  }

  append(...nodes) {
    this.children.push(...nodes);
  }

  replaceChildren(...nodes) {
    this.children = nodes;
  }

  addEventListener(name, listener) {
    this.listeners.set(name, listener);
  }
}

async function renderForHost(hostname, search = '', candidates = [
  { domain: 'murakumo.cloud', 'app-id': 'net.kotoba.murakumo', decision: 'candidate' },
  { domain: 'aozora.app', 'app-id': 'net.kotoba.aozora', decision: 'candidate' }
]) {
  const elements = new Map();
  const document = {
    getElementById(id) {
      if (!elements.has(id)) elements.set(id, new Element());
      return elements.get(id);
    },
    createElement(tagName) {
      return new Element(tagName);
    },
    createTextNode(text) {
      return { textContent: text };
    }
  };
  const fetch = async () => ({
    ok: true,
    json: async () => ({
      status: 'ok',
      candidates
    })
  });

  runInNewContext(launcherSource, {
    document,
    location: { hostname, port: '', search },
    fetch,
    AbortSignal,
    URL,
    URLSearchParams
  });
  await new Promise((resolve) => setImmediate(resolve));
  return { list: elements.get('awai-apps'), trust: elements.get('awai-trust') };
}

test('product hostname focuses the launcher on its exact Awai binding', async () => {
  const { list, trust } = await renderForHost('murakumo.cloud');
  assert.equal(list.children.length, 1);
  assert.equal(list.children[0].children[0].textContent, 'murakumo.cloud');
  assert.match(trust.textContent, /murakumo\.cloud/);
});

test('canonical Itonami host keeps the full app directory', async () => {
  const { list } = await renderForHost('itonami.app');
  assert.equal(list.children.length, 2);
});

test('unbound product hostname does not fall through to other apps', async () => {
  const { list } = await renderForHost('unknown.example');
  assert.equal(list.children.length, 1);
  assert.match(list.children[0].textContent, /unknown\.example/);
  assert.match(list.children[0].textContent, /binding/);
});

test('public product alias hands its exact domain to the local Itonami host', async () => {
  const { list } = await renderForHost('murakumo.cloud');
  const card = list.children[0];
  const handoff = card.children.find((child) => child.tagName === 'a');
  assert.equal(handoff.href,
    'http://127.0.0.1:1338/?domain=murakumo.cloud');
  assert.match(handoff.textContent, /ローカル Itonami host/);
});

test('a non-candidate binding does not get a local handoff link', async () => {
  const { list } = await renderForHost('itonami.app', '', [
    { domain: 'murakumo.cloud', 'app-id': 'net.kotoba.murakumo', decision: 'revoked' }
  ]);
  assert.equal(list.children[0].children.some((child) => child.tagName === 'a'), false);
});

test('local handoff focuses one domain and enables local install controls', async () => {
  const { list } = await renderForHost('127.0.0.1', '?domain=aozora.app');
  assert.equal(list.children.length, 1);
  const card = list.children[0];
  assert.equal(card.children[0].textContent, 'aozora.app');
  const launchButton = card.children.find((child) => child.tagName === 'button');
  assert.equal(launchButton.disabled, false);
});

test('malformed local handoff fails closed without showing the app directory', async () => {
  const { list, trust } = await renderForHost(
    '127.0.0.1', '?domain=aozora.app&domain=murakumo.cloud');
  assert.equal(list.children.length, 1);
  assert.match(list.children[0].textContent, /canonical domain query/i);
  assert.match(trust.textContent, /引き継ぎを拒否/);
});
