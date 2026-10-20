import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp,writeFile,readFile,rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Script } from 'node:vm';
import { METHODS,buildPool,writePool,annotationHtml,validateAnnotations,verifyBlindPool } from './retrieval-pool.mjs';
const sha=s=>createHash('sha256').update(s).digest('hex');
function fixture(){
  const corpus=Buffer.from(Array.from({length:24},(_,i)=>JSON.stringify({docId:`private-doc-${i}`,city:i===23?'杭州':'重庆',title:`标题${i}`,content:i===0?'恶意 </script><img src=x onerror=alert(1)> & \u2028 资料':`正文${i}`})).join('\n'));
  const q={id:'private-query',query:'需要回答的问题',city:'重庆',split:'holdout-secret',familyId:'family-secret',gold:['private-doc-0'],methods:Object.fromEntries(METHODS.map((m,mi)=>[m,{status:'OK',latencyMs:2,ranking:Array.from({length:10},(_,i)=>({docId:`private-doc-${(i+mi)%20}`,rank:i+1,score:10-i}))}]))};
  const r={schemaVersion:'retrieval-rankings-v1',corpusSha256:sha(corpus),configSha256:'a'.repeat(64),queriesSha256:'b'.repeat(64),runs:[{repeat:1,queries:[q]}]};
  return {corpus,r,bytes:()=>Buffer.from(JSON.stringify(r))};
}
test('all methods and all repeats contribute; duplicate documents pool only once; deterministic shuffle',()=>{
  const f=fixture(), q=structuredClone(f.r.runs[0].queries[0]);q.methods.dense.ranking[0].docId='private-doc-22';f.r.runs.push({repeat:2,queries:[q]});
  const a=buildPool(f.bytes(),f.corpus),b=buildPool(f.bytes(),f.corpus);
  assert.deepEqual(a,b);assert(a.privateMap.coverage.complete);const ds=a.privateMap.queries[0].documents;
  assert.equal(ds.length,15);assert(ds.some(d=>d.docId==='private-doc-22'));assert(ds.find(d=>d.docId==='private-doc-5').sources.length>2);
  assert.notDeepEqual(ds.map(d=>d.docId),f.r.runs[0].queries[0].methods.dense.ranking.map(d=>d.docId));
  assert(a.blind.queries[0].documents.every(d=>d.grade===null));
});
test('blind payload excludes source IDs, methods, ranks, splits, gold and family metadata',()=>{
  const f=fixture(),{blind}=buildPool(f.bytes(),f.corpus),s=JSON.stringify(blind);
  for(const secret of ['private-doc','private-query','holdout-secret','family-secret','bm25_jieba','hybrid_rrf','"rank"','"gold"'])assert(!s.includes(secret),secret);
  assert.deepEqual(Object.keys(blind.queries[0]),['anonymousQueryId','query','city','documents']);
});
test('hash mismatch, unknown IDs, cross-city hits, duplicate ranks/docs fail',()=>{
  for(const mutate of [f=>f.r.corpusSha256='0'.repeat(64),f=>f.r.runs[0].queries[0].methods.dense.ranking[0].docId='no-such',f=>f.r.runs[0].queries[0].methods.dense.ranking[0].docId='private-doc-23',f=>f.r.runs[0].queries[0].methods.dense.ranking[1].rank=1,f=>f.r.runs[0].queries[0].methods.dense.ranking[1].docId='private-doc-0']){
    const f=fixture();mutate(f);assert.throws(()=>buildPool(f.bytes(),f.corpus));
  }
});
test('missing/error/empty methods and absent query repetitions are reported, never silently complete',()=>{
  const f=fixture(),q=f.r.runs[0].queries[0];delete q.methods.bm25_bigram;q.methods.dense={status:'ERROR',error:'provider failed'};q.methods.bm25_jieba.ranking=[];
  const q2=structuredClone(q);q2.id='other-query';f.r.runs.push({repeat:2,queries:[q2]});
  const p=buildPool(f.bytes(),f.corpus);assert.equal(p.privateMap.coverage.complete,false);
  for(const kind of ['MISSING_METHOD','ERROR','EMPTY_RANKING','MISSING_QUERY'])assert(p.privateMap.coverage.problems.some(x=>x.status===kind));
  assert(annotationHtml(p.blind,false).includes('警告：候选池检索覆盖不完整'));
});
test('offline HTML contains escaped corpus text and syntactically valid safe scripts',()=>{
  const f=fixture(),p=buildPool(f.bytes(),f.corpus),html=annotationHtml(p.blind,true);
  assert(!html.includes('</script><img'));assert(!html.includes('innerHTML'));assert(html.includes('connect-src \'none\''));assert(html.includes('textContent'));
  const embedded=html.match(/<script id="pool-data" type="application\/json">([\s\S]*?)<\/script>/)[1];
  assert.deepEqual(JSON.parse(embedded),p.blind);
  const script=html.match(/<\/script><script>([\s\S]*?)<\/script>/)[1];assert.doesNotThrow(()=>new Script(script));
});
test('annotations require matching input identity and explicit human declaration; unjudged stays null',()=>{
  const f=fixture(),p=buildPool(f.bytes(),f.corpus).blind,q=p.queries[0],d=q.documents[0];
  const expected=sha(JSON.stringify(p));
  const a={schemaVersion:'retrieval-pool-annotations-v1',poolId:p.poolId,blindContentSha256:expected,reviewer:{name:'Tester',type:'HUMAN_SELF_ATTESTED'},judgments:[{anonymousQueryId:q.anonymousQueryId,anonymousDocumentId:d.anonymousDocumentId,grade:null,reason:''}]};
  assert.equal(validateAnnotations(a,p,expected).judgments[0].grade,null);
  for(const mutate of [x=>x.poolId='old',x=>x.reviewer.name='',x=>x.reviewer.type='AI',x=>x.judgments.push(x.judgments[0]),x=>x.judgments[0].grade=3,x=>x.judgments[0].anonymousDocumentId='unknown']){const b=structuredClone(a);mutate(b);assert.throws(()=>validateAnnotations(b,p,expected));}
  f.r.configSha256='c'.repeat(64);assert.notEqual(buildPool(f.bytes(),f.corpus).blind.poolId,p.poolId);
});
test('editing blind candidate title or content without changing poolId breaks content verification',()=>{
  const f=fixture(),{blind,privateMap}=buildPool(f.bytes(),f.corpus);
  assert.equal(verifyBlindPool(blind,privateMap),privateMap.blindContentSha256);
  for(const field of ['title','content']){const changed=structuredClone(blind);changed.queries[0].documents[0][field]+=' edited';assert.equal(changed.poolId,blind.poolId);assert.throws(()=>verifyBlindPool(changed,privateMap),/content changed/);assert.throws(()=>annotationHtml(changed,true,privateMap.blindContentSha256),/content changed/);}
});
test('old annotations cannot be carried to changed candidate text even if IDs and poolId are preserved',()=>{
  const f=fixture(),{blind,privateMap}=buildPool(f.bytes(),f.corpus);
  const a={schemaVersion:'retrieval-pool-annotations-v1',poolId:blind.poolId,blindContentSha256:privateMap.blindContentSha256,reviewer:{name:'Reviewer',type:'HUMAN_SELF_ATTESTED'},judgments:[]};
  const changed=structuredClone(blind);changed.queries[0].documents[0].content+=' new version';
  assert.throws(()=>validateAnnotations(a,changed,sha(JSON.stringify(changed))),/正文版本不匹配/);
  delete a.blindContentSha256;assert.throws(()=>validateAnnotations(a,blind,privateMap.blindContentSha256),/正文版本不匹配/);
});
test('delivery refuses existing directories without overwriting prior output',async()=>{
  const temp=await mkdtemp(join(tmpdir(),'retrieval-pool-'));
  try{const f=fixture(),rankingsPath=join(temp,'rankings.json'),corpusPath=join(temp,'corpus.jsonl'),outputDir=join(temp,'pool');await writeFile(rankingsPath,f.bytes());await writeFile(corpusPath,f.corpus);const result=await writePool({rankingsPath,corpusPath,outputDir});assert(result.coverage.complete);const original=await readFile(join(outputDir,'blind-pool.json'));await assert.rejects(writePool({rankingsPath,corpusPath,outputDir}),/EEXIST/);assert.deepEqual(await readFile(join(outputDir,'blind-pool.json')),original);}
  finally{await rm(temp,{recursive:true,force:true});}
});
