import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const source=readFileSync('resources/cloud/itonami/app/interaction.js','utf8');
const sync=source.match(/const syncBotsFromResident = async \(\) => \{([\s\S]*?)\n    \};/)[0];
for (const active of [false,true]) {
 const calls=[], rail=[], timers=[];
 const state={selected:'old-failure',syncing:false,activeRuns:new Map(active?[['old-failure',{}]]:[]),threadVersion:'unchanged',bots:[]};
 const c={appUnlocked:true,botsVisible:()=>true,document:{hidden:false},botsState:state,Date,
 fetch:async url=>{calls.push(url);return {ok:true,json:async()=>url==='/api/bots'?{bots:[{id:'other',status:'running'}]}:{messages:[],turn:null}};},
 renderBotsRail:()=>rail.push(state.bots[0].status),botsThreadVersion:()=> 'unchanged',scheduleBotsRealtime:x=>timers.push(x)};
 await vm.runInNewContext(sync+';syncBotsFromResident();',c);
 assert.deepEqual(rail,['running'],'unchanged selected thread must not freeze other Bots');
 assert.equal(calls.includes('/api/bots/old-failure/messages'),!active,'do not race an active stream');
 assert.equal(state.syncing,false);assert.equal(timers.length,1);
}
console.log('Bot overview advances with unchanged conversation and during active streams.');
