import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const source=readFileSync('resources/cloud/itonami/app/interaction.js','utf8');
function elements(){const nodes=new Map();return id=>{if(!nodes.has(id))nodes.set(id,{textContent:'',hidden:false,disabled:false,dataset:{},attrs:{},classList:{toggle(k,v){this[k]=v;},remove(k){delete this[k];}},setAttribute(k,v){this.attrs[k]=v;}});return nodes.get(id);};}
const gate=source.slice(source.indexOf('    const renderSigninGate ='),source.indexOf('    const renderIdentity ='));
for(const [supported,resuming,authenticated,hidden] of [[true,false,false,true],[true,true,false,false],[false,false,false,false],[true,false,true,true]]){
 const $=elements();vm.runInNewContext(gate+';renderSigninGate(data);',{$,passkeySupported:()=>supported,hostedPasskeyConfigured:()=>true,nativeSurface:()=>false,data:{'passkey-required?':resuming,'authenticated?':authenticated,'may-act?':authenticated}});
 assert.equal($('#passkey-gate-notice').hidden,hidden);assert.equal($('#passkey-signin').disabled,!supported);
}
const start=source.indexOf("    if (initialParams.get('auth')) {");const callback=source.slice(start,source.indexOf('    // ---- Comment mode',start));
for(const [provider,auth,unlocked] of [['itonami-cloud','error',false],['itonami-cloud','error',true],['itonami-cloud','itonami-cloud',false],['other','error',false]]){
 const $=elements();let replaced='';vm.runInNewContext(callback,{$,initialParams:new URLSearchParams({provider,auth}),appUnlocked:unlocked,URL,location:{href:'https://localhost/?auth=error&provider=itonami-cloud#/signin'},history:{replaceState(a,b,c){replaced=c;}}});
 assert.equal(replaced,'/#/signin');const n=$('#identity-status');
 if(provider==='other')assert.equal(n.textContent,'');else{assert.equal(n.attrs.role,auth==='error'?'alert':'status');assert.equal(n.classList['settings-notice--error'],auth==='error');if(unlocked)assert.equal(n.textContent,'');else assert.ok(n.textContent.length);}
}
const render=source.slice(source.indexOf('    const renderIdentity ='));const route=render.slice(render.indexOf('      if (!identityReady) {'),render.indexOf('      const onboarding ='));
for(const ready of [true,false]){const $=elements();let view='';$('#identity-status').dataset.authResult='error';$('#identity-status').textContent='old error';vm.runInNewContext(route,{$,identityReady:ready,publicViews:new Set(['signin']),requestedView:'signin',currentView:'signin',showView:v=>view=v,bootstrapApp:()=>{},loadChronicle:()=>Promise.resolve()});assert.equal(view,ready?'bots':'signin');if(ready)assert.equal($('#identity-status').textContent,'');}
console.log('Signin UX: normal/recovery/unsupported states, failure semantics, consumed callback and verified-session return passed.');
