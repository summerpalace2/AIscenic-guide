import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { buildPool, METHODS } from './retrieval-pool.mjs';
import { scorePool, queryMetrics, scoreFiles } from './retrieval-pool-score.mjs';

const sha=x=>createHash('sha256').update(x).digest('hex');
function fixture({repeats=1,queries=1,methodError=false}={}) {
  const corpus=Buffer.from(['a','b','c','outside-pool'].map(docId=>JSON.stringify({docId,city:docId==='outside-pool'?'杭州市':'重庆市',title:docId,content:'正文'+docId})).join('\n'));
  const rankings={schemaVersion:'retrieval-rankings-v1',corpusSha256:sha(corpus),queriesSha256:sha('queries'),configSha256:sha('config'),runs:Array.from({length:repeats},(_,ri)=>({repeat:ri+1,queries:Array.from({length:queries},(_,qi)=>({id:`rq-${qi+1}`,query:`问题${qi+1}`,city:'重庆市',split:'holdout',familyId:'museum',methods:Object.fromEntries(METHODS.map(method=>[method,methodError&&method==='hybrid_rerank'?{status:'ERROR',error:'supplier timeout'}:{status:'OK',latencyMs:ri+2,ranking:(method==='dense'?['b','a','c']:['a','b','c']).map((docId,i)=>({docId,rank:i+1,score:3-i}))}]))}))}))};
  const bytes=Buffer.from(JSON.stringify(rankings));
  const {blind,privateMap}=buildPool(bytes,corpus);
  const annotations={schemaVersion:'retrieval-pool-annotations-v1',poolId:blind.poolId,blindContentSha256:privateMap.blindContentSha256,reviewer:{name:'人工复核声明',type:'HUMAN_SELF_ATTESTED'},judgments:privateMap.queries.flatMap(q=>q.documents.map(d=>({anonymousQueryId:q.anonymousQueryId,anonymousDocumentId:d.anonymousDocumentId,grade:d.docId==='a'?2:d.docId==='b'?1:0,reason:'测试标签'})))};
  return {corpus,rankings,bytes,blind,privateMap,annotations};
}
const score=f=>scorePool(f.bytes,f.blind,f.privateMap,f.annotations);
test('no annotations is NOT_SCORED and never zero quality',()=>{
  const f=fixture();const r=scorePool(f.bytes,f.blind,f.privateMap);
  assert.equal(r.status,'NOT_SCORED');assert.equal(r.coverage.judgmentsCompleted,0);
  assert(r.summaries.every(s=>s.metrics===null));assert(r.pairs.every(p=>!p.byCitySplit.length));
  assert.equal(r.latency.find(x=>x.method==='dense').p95Ms,2);
});
test('pool denominator is pooled relevant union, not corpus count or top-K count',()=>{
  const r=score(fixture());const d=r.perQuery.find(x=>x.method==='dense');
  assert.equal(d.metrics['pooledRecall@1'],.5);assert.equal(d.metrics['pooledRecall@3'],1);
  assert(!Object.keys(d.metrics).some(x=>x==='Recall@1'));
  assert(r.limitations.some(x=>x.includes('not exhaustive')));
});
test('partial judgments close entire query; completed queries remain separately visible',()=>{
  const f=fixture({queries:2});f.annotations.judgments[0].grade=null;
  const r=score(f);assert.equal(r.status,'PARTIAL');assert.equal(r.coverage.fullyJudgedQueries,1);
  assert.equal(r.coverage.scoredRows,5);assert(r.summaries.every(s=>s.completeQueries===1));
  assert(r.pairs.every(p=>p.excluded.length===1));
});
test('zero relevant judged pool has no invented perfect scores',()=>{
  const f=fixture();f.annotations.judgments.forEach(j=>j.grade=0);const r=score(f);
  assert.equal(r.status,'NOT_SCORED');assert.equal(r.coverage.noRelevantRows,5);
  assert(r.perQuery.every(x=>x.metrics===null));
});
test('graded IDCG uses entire judged pool, even if method returns one result',()=>{
  const r=queryMetrics([{docId:'b',rank:1}],new Map([['a',2],['b',1],['c',0]]));
  assert.equal(r.metrics['nDCG@1'],1/3);
  assert.equal(r.metrics['nDCG@3'],1/(3+1/Math.log2(3)));
  assert.equal(r.metrics['pooledRecall@3'],.5);
});
test('extra or omitted pool candidate cannot be silently scored irrelevant',()=>{
  const f=fixture();const q=f.privateMap.queries[0];q.documents.push({anonymousDocumentId:'extra',docId:'outside-pool',sources:[]});
  assert.throws(()=>score(f),/unknown\/duplicate anonymous document/);
  const g=fixture();g.privateMap.queries[0].documents.pop();assert.throws(()=>score(g),/omits or adds/);
});
test('unknown, duplicate, bot or invalid human grades rejected',()=>{
  for(const mutate of [f=>f.annotations.judgments.push(f.annotations.judgments[0]),f=>f.annotations.judgments[0].anonymousDocumentId='invalid',f=>f.annotations.judgments[0].grade=3,f=>f.annotations.reviewer.type='AGENT']){
    const f=fixture();mutate(f);assert.throws(()=>score(f));
  }
});
test('rankings byte hash, map metadata and pool binding checked',()=>{
  const f=fixture();assert.throws(()=>scorePool(Buffer.concat([f.bytes,Buffer.from(' ')]),f.blind,f.privateMap,f.annotations),/hashes mismatch/);
  const g=fixture();g.privateMap.poolId='bad';assert.throws(()=>score(g),/binding/);
  const h=fixture();h.privateMap.queries[0].city='北京市';assert.throws(()=>score(h),/city mismatch/);
  const i=fixture();i.privateMap.queries[0].documents[0].sources[0].rank=9;assert.throws(()=>score(i),/sources mismatch/);
});
test('blind body edits preserving IDs/poolId and missing or mismatched annotation hashes fail',()=>{
  const f=fixture();f.blind.queries[0].documents[0].content='正文被替换，ID不变';
  assert.throws(()=>score(f),/blind content hash mismatch/);
  const g=fixture();g.blind.queries[0].documents[0].title='标题被替换';
  assert.throws(()=>score(g),/blind content hash mismatch/);
  const h=fixture();delete h.privateMap.blindContentSha256;
  assert.throws(()=>score(h),/blind content hash mismatch/);
  const i=fixture();delete i.annotations.blindContentSha256;assert.throws(()=>score(i));
  const j=fixture();j.annotations.blindContentSha256='0'.repeat(64);assert.throws(()=>score(j));
});
test('three repeats are averaged per query, not inflated independent wins',()=>{
  const r=score(fixture({repeats:3,queries:2}));
  const p=r.pairs.find(x=>x.left==='hybrid_rrf'&&x.right==='dense'&&x.metric==='nDCG@1').byCitySplit[0];
  assert.equal(p.wins,2);assert.equal(p.queries.length,2);assert.equal(p.families[0].queryCount,2);
  assert(Math.abs(p.queries[0].diff-2/3)<1e-12);
  assert.equal(r.summaries.find(x=>x.method==='dense').completeQueries,2);
});
test('supplier ERROR remains explicit and blocks comparisons instead of being dropped',()=>{
  const r=score(fixture({methodError:true}));assert.equal(r.status,'PARTIAL');assert.equal(r.coverage.errors.length,1);
  const p=r.pairs.find(x=>x.left==='hybrid_rerank');assert.equal(p.excluded.length,1);assert.equal(p.byCitySplit.length,0);
  assert.equal(r.summaries.find(x=>x.method==='hybrid_rerank').metrics,null);
});
test('one failed repeat blocks whole-query pairing, retaining every repeat error',()=>{
  const f=fixture({repeats:3});f.rankings.runs[1].queries[0].methods.hybrid_rrf={status:'ERROR',error:'transient timeout'};
  f.bytes=Buffer.from(JSON.stringify(f.rankings));const p=buildPool(f.bytes,f.corpus);f.blind=p.blind;f.privateMap=p.privateMap;
  f.annotations.poolId=p.blind.poolId;f.annotations.blindContentSha256=p.privateMap.blindContentSha256;f.annotations.judgments=p.privateMap.queries.flatMap(q=>q.documents.map(d=>({anonymousQueryId:q.anonymousQueryId,anonymousDocumentId:d.anonymousDocumentId,grade:d.docId==='a'?2:0,reason:''})));
  const r=score(f),comparison=r.pairs.find(p=>p.left==='hybrid_rrf'&&p.metric==='nDCG@1');
  assert.equal(comparison.byCitySplit.length,0);assert.equal(comparison.excluded[0].statuses[0].repeat,2);
  assert.equal(r.summaries.find(x=>x.method==='hybrid_rrf').completeQueries,0);
});
test('same-city documents and differing split metrics stay in separate tables',()=>{
  const f=fixture({queries:2});f.rankings.runs[0].queries[1].split='development';
  f.bytes=Buffer.from(JSON.stringify(f.rankings));const p=buildPool(f.bytes,f.corpus);f.blind=p.blind;f.privateMap=p.privateMap;
  f.annotations.poolId=p.blind.poolId;f.annotations.blindContentSha256=p.privateMap.blindContentSha256;f.annotations.judgments=p.privateMap.queries.flatMap(q=>q.documents.map(d=>({anonymousQueryId:q.anonymousQueryId,anonymousDocumentId:d.anonymousDocumentId,grade:d.docId==='a'?2:0,reason:''})));
  const r=score(f);assert.equal(r.summaries.length,10);assert(r.summaries.every(x=>x.queryCount===1));
  assert.equal(r.pairs.find(p=>p.metric==='nDCG@1').byCitySplit.length,2);
});
test('duplicate ranked documents and fabricated sources fail closed',()=>{
  const f=fixture();f.rankings.runs[0].queries[0].methods.dense.ranking[1].docId='b';f.bytes=Buffer.from(JSON.stringify(f.rankings));
  const inputs={...f.blind.inputs,rankingsSha256:sha(f.bytes)};f.blind.inputs=inputs;f.privateMap.inputs=inputs;
  // Preserve canonical insertion order (the existing inputs order is canonical).
  f.blind.poolId=sha(JSON.stringify(inputs));f.privateMap.poolId=f.blind.poolId;f.privateMap.blindContentSha256=sha(JSON.stringify(f.blind));
  assert.throws(()=>score(f),/duplicate ranked document/);
});
test('successful empty ranking is a real zero with positive relevance in other methods',()=>{
  const f=fixture();f.rankings.runs[0].queries[0].methods.dense.ranking=[];
  f.bytes=Buffer.from(JSON.stringify(f.rankings));const pool=buildPool(f.bytes,f.corpus);f.blind=pool.blind;f.privateMap=pool.privateMap;f.annotations.poolId=f.blind.poolId;f.annotations.blindContentSha256=f.privateMap.blindContentSha256;
  // Rebuild anonymous identities because the pool ID changed with rankings bytes.
  f.annotations.judgments=f.privateMap.queries.flatMap(q=>q.documents.map(d=>({anonymousQueryId:q.anonymousQueryId,anonymousDocumentId:d.anonymousDocumentId,grade:d.docId==='a'?2:0,reason:''})));
  const r=score(f);const d=r.perQuery.find(x=>x.method==='dense');assert.equal(d.metrics['nDCG@10'],0);assert.equal(d.metrics['MRR@10'],0);
  assert.equal(r.status,'PARTIAL');assert(d.poolCoverageProblems.some(x=>x.status==='EMPTY_RANKING'));
  assert.equal(r.summaries.find(x=>x.method==='dense').completeQueries,0);
});
test('real builder incomplete Top10 coverage survives scoring and blocks complete pairing',()=>{
  const f=fixture({repeats:3});f.rankings.runs[1].queries[0].methods.dense.ranking=[{docId:'a',rank:1,score:1}];
  f.bytes=Buffer.from(JSON.stringify(f.rankings));const p=buildPool(f.bytes,f.corpus);f.blind=p.blind;f.privateMap=p.privateMap;
  f.annotations.poolId=p.blind.poolId;f.annotations.blindContentSha256=p.privateMap.blindContentSha256;
  f.annotations.judgments=p.privateMap.queries.flatMap(q=>q.documents.map(d=>({anonymousQueryId:q.anonymousQueryId,anonymousDocumentId:d.anonymousDocumentId,grade:d.docId==='a'?2:0,reason:''})));
  assert.equal(p.privateMap.coverage.complete,false);
  assert.equal(scorePool(f.bytes,f.blind,f.privateMap).status,'NOT_SCORED');
  const r=score(f);assert.equal(r.status,'PARTIAL');assert.deepEqual(r.poolCoverage,p.privateMap.coverage);
  const short=r.perQuery.find(x=>x.method==='dense'&&x.repeat===2);assert.equal(short.metrics['nDCG@10'],1);
  assert(short.poolCoverageProblems.some(x=>x.status==='INCOMPLETE_TOP10'));
  assert.equal(r.summaries.find(x=>x.method==='dense').completeQueries,0);
  assert.equal(r.summaries.find(x=>x.method==='hybrid_rrf').completeQueries,1);
  const pair=r.pairs.find(x=>x.left==='hybrid_rrf'&&x.right==='dense');assert.equal(pair.byCitySplit.length,0);assert.equal(pair.excluded[0].statuses[0].repeat,2);
});
test('score file refuses overwrite and records artifact hashes',async()=>{
  const dir=await mkdtemp(join(tmpdir(),'retrieval-score-'));
  try{
    const f=fixture();for(const [name,value]of [['rankings',f.rankings],['pool',f.blind],['mapping',f.privateMap]])await writeFile(join(dir,name+'.json'),JSON.stringify(value));
    const opts={rankingsPath:join(dir,'rankings.json'),poolPath:join(dir,'pool.json'),mappingPath:join(dir,'mapping.json'),outputPath:join(dir,'scores.json')};
    const r=await scoreFiles(opts);assert.equal(r.status,'NOT_SCORED');assert.equal(r.artifactSha256.pool,sha(await readFile(opts.poolPath)));
    await assert.rejects(()=>scoreFiles(opts),e=>e.code==='EEXIST');
  }finally{await rm(dir,{recursive:true,force:true});}
});
