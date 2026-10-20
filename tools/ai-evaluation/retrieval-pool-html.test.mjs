import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, webcrypto } from 'node:crypto';
import { runInNewContext } from 'node:vm';
import { buildPool, annotationHtml, validateAnnotations, METHODS } from './retrieval-pool.mjs';

// Executes the actual generated offline page script with a small DOM, not a second
// implementation of its import/export logic. This does not claim browser download QA.
async function page(options = {}) {
  const sha=x=>createHash('sha256').update(x).digest('hex');
  const corpus=Buffer.from(JSON.stringify({docId:'private-a',city:'重庆市',title:'标题',content:'离线正文 </script> & 中文'}));
  const rankings={schemaVersion:'retrieval-rankings-v1',corpusSha256:sha(corpus),queriesSha256:sha('q'),configSha256:sha('c'),runs:[{repeat:1,queries:Array.from({length:options.queryCount??1},(_,i)=>({id:'private-rq'+(i+1),query:'问题'+(i+1),city:'重庆市',split:i===0?'development':'holdout',methods:Object.fromEntries(METHODS.map(m=>[m,{status:'OK',ranking:[{docId:'private-a',rank:1,score:1}]}]))}))}]};
  const p=buildPool(Buffer.from(JSON.stringify(rankings)),corpus),devIds=options.development?p.privateMap.queries.filter(q=>q.split==='development').map(q=>q.anonymousQueryId):[],html=annotationHtml(p.blind,true,p.privateMap.blindContentSha256,devIds);
  const script=html.match(/<\/script><script>([\s\S]*?)<\/script>/)[1];
  const embedded=html.match(/<script id="pool-data" type="application\/json">([\s\S]*?)<\/script>/)[1];
  const elements=new Map(),nodes=[];
  function node(tag){const n={tag,children:[],value:'',textContent:'',append(...xs){this.children.push(...xs);},replaceChildren(){this.children=[];},click(){this.clicked=true;}};nodes.push(n);return n;}
  for(const id of ['pool-data','reviewer','main','progress','prev','next','search','export','import','status','storage-status','rubric-notes','development','confirm-saved'])elements.set(id,node(id));
  elements.get('pool-data').textContent=embedded;elements.get('confirm-saved').disabled=true;
  const blobs=[],storage=options.storage??new Map(),handlers={};
  const draftKey='retrieval-pool-draft:'+p.blind.poolId+':'+p.privateMap.blindContentSha256;
  if(options.seed!==undefined)storage.set(draftKey,options.seed);
  const localStorage={getItem:k=>{if(options.security)throw new Error('SecurityError');return storage.get(k)??null;},setItem:(k,v)=>{if(options.quota)throw new Error('QuotaExceededError');storage.set(k,v);}};
  const context={localStorage,addEventListener:(name,fn)=>{handlers[name]=fn;},document:{getElementById:id=>elements.get(id),createElement:node},crypto:webcrypto,TextEncoder,Blob,Uint8Array,URL:{createObjectURL:b=>{blobs.push(b);return'blob:test';},revokeObjectURL(){}},setTimeout:fn=>fn()};
  runInNewContext(script,context);await runInNewContext('ready',context);
  return{p,html,elements,nodes,blobs,context,storage,draftKey,handlers,async exported(){await elements.get('export').onclick();return blobs.length?JSON.parse(await blobs.at(-1).text()):null;},async imported(value){elements.get('import').files=[{text:async()=>JSON.stringify(value)}];await elements.get('import').onchange();}};
}
test('actual offline script exports human grades/reasons and imports continuation without network capabilities',async()=>{
  const f=await page();f.elements.get('reviewer').value='独立复核者';
  const select=f.nodes.find(n=>n.tag==='select');select.value='2';select.onchange();
  const textarea=f.nodes.find(n=>n.tag==='textarea');textarea.value='直接回答';textarea.oninput();
  const first=await f.exported();assert.equal(first.judgments[0].grade,2);assert.equal(first.judgments[0].reason,'直接回答');
  assert.equal(first.blindContentSha256,f.p.privateMap.blindContentSha256);assert.equal(first.reviewer.type,'HUMAN_SELF_ATTESTED');
  validateAnnotations(first,f.p.blind,f.p.privateMap.blindContentSha256);
  const resumed=structuredClone(first);resumed.judgments[0].grade=1;resumed.judgments[0].reason='部分相关';
  await f.imported(resumed);const second=await f.exported();assert.equal(second.judgments[0].grade,1);assert.equal(second.judgments[0].reason,'部分相关');
  assert.equal(f.context.fetch,undefined);assert.equal(f.context.XMLHttpRequest,undefined);
});
test('actual offline script keeps unjudged null and refuses unnamed export',async()=>{
  const f=await page();assert.equal(await f.exported(),null);assert.match(f.elements.get('status').textContent,/姓名/);
  f.elements.get('reviewer').value='Reviewer';const value=await f.exported();assert.equal(value.judgments[0].grade,null);
});
test('actual offline script refuses duplicate, stale-text and unknown imported judgments',async()=>{
  const f=await page();f.elements.get('reviewer').value='Reviewer';const good=await f.exported();
  for(const mutate of [x=>x.judgments.push(x.judgments[0]),x=>x.blindContentSha256='0'.repeat(64),x=>x.judgments[0].anonymousDocumentId='unknown']){
    const bad=structuredClone(good);mutate(bad);await f.imported(bad);assert.match(f.elements.get('status').textContent,/导入失败/);
    const value=await f.exported();assert.equal(value.judgments[0].grade,null);
  }
});
test('actual offline script blocks both import/export after candidate body mutation',async()=>{
  const f=await page();f.elements.get('reviewer').value='Reviewer';const good=await f.exported();const count=f.blobs.length;
  runInNewContext("pool.queries[0].documents[0].content='误改正文'",f.context);
  await f.elements.get('export').onclick();assert.equal(f.blobs.length,count);assert.match(f.elements.get('status').textContent,/候选正文已变更/);
  await f.imported(good);assert.match(f.elements.get('status').textContent,/导入失败.*候选正文已变更/);
});

test('autosave restores two questions, empty reviewer name, reasons and global rubric notes without human claim',async()=>{
  const storage=new Map(),f=await page({storage,queryCount:2});
  const first=f.nodes.filter(n=>n.tag==='select').at(-1);first.value='2';first.onchange();
  const reason=f.nodes.filter(n=>n.tag==='textarea').at(-1);reason.value='第一题理由';reason.oninput();
  f.elements.get('rubric-notes').value='只提名判0';f.elements.get('rubric-notes').oninput();
  f.elements.get('next').onclick();const second=f.nodes.filter(n=>n.tag==='select').at(-1);second.value='1';second.onchange();
  const draft=JSON.parse(storage.get(f.draftKey));assert.equal(draft.schemaVersion,'retrieval-pool-draft-v1');assert.equal(draft.reviewerName,'');assert.equal(draft.reviewer,undefined);assert.equal(draft.judgments.filter(j=>j.grade!==null).length,2);
  const g=await page({storage,queryCount:2});assert.equal(g.elements.get('reviewer').value,'');assert.equal(g.elements.get('rubric-notes').value,'只提名判0');assert.match(g.elements.get('storage-status').textContent,/已恢复/);
  g.elements.get('reviewer').value='Reviewer';g.elements.get('reviewer').oninput();const exported=await g.exported();assert.deepEqual(exported.judgments.map(j=>j.grade),[2,1]);assert.equal(exported.judgments[0].reason,'第一题理由');assert.equal(exported.rubricNotes,'只提名判0');
});

test('quota and security failures keep edits in memory and do not prevent local export',async()=>{
  for(const failure of ['quota','security']){
    const options={[failure]:true},f=await page(options);f.elements.get('reviewer').value='Reviewer';f.elements.get('reviewer').oninput();const grade=f.nodes.filter(n=>n.tag==='select').at(-1);grade.value='2';grade.onchange();
    assert.match(f.elements.get('storage-status').textContent,/失败|不可用/);assert.doesNotMatch(f.elements.get('storage-status').textContent,/已保存/);assert.equal(f.storage.size,0);
    const a=await f.exported();assert.equal(a.judgments[0].grade,2);assert.match(f.elements.get('status').textContent,/请确认文件已保存/);
  }
});

test('broken, stale-body and invalid-ID stored drafts are never overwritten',async()=>{
  const baseline=await page();baseline.elements.get('reviewer').value='Reviewer';baseline.elements.get('reviewer').oninput();const valid=JSON.parse(baseline.storage.get(baseline.draftKey));
  const badBody=structuredClone(valid);badBody.blindContentSha256='0'.repeat(64);
  const badId=structuredClone(valid);badId.judgments[0].anonymousDocumentId='wrong';
  const badGrade=structuredClone(valid);badGrade.judgments[0].grade=7;
  for(const raw of ['{broken',JSON.stringify(badBody),JSON.stringify(badId),JSON.stringify(badGrade)]){
    const f=await page({seed:raw});assert.match(f.elements.get('storage-status').textContent,/未恢复/);f.elements.get('reviewer').value='Reviewer';f.elements.get('reviewer').oninput();const grade=f.nodes.filter(n=>n.tag==='select').at(-1);grade.value='1';grade.onchange();
    assert.equal(f.storage.get(f.draftKey),raw);assert.equal((await f.exported()).judgments[0].grade,1);
  }
});

test('different pool identity uses another storage key and cannot auto-restore stale judgments',async()=>{
  const storage=new Map(),f=await page({storage});f.elements.get('reviewer').value='Previous';f.elements.get('reviewer').oninput();const original=storage.get(f.draftKey);
  const g=await page({storage,queryCount:2});assert.notEqual(g.draftKey,f.draftKey);assert.equal(g.elements.get('reviewer').value,'');assert.equal(storage.get(f.draftKey),original);
});

test('development filtering never removes grades from other queries or export',async()=>{
  const f=await page({queryCount:2,development:true});assert.equal(f.elements.get('development').checked,true);assert.match(f.elements.get('progress').textContent,/1 \/ 1 题/);
  let grade=f.nodes.filter(n=>n.tag==='select').at(-1);grade.value='2';grade.onchange();
  f.elements.get('development').checked=false;f.elements.get('development').onchange();assert.match(f.elements.get('progress').textContent,/1 \/ 2 题/);
  const devId=f.p.privateMap.queries.find(q=>q.split==='development').anonymousQueryId;
  const otherIndex=f.p.blind.queries.findIndex(q=>q.anonymousQueryId!==devId);if(otherIndex===1)f.elements.get('next').onclick();
  grade=f.nodes.filter(n=>n.tag==='select').at(-1);grade.value='1';grade.onchange();
  f.elements.get('development').checked=true;f.elements.get('development').onchange();assert.match(f.elements.get('progress').textContent,/已判 2 \/ 2 篇/);
  f.elements.get('reviewer').value='Reviewer';const a=await f.exported();assert.equal(a.judgments.length,2);assert(a.judgments.some(j=>j.grade===1));assert(a.judgments.some(j=>j.grade===2));
  assert(!f.html.includes('private-rq'));assert(!f.html.includes('"familyId"'));assert(!f.html.includes('"sources"'));
});

test('old annotation files remain importable and notes round-trip without changing grades',async()=>{
  const f=await page();f.elements.get('reviewer').value='Reviewer';f.elements.get('rubric-notes').value='先前口径';f.elements.get('rubric-notes').oninput();const old=await f.exported();delete old.rubricNotes;
  await f.imported(old);assert.equal(f.elements.get('rubric-notes').value,'先前口径');
  f.elements.get('rubric-notes').value='新口径';f.elements.get('rubric-notes').oninput();const a=await f.exported();assert.equal(a.rubricNotes,'新口径');assert.equal(a.judgments[0].grade,null);
});

test('beforeunload warns about unexported edits, even when local draft saved',async()=>{
  const f=await page();let prevented=false;const event={preventDefault(){prevented=true;}};f.handlers.beforeunload(event);assert.equal(prevented,false);
  f.elements.get('rubric-notes').value='未导出';f.elements.get('rubric-notes').oninput();f.handlers.beforeunload(event);assert.equal(prevented,true);assert.match(event.returnValue,/未确认保存/);
  f.elements.get('reviewer').value='Reviewer';await f.exported();prevented=false;f.handlers.beforeunload({preventDefault(){prevented=true;}});assert.equal(prevented,true);f.elements.get('confirm-saved').onclick();prevented=false;f.handlers.beforeunload({preventDefault(){prevented=true;}});assert.equal(prevented,false);
});

test('storage failure and download initiation retain leave warning until explicit saved-file confirmation',async()=>{
  const f=await page({quota:true});f.elements.get('reviewer').value='Reviewer';f.elements.get('reviewer').oninput();
  // The mock anchor click only requests a download; no successful file write exists.
  await f.exported();let warned=false;f.handlers.beforeunload({preventDefault(){warned=true;}});assert.equal(warned,true);
  assert.equal(f.elements.get('confirm-saved').disabled,false);f.elements.get('confirm-saved').onclick();warned=false;f.handlers.beforeunload({preventDefault(){warned=true;}});assert.equal(warned,false);
});

test('new edits disable old export confirmation and cannot clear leave warning',async()=>{
  const f=await page();f.elements.get('reviewer').value='Reviewer';f.elements.get('reviewer').oninput();await f.exported();assert.equal(f.elements.get('confirm-saved').disabled,false);
  f.elements.get('rubric-notes').value='下载发起后的新编辑';f.elements.get('rubric-notes').oninput();assert.equal(f.elements.get('confirm-saved').disabled,true);
  f.elements.get('confirm-saved').onclick();let warned=false;f.handlers.beforeunload({preventDefault(){warned=true;}});assert.equal(warned,true);
  await f.exported();f.elements.get('confirm-saved').onclick();warned=false;f.handlers.beforeunload({preventDefault(){warned=true;}});assert.equal(warned,false);
});
