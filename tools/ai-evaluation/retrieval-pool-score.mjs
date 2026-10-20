import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { parseArgs } from 'node:util';
import { pathToFileURL } from 'node:url';
import { METHODS, validateAnnotations, validateModelAssistedAnnotations } from './retrieval-pool.mjs';

const sha = value => createHash('sha256').update(value).digest('hex');
const parse = bytes => JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, ''));
const mean = xs => xs.length ? xs.reduce((a,b)=>a+b,0)/xs.length : null;
const KS = [1,3,5,10];
const metricNames = [...KS.flatMap(k=>[`pooledRecall@${k}`,`nDCG@${k}`]), 'MRR@10'];
const key = (q,d) => `${q}/${d}`;
const canonical = xs => [...xs].sort((a,b)=>JSON.stringify(a).localeCompare(JSON.stringify(b)));

// Relevance is relative to this judged pool, never an exhaustive corpus recall claim.
export function queryMetrics(ranking, grades) {
  const ordered = [...ranking].sort((a,b)=>a.rank-b.rank);
  const ids = new Set();
  for (const hit of ordered) { assert(!ids.has(hit.docId),'duplicate ranked document'); ids.add(hit.docId); }
  const relevant = [...grades.values()].filter(g=>g>=1).length;
  if (!relevant) return { status:'NO_RELEVANT_JUDGMENTS', metrics:null };
  const out = {};
  for (const k of KS) {
    const hits=ordered.filter(h=>h.rank<=k);
    if (hits.some(h=>!grades.has(h.docId) || grades.get(h.docId)===null)) return {status:'UNJUDGED_RESULT',metrics:null};
    out[`pooledRecall@${k}`]=hits.filter(h=>grades.get(h.docId)>=1).length/relevant;
    const dcg=hits.reduce((n,h)=>n+(2**grades.get(h.docId)-1)/Math.log2(h.rank+1),0);
    const ideal=[...grades.values()].sort((a,b)=>b-a).slice(0,k);
    const idcg=ideal.reduce((n,g,i)=>n+(2**g-1)/Math.log2(i+2),0);
    out[`nDCG@${k}`]=idcg ? dcg/idcg : null;
  }
  const first=ordered.find(h=>h.rank<=10 && grades.get(h.docId)>=1);
  out['MRR@10']=first ? 1/first.rank : 0;
  return {status:'SCORED',metrics:out};
}

export function scorePool(rankingsBytes,pool,mapping,annotations=null) {
  const rankings=parse(rankingsBytes);
  assert.equal(rankings.schemaVersion,'retrieval-rankings-v1');
  assert.equal(pool.schemaVersion,'retrieval-blind-pool-v1');
  assert.equal(mapping.schemaVersion,'retrieval-private-pool-v1');
  const inputs={rankingsSha256:sha(rankingsBytes),corpusSha256:rankings.corpusSha256,configSha256:rankings.configSha256,queriesSha256:rankings.queriesSha256};
  for(const h of Object.values(inputs))assert(/^[a-f0-9]{64}$/.test(h),'invalid input SHA');
  assert.deepEqual(pool.inputs,inputs,'pool input hashes mismatch');
  assert.deepEqual(mapping.inputs,inputs,'mapping input hashes mismatch');
  const poolId=sha(JSON.stringify(inputs));
  assert.equal(pool.poolId,poolId,'pool binding mismatch'); assert.equal(mapping.poolId,poolId,'mapping pool binding mismatch');
  const blindContentSha256=sha(JSON.stringify(pool));
  assert.equal(mapping.blindContentSha256,blindContentSha256,'blind content hash mismatch');
  assert.equal(typeof mapping.coverage?.complete,'boolean','missing pool coverage');
  assert(Array.isArray(mapping.coverage.problems),'missing pool coverage problems');
  assert.equal(mapping.coverage.complete,mapping.coverage.problems.length===0,'inconsistent pool coverage');
  const rows=new Map(), repeatIds=new Set(), rankingQueries=new Map();
  assert(Array.isArray(rankings.runs)&&rankings.runs.length,'no runs');
  for(const run of rankings.runs) {
    assert(Number.isInteger(run.repeat)&&run.repeat>=1&&!repeatIds.has(run.repeat),'duplicate/invalid repeat');repeatIds.add(run.repeat);
    const seen=new Set();
    for(const q of run.queries) {
      assert(!seen.has(q.id),'duplicate query');seen.add(q.id);
      const metadata={query:q.query,city:q.city,split:q.split??null,familyId:q.familyId??null};
      if(rankingQueries.has(q.id)) assert.deepEqual(rankingQueries.get(q.id),metadata,'query metadata changed');
      else rankingQueries.set(q.id,metadata);
      for(const method of Object.keys(q.methods??{}))assert(METHODS.includes(method),'unknown method');
      for(const method of METHODS) {
        const result=q.methods?.[method]??{status:'MISSING_METHOD'};
        assert(['OK','ERROR','MISSING_METHOD'].includes(result.status),'invalid result status');
        const hits=result.status==='OK'?result.ranking:[];
        assert(Array.isArray(hits),'ranking must be array');
        const docs=new Set(),ranks=new Set();
        for(const h of hits) {
          assert(typeof h.docId==='string'&&h.docId,'invalid docId');
          assert(!docs.has(h.docId),'duplicate ranked document');docs.add(h.docId);
          assert(Number.isInteger(h.rank)&&h.rank>0&&!ranks.has(h.rank),'invalid/duplicate rank');ranks.add(h.rank);
        }
        if(hits.length)assert.deepEqual([...ranks].sort((a,b)=>a-b),Array.from({length:hits.length},(_,i)=>i+1),'rank gaps');
        rows.set(`${run.repeat}/${q.id}/${method}`,{repeat:run.repeat,queryId:q.id,method,...metadata,result});
      }
    }
  }
  const blind=new Map(), privateQueries=new Map();
  for(const q of pool.queries) {assert(!blind.has(q.anonymousQueryId),'duplicate anonymous query');blind.set(q.anonymousQueryId,q);}
  for(const q of mapping.queries) {
    assert(!privateQueries.has(q.queryId),'duplicate private query'); privateQueries.set(q.queryId,q);
    const b=blind.get(q.anonymousQueryId), metadata=rankingQueries.get(q.queryId);
    assert(b&&metadata,'unknown query mapping');
    assert.equal(b.query,metadata.query);assert.equal(b.city,metadata.city);
    for(const prop of ['city','split','familyId'])assert.equal(q[prop]??null,metadata[prop],`mapping ${prop} mismatch`);
    const bd=new Map();for(const d of b.documents){assert(!bd.has(d.anonymousDocumentId),'duplicate blind document');bd.set(d.anonymousDocumentId,d);}
    const ids=new Set(), anonymous=new Set(), actualSources=new Map();
    for(const row of rows.values())if(row.queryId===q.queryId&&row.result.status==='OK')for(const h of row.result.ranking.filter(h=>h.rank<=10)){
      if(!actualSources.has(h.docId))actualSources.set(h.docId,[]);
      actualSources.get(h.docId).push({repeat:row.repeat,method:row.method,rank:h.rank});
    }
    for(const d of q.documents) {
      assert(bd.has(d.anonymousDocumentId)&&!anonymous.has(d.anonymousDocumentId),'unknown/duplicate anonymous document');anonymous.add(d.anonymousDocumentId);
      assert(!ids.has(d.docId),'duplicate mapped document');ids.add(d.docId);
      assert.deepEqual(canonical(d.sources),canonical(actualSources.get(d.docId)??[]),'mapped sources mismatch');
    }
    assert.deepEqual([...ids].sort(),[...actualSources.keys()].sort(),'pool omits or adds ranked candidates');
    assert.equal(anonymous.size,bd.size,'blind/mapping document mismatch');
  }
  assert.equal(privateQueries.size,rankingQueries.size,'mapping queries incomplete');
  assert.equal(blind.size,privateQueries.size,'blind queries incomplete');
  assert.equal(new Set(mapping.queries.map(q=>q.anonymousQueryId)).size,blind.size,'duplicate private anonymous query');
  if(annotations) {
    assert.equal(annotations.blindContentSha256,blindContentSha256,'annotation blind content hash mismatch');
    if(annotations.reviewer?.type==='MODEL_ASSISTED_PRIMARY') validateModelAssistedAnnotations(annotations,pool,blindContentSha256);
    else validateAnnotations(annotations,pool,blindContentSha256);
  }
  const judgments=new Map((annotations?.judgments??[]).map(j=>[key(j.anonymousQueryId,j.anonymousDocumentId),j.grade]));
  const queryStates=new Map();
  for(const q of mapping.queries) {
    const grades=new Map(q.documents.map(d=>[d.docId,judgments.get(key(q.anonymousQueryId,d.anonymousDocumentId))??null]));
    queryStates.set(q.queryId,{grades,complete:grades.size>0&&[...grades.values()].every(g=>g!==null)});
  }
  const perQuery=[];
  for(const repeat of repeatIds)for(const [queryId,metadata]of rankingQueries)for(const method of METHODS) {
    const row=rows.get(`${repeat}/${queryId}/${method}`),state=queryStates.get(queryId);
    let assessment;
    if(!row)assessment={status:'MISSING_QUERY',metrics:null};
    else if(row.result.status!=='OK')assessment={status:row.result.status,metrics:null};
    else if(!state.complete)assessment={status:'UNJUDGED_POOL',metrics:null};
    else assessment=queryMetrics(row.result.ranking,state.grades);
    const poolCoverageProblems=mapping.coverage.problems.filter(p=>p.repeat===repeat&&p.queryId===queryId&&(p.method==null||p.method===method));
    perQuery.push({repeat,queryId,...metadata,method,...assessment,poolCoverageProblems,latencyMs:row?.result.latencyMs??null});
  }
  const groups=new Map();
  for(const r of perQuery){const k=JSON.stringify([r.city,r.split,r.method]);if(!groups.has(k))groups.set(k,[]);groups.get(k).push(r);}
  const summaries=[...groups.values()].map(rs=>{
    const completeQueries=[...new Set(rs.map(r=>r.queryId))].filter(id=>rs.filter(r=>r.queryId===id).every(r=>r.status==='SCORED'&&r.poolCoverageProblems.length===0));
    const metrics=Object.fromEntries(metricNames.map(m=>[m,mean(completeQueries.map(id=>mean(rs.filter(r=>r.queryId===id).map(r=>r.metrics[m]))))]));
    const counts={};for(const r of rs)counts[r.status]=(counts[r.status]??0)+1;
    return {city:rs[0].city,split:rs[0].split,method:rs[0].method,queryCount:new Set(rs.map(r=>r.queryId)).size,completeQueries:completeQueries.length,repeatQueryStatuses:counts,metrics:completeQueries.length?metrics:null};
  });
  const pairs=[];
  for(const [left,right]of [['hybrid_rrf','dense'],['hybrid_rerank','hybrid_rrf'],['bm25_jieba','dense'],['bm25_bigram','bm25_jieba']])for(const metric of metricNames){
    const compared=[];const excluded=[];
    for(const [id,metadata]of rankingQueries){
      const a=perQuery.filter(r=>r.queryId===id&&r.method===left),b=perQuery.filter(r=>r.queryId===id&&r.method===right);
      if([...a,...b].some(r=>r.status!=='SCORED'||r.poolCoverageProblems.length)){excluded.push({queryId:id,statuses:[...a,...b].filter(r=>r.status!=='SCORED'||r.poolCoverageProblems.length).map(r=>({method:r.method,repeat:r.repeat,status:r.status,poolCoverageProblems:r.poolCoverageProblems}))});continue;}
      const av=mean(a.map(r=>r.metrics[metric])),bv=mean(b.map(r=>r.metrics[metric])),diff=av-bv;
      compared.push({queryId:id,...metadata,left:av,right:bv,diff,outcome:Math.abs(diff)<=1e-12?'TIE':diff>0?'WIN':'LOSS'});
    }
    const grouped=new Map();for(const r of compared){const k=JSON.stringify([r.city,r.split]);if(!grouped.has(k))grouped.set(k,[]);grouped.get(k).push(r);}
    pairs.push({left,right,metric,excluded,byCitySplit:[...grouped.values()].map(rs=>({city:rs[0].city,split:rs[0].split,wins:rs.filter(r=>r.outcome==='WIN').length,losses:rs.filter(r=>r.outcome==='LOSS').length,ties:rs.filter(r=>r.outcome==='TIE').length,queries:rs,families:[...new Set(rs.map(r=>r.familyId))].map(familyId=>({familyId,queryCount:rs.filter(r=>r.familyId===familyId).length,meanDiff:mean(rs.filter(r=>r.familyId===familyId).map(r=>r.diff))}))}))});
  }
  const latency=METHODS.map(method=>{const all=perQuery.filter(r=>r.method===method),xs=all.map(r=>r.latencyMs).filter(n=>Number.isFinite(n)&&n>=0).sort((a,b)=>a-b);return{method,observations:xs.length,missingOrInvalid:all.length-xs.length,p50Ms:xs.length?xs[Math.ceil(xs.length*.5)-1]:null,p95Ms:xs.length?xs[Math.ceil(xs.length*.95)-1]:null,scope:'Recorded local retrieval stage latency; not browser or Java end-to-end latency'};});
  const scored=perQuery.filter(r=>r.status==='SCORED').length;
  const errors=perQuery.filter(r=>['ERROR','MISSING_METHOD','MISSING_QUERY'].includes(r.status));
  const modelAssisted=annotations?.reviewer?.type==='MODEL_ASSISTED_PRIMARY';
  const limitations=['pooledRecall uses judged Top-10 union, not exhaustive corpus relevance','Unjudged candidates are never assumed irrelevant; all pool judgments required per query','Incomplete Top-10 pools retain per-result mathematical values but are excluded from complete-query aggregates and paired comparisons; ungenerated candidates are not verified','Repeated runs are paired within query, not independent samples; families reported descriptively','City and split metrics are separate; no nationwide aggregate',modelAssisted?'AI-assisted provisional labels are not human gold and cannot authorize rerank adoption':'Human identity is a self-attestation, not independent verification'];
  return {schemaVersion:'retrieval-pool-scores-v1',poolId,inputs,status:!scored?'NOT_SCORED':mapping.coverage.complete&&perQuery.every(r=>['SCORED','NO_RELEVANT_JUDGMENTS'].includes(r.status))?'SCORED':'PARTIAL',qualityTrack:modelAssisted?'MODEL_ASSISTED_EXPLORATORY':'HUMAN_ANNOTATION',adoptionEligibility:modelAssisted?'INSUFFICIENT_NO_ENABLE':'REQUIRES_PRE_REGISTERED_ADOPTION_GATES',reviewer:annotations?.reviewer??null,limitations,poolCoverage:structuredClone(mapping.coverage),coverage:{queries:rankingQueries.size,fullyJudgedQueries:[...queryStates.values()].filter(s=>s.complete).length,judgmentsRequired:pool.queries.reduce((n,q)=>n+q.documents.length,0),judgmentsCompleted:[...judgments.values()].filter(v=>v!==null).length,repeatMethodRows:perQuery.length,scoredRows:scored,fullyCoveredScoredRows:perQuery.filter(r=>r.status==='SCORED'&&!r.poolCoverageProblems.length).length,noRelevantRows:perQuery.filter(r=>r.status==='NO_RELEVANT_JUDGMENTS').length,errors},summaries,pairs,perQuery,latency};
}

export async function scoreFiles({rankingsPath,poolPath,mappingPath,annotationsPath,outputPath}) {
  const [bytes,pool,mapping,annotations]=await Promise.all([readFile(rankingsPath),readFile(poolPath),readFile(mappingPath),annotationsPath?readFile(annotationsPath):null]);
  const report=scorePool(bytes,parse(pool),parse(mapping),annotations?parse(annotations):null);
  report.artifactSha256={pool:sha(pool),mapping:sha(mapping),annotations:annotations?sha(annotations):null};
  await writeFile(outputPath,JSON.stringify(report,null,2)+'\n',{flag:'wx',encoding:'utf8'});return report;
}
async function main(){const{values:v}=parseArgs({options:Object.fromEntries(['rankings','pool','mapping','annotations','output'].map(k=>[k,{type:'string'}]))});for(const k of ['rankings','pool','mapping','output'])assert(v[k],`--${k} required`);const r=await scoreFiles({rankingsPath:v.rankings,poolPath:v.pool,mappingPath:v.mapping,annotationsPath:v.annotations,outputPath:v.output});console.log(JSON.stringify({status:r.status,coverage:r.coverage},null,2));}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href)main().catch(e=>{console.error(e.message);process.exitCode=1;});
