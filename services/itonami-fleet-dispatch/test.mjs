import {test} from 'node:test';
import assert from 'node:assert/strict';
import ts from 'typescript';
import {readFileSync} from 'node:fs';
const code=ts.transpileModule(readFileSync(new URL('./src/index.ts',import.meta.url),'utf8'),{compilerOptions:{module:ts.ModuleKind.ES2022,target:ts.ScriptTarget.ES2022}}).outputText;
const router=(await import('data:text/javascript;base64,'+Buffer.from(code).toString('base64'))).default;
test('app roots and auth reach Pages, while existing actors retain their mount',async()=>{
 const before=globalThis.fetch;const seen=[];
 const env={ITONAMI_APP_GATEWAY_KEY:'fixture',FLEET:{get(name){return {async fetch(req){seen.push([name,new URL(req.url).pathname]);return new Response('actor');}}}}};
 globalThis.fetch=async req=>{seen.push(req);return new Response('app');};
 try {
  assert.equal(await (await router.fetch(new Request('https://app.itonami.cloud/ja/?plugins=open'),env)).text(),'app');
  assert.equal(seen[0].url,'https://cloud-itonami.pages.dev/ja/bots/app/?plugins=open');
  assert.match(seen[0].headers.get('x-itonami-app-proof'),/^\d{13}\.[a-f0-9]{64}$/);
  const key=await crypto.subtle.importKey('raw',new TextEncoder().encode('fixture'),{name:'HMAC',hash:'SHA-256'},false,['verify']);
  const [stamp,sig]=seen[0].headers.get('x-itonami-app-proof').split('.');
  assert.ok(await crypto.subtle.verify('HMAC',key,Buffer.from(sig,'hex'),new TextEncoder().encode(stamp+'\nGET\n/ja/bots/app/?plugins=open')));
  await router.fetch(new Request('https://app.itonami.cloud/api/auth/session'),env);
  assert.equal(seen[1].url,'https://cloud-itonami.pages.dev/api/auth/session');
  await router.fetch(new Request('https://app.itonami.cloud/kaisya/api/state'),env);
  assert.deepEqual(seen[2],['kaisya','/api/state']);
  const redirect=await router.fetch(new Request('https://app.itonami.cloud/ja/bots/app/?plugins=open'),env);
  assert.equal(redirect.status,308); assert.equal(redirect.headers.get('location'),'https://app.itonami.cloud/ja/?plugins=open');
 }finally{globalThis.fetch=before;}
});
