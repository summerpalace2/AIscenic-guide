import test from 'node:test';
import assert from 'node:assert/strict';
import { ratio,latency,validateReport,summarizeIntent,summarizePlanning,summarizeRetrieval,summarizeReports } from './metrics.mjs';
const row=(id,metrics,extra={})=>({id,query:id,city:'北京市',expected:{},actual:{},metrics,status:'PASS',latencyMs:1,...extra});
test('empty denominators remain unknown rather than perfect',()=>assert.deepEqual(ratio(0,0),{numerator:0,denominator:0,value:null}));
test('latency rejects missing/negative data and uses nearest rank percentile',()=>{
  assert.equal(latency([{latencyMs:1},{latencyMs:4},{latencyMs:2}]).p95Ms,4);
  assert.throws(()=>latency([{latencyMs:-1}]));
});
test('manifest validation rejects missing cases and dataset drift',()=>{
  const {dataset:ds,report:r}=intentPair();
  validateReport(r,ds,'hash');assert.throws(()=>validateReport({...r,cases:[]},ds,'hash'));assert.throws(()=>validateReport(r,ds,'changed'));
});
test('operation accuracy denominator excludes unannotated fields; mutation classification is not write rate',()=>{
  const r={cases:[row('a',{exactMatch:true,operationCorrect:true,fieldChecks:{}},{expected:{operation:'QA',mutationAllowed:false},actual:{operation:'QA'}}),row('b',{exactMatch:false,operationCorrect:null,fieldChecks:{}})]};
  const s=summarizeIntent(r);assert.equal(s.operationAccuracy.denominator,1);assert.equal(s.exactAnnotatedFields.value,.5);assert.equal(s.inappropriateMutationIntent.value,0);assert.equal(s.realLlmAccuracy.value,null);
});
test('empty plan and clarification do not inflate feasible planning rate',()=>{
  const r={cases:[row('a',{eligibleForPlanning:true,nonEmpty:false,feasible:false,constraintsSatisfied:false,successful:false,fieldChecks:{}},{expected:{outcome:'PLAN'},status:'EMPTY_PLAN'}),row('b',{eligibleForPlanning:false,outcomeCorrect:true,fieldChecks:{}},{expected:{outcome:'CLARIFICATION'},status:'CLARIFICATION'})]};
  const s=summarizePlanning(r);assert.equal(s.eligiblePlanningCount,1);assert.equal(s.staticEstimatedFeasibility.value,0);assert.equal(s.nonPlanningOutcomeAccuracy.value,1);
  assert.equal(s.failures.length,1);
});
const rr=(id,gold,ids,unknown,leak=false)=>row(id,{recallAt3:gold.length?ids.filter(x=>gold.includes(x)).length/gold.length:null,recallAt5:gold.length?ids.filter(x=>gold.includes(x)).length/gold.length:null,mrr:gold.length?(ids.some(x=>gold.includes(x))?1/(ids.findIndex(x=>gold.includes(x))+1):0):null,crossCityLeak:leak},{expected:{relevantDocumentIds:gold},actual:{retrievedDocumentIds:ids,gateDecision:{isUnknown:unknown},candidates:ids.map(documentId=>({documentId,city:leak?'杭州':'北京',scope:'CITY'}))}});
test('document recall and unknown rejection use independent denominators',()=>{
  const s=summarizeRetrieval({cases:[rr('a',['doc'],['other','doc'],false),rr('b',[],['other'],false),rr('c',[],[],true)]});
  assert.equal(s.documentRecallAt5.value,1);assert.equal(s.mrr.value,.5);assert.equal(s.documentRecallAt5.denominator,1);assert.equal(s.unsupportedQueryAllowed.value,.5);assert.equal(s.gateRejectRecall.value,.5);
});
test('cross-city leakage and fake recall numbers cannot be hidden by a report',()=>{
  const r=rr('a',['doc'],['doc'],false,true);assert.equal(summarizeRetrieval({cases:[r]}).crossCityLeak.value,1);
  assert.throws(()=>summarizeRetrieval({cases:[{...r,metrics:{...r.metrics,recallAt5:0}}]}));
});
test('mixed runs are rejected instead of silently stitching stale evidence',()=>assert.throws(()=>summarizeReports({intent:{runId:'a'},planning:{runId:'a'},retrieval:{runId:'b'}})));
function intentPair(){
  const gold={id:'a',query:'a',city:'北京市',context:{activeDay:1},expected:{operation:'QA'}};
  const report={schemaVersion:'ai-evaluation-v1',suite:'intent',datasetSha256:'hash',cases:[row('a',{exactMatch:true,operationCorrect:true,fieldChecks:{operation:{expected:'QA',actual:'QA',correct:true,comparator:'EQUALS'}}},{status:'MATCH',input:structuredClone(gold),expected:structuredClone(gold.expected),actual:{operation:'QA'}})]};
  return {gold,report,dataset:{suite:'intent',cases:[gold]}};
}
test('real producer MATCH/MISMATCH shape is accepted while omitted labels cannot shrink denominator',()=>{
  const {report,dataset}=intentPair();validateReport(report,dataset,'hash');
  report.cases[0].metrics.operationCorrect=null;assert.throws(()=>validateReport(report,dataset,'hash'));
});
test('changed context, omitted checks and fabricated correctness are rejected',()=>{
  for(const change of [r=>r.cases[0].input.context.activeDay=2,r=>delete r.cases[0].metrics.fieldChecks.operation,r=>r.cases[0].metrics.fieldChecks.operation.correct=false]){
    const {report,dataset}=intentPair();change(report);assert.throws(()=>validateReport(report,dataset,'hash'));
  }
});
test('retrieval gold gate status and candidates must match the scored document identities',()=>{
  const c=rr('a',['doc'],['doc'],false);c.expected.retrievalStatus='RELEVANT';
  const dataset={cases:[{id:'a',query:'a',city:'北京市',relevantDocumentIds:['doc'],expectedStatus:'RELEVANT'}]};
  const report={schemaVersion:'ai-evaluation-v1',suite:'retrieval',datasetSha256:'hash',cases:[c]};
  validateReport(report,dataset,'hash');c.expected.retrievalStatus='UNKNOWN';assert.throws(()=>validateReport(report,dataset,'hash'));
  c.expected.retrievalStatus='RELEVANT';c.actual.candidates[0].documentId='different';assert.throws(()=>validateReport(report,dataset,'hash'));
});
