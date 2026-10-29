import { test } from 'node:test';
import assert from 'node:assert/strict';
import { draftDescriptor, draftRequestHash, validDraftSave } from '../src/program-draft-contract.js';
import { sourceIdentity } from '../src/program-drafts.js';
const id=(n:number)=>'00000000-0000-4000-8000-'+String(n).padStart(12,'0');
const M=2147483646;
const draft={templateId:id(1),expectedRevision:M-1,authority:{authorLifecycleRevision:M,authorMembershipRevision:M,workspaceMembershipRevision:M},
  title:'😀'.repeat(80),description:'😀'.repeat(240),workouts:Array.from({length:3},(_,w)=>({id:id(w+2),title:'😀'.repeat(40),
    items:Array.from({length:4},(_,i)=>{const index=w*4+i;return {id:id(index+10),sourceKind:'system',sourceId:'z'.repeat(128),sourceRevision:null,
      sourceHash:'a'.repeat(64),name:'😀'.repeat(index===0?120:index===1?80:1),prescription:{sets:null,reps:1000,restSeconds:3600,loadGrams:1000000}};})}))};
test('production hash parity, complete wrapper and explicit nulls',()=>{
  assert(validDraftSave(draft));const key='or1-b91f0176ec74475e8cf7406e25b7e9d3-'+'a'.repeat(80);
  assert.equal(draftRequestHash('A',draftDescriptor('w',key,draft)),'9d55a246b963653fdacb83755a175dd2d9dea417d0679ba3724121c1bab17018');
  const resolver=JSON.stringify({protocolVersion:1,descriptor:draftDescriptor('\u0002'.repeat(128),key,draft)});
  assert.equal(resolver.length,7580);assert.equal(Buffer.byteLength(resolver,'utf8'),8880);
  assert(!validDraftSave({...draft,title:'😀'.repeat(81)}));
  assert(!validDraftSave({...draft,extra:true}));
  assert(!validDraftSave({...draft,expectedRevision:'0'}));
  const escaped={templateId:id(1),expectedRevision:0,authority:{authorLifecycleRevision:1,authorMembershipRevision:1,workspaceMembershipRevision:1},title:'Quote " slash / backslash \\ café 😀',description:'Line one\nLine two \u2028 漢',workouts:[]};
  assert(validDraftSave(escaped));
  assert.equal(draftRequestHash('A',draftDescriptor('w','or1-b91f0176ec74475e8cf7406e25b7e9d3-escaped',escaped)),
    'ba1653c9e5c922c96c380336e3c980645ddfb6251c0351904563ed43a1f60772');
  assert.equal(sourceIdentity('system','synthetic','w','A',{schemaVersion:1,name:'Synthetic exercise',nameNormalized:'synthetic exercise',muscleGroups:[],equipment:[],instructions:'Original synthetic text.',status:'published'})!.sourceHash,
    'a7b1b8560a24779cf202e9618d4083da05dcc2b725bfdbfd77ee18d430899647');
});
