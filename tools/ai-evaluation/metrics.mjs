import assert from 'node:assert/strict';
import { isDeepStrictEqual } from 'node:util';

export const ratio = (numerator, denominator) => ({ numerator, denominator, value: denominator ? numerator / denominator : null });
const average = values => values.length ? values.reduce((a, b) => a + b, 0) / values.length : null;
const city = value => String(value ?? '').trim().replace(/市$/, '');
const bool = value => { assert.equal(typeof value, 'boolean', 'Missing boolean metric'); return value; };
const unique = values => new Set(values);
const families = cases => unique(cases.map(c => c.familyId ?? c.input?.familyId ?? c.input?.family ?? 'UNSPECIFIED')).size;
const rate = (cases, predicate) => ratio(cases.filter(predicate).length, cases.length);
function grouped(cases,key,predicate) {
  const groups=Map.groupBy(cases,key);
  return Object.fromEntries([...groups].map(([name,rows])=>[name,{count:rows.length,...rate(rows,predicate)}]));
}
const MUTATIONS = ['ADD_STOP','REMOVE_STOP','REPLACE_STOP','REPLAN_DAY','REDUCE_DENSITY','APPLY_REPLACEMENT','PLAN'];
const string = v => v == null ? '' : String(v).trim();
function validateCheck(check, expected, actual, field) {
  assert.deepEqual(check.expected, expected, `Field label changed: ${field}`);
  assert.deepEqual(check.actual, actual, `Field actual changed: ${field}`);
  const notContains=field.endsWith('NotContains'), contains=!notContains && field.endsWith('Contains');
  const comparator=notContains?'NOT_CONTAINS':contains?'CONTAINS':'EQUALS';
  assert.equal(check.comparator,comparator);
  const found=Array.isArray(actual)?actual.some(v=>string(v).includes(string(expected))):string(actual).includes(string(expected));
  const correct=contains||notContains ? actual!=null && (notContains?!found:found) : isDeepStrictEqual(expected,actual);
  assert.equal(check.correct,correct,`Incorrect field scoring: ${field}`);
}

export function latency(cases) {
  const values = cases.map(c => c.latencyMs);
  assert(values.every(v => Number.isFinite(v) && v >= 0), 'Invalid latency');
  values.sort((a, b) => a - b);
  const percentile = p => values.length ? values[Math.max(0, Math.ceil(values.length * p) - 1)] : null;
  return { count: values.length, meanMs: average(values), p50Ms: percentile(.5), p95Ms: percentile(.95), scope: 'In-process component latency; not HTTP/model/provider latency or cold start' };
}

export function validateReport(report, dataset, datasetSha256) {
  assert.equal(report.schemaVersion, 'ai-evaluation-v1');
  assert.equal(report.suite, dataset.suite ?? 'retrieval');
  assert.equal(report.datasetSha256, datasetSha256, 'Dataset fingerprint mismatch');
  assert(Array.isArray(report.cases) && Array.isArray(dataset.cases));
  const actualIds = report.cases.map(c => c.id), expectedIds = dataset.cases.map(c => c.id);
  assert.equal(unique(actualIds).size, actualIds.length, 'Duplicate output IDs');
  assert.equal(unique(expectedIds).size, expectedIds.length, 'Duplicate dataset IDs');
  assert.deepEqual([...actualIds].sort(), [...expectedIds].sort(), 'Missing or extra output cases');
  const gold = new Map(dataset.cases.map(c => [c.id, c]));
  for (const c of report.cases) {
    assert.equal(c.query, gold.get(c.id).query, 'Input changed');
    assert.equal(c.city, gold.get(c.id).city, 'Query city changed');
    if (report.suite === 'retrieval') {
      assert.deepEqual(c.expected.relevantDocumentIds, gold.get(c.id).relevantDocumentIds, 'Gold document IDs changed');
      assert.equal(c.expected.retrievalStatus,gold.get(c.id).expectedStatus,'Gold gate label changed');
      assert.deepEqual(c.actual.candidates.map(d=>d.documentId),c.actual.retrievedDocumentIds,'Candidate IDs differ from scored IDs');
    } else {
      assert.deepEqual(c.expected, gold.get(c.id).expected, 'Expected labels changed');
      assert(Object.keys(c.expected).length>0,'Unlabelled case cannot earn an exact-match score');
      assert.deepEqual(c.input,gold.get(c.id),'Original context or requirements changed');
      assert.deepEqual(Object.keys(c.metrics.fieldChecks).sort(),Object.keys(c.expected).sort(),'Missing annotated field checks');
      for(const [field,expected] of Object.entries(c.expected)) {
        const key=field.endsWith('NotContains')?field.slice(0,-11):field.endsWith('Contains')?field.slice(0,-8):field;
        const actual=field==='mutationAllowed'?MUTATIONS.includes(c.actual.operation):c.actual[key];
        validateCheck(c.metrics.fieldChecks[field],expected,actual ?? null,field);
      }
      const allFields=Object.values(c.metrics.fieldChecks).every(v=>v.correct);
      if(report.suite==='intent') {
        assert.equal(c.metrics.exactMatch,allFields,'Exact match differs from annotated fields');
        assert.equal(c.metrics.operationCorrect,Object.hasOwn(c.expected,'operation')?c.metrics.fieldChecks.operation.correct:null,'Operation denominator differs from gold');
      } else {
        const eligible=c.expected.outcome==='PLAN';
        assert.equal(c.metrics.eligibleForPlanning,eligible,'Planning denominator differs from gold');
        const requirements=gold.get(c.id).requirements ?? {}, keys=[];
        for(const name of requirements.requiredVenueNames ?? [])keys.push(`requiredVenue:${name}`);
        for(const category of requirements.forbiddenCategories ?? [])keys.push(`forbiddenCategory:${category}`);
        if(Object.hasOwn(requirements,'maxWalkingDifficulty'))keys.push('lowWalkingEvidence');
        if(eligible)keys.push('requestedDayCount','allRequestedDaysPopulated','allSelectedInRequestedCity');
        assert.deepEqual(Object.keys(c.metrics.requirementChecks).sort(),keys.sort(),'Missing requirement checks');
        assert.equal(c.metrics.constraintsSatisfied,allFields && Object.values(c.metrics.requirementChecks).every(v=>v.correct));
        assert.equal(c.metrics.outcomeCorrect,c.expected.outcome===c.actual.outcome);
        if(!eligible)assert.equal(c.metrics.feasible,null,'Non-plan must not enter feasibility denominator');
        assert.equal(c.metrics.successful,c.metrics.nonEmpty && c.metrics.feasible===true && c.metrics.constraintsSatisfied);
      }
    }
    const allowed=report.suite==='intent'?['MATCH','MISMATCH']:report.suite==='planning'?['GENERATED','EMPTY_PLAN','CLARIFICATION','NO_CONFIRMED_DURATION','NO_CANDIDATES','COMPONENT_ERROR']:['PASS','QUALITY_FAILURE'];
    assert(allowed.includes(c.status), `Unexpected case status ${c.status}`);
    assert(c.expected && c.actual && c.metrics);
  }
  latency(report.cases);
  return report;
}

function fields(cases) {
  const totals = {};
  for (const c of cases) for (const [name, check] of Object.entries(c.metrics.fieldChecks ?? {})) {
    assert.equal(typeof check.correct, 'boolean', `Missing field correctness ${name}`);
    totals[name] ??= { numerator: 0, denominator: 0 };
    totals[name].denominator++;
    if (check.correct) totals[name].numerator++;
  }
  return Object.fromEntries(Object.entries(totals).map(([k, v]) => [k, ratio(v.numerator, v.denominator)]));
}

export function summarizeIntent(report) {
  const rows = report.cases;
  const annotatedOperation = rows.filter(c => Object.hasOwn(c.expected,'operation'));
  const labelledNonMutations = rows.filter(c => c.expected.mutationAllowed === false);
  return {
    mode: report.executionMode, count: rows.length, familyCount: families(rows),
    exactAnnotatedFields: rate(rows, c => bool(c.metrics.exactMatch)),
    singleTurnClassifier: rate(rows.filter(c=>c.metrics.evaluationPath!=='TWO_TURN_PREVIEW_DISABLED_LLM'),c=>bool(c.metrics.exactMatch)),
    twoTurnDisabledLlmPreview: rate(rows.filter(c=>c.metrics.evaluationPath==='TWO_TURN_PREVIEW_DISABLED_LLM'),c=>bool(c.metrics.exactMatch)),
    byCity:grouped(rows,c=>c.city,c=>bool(c.metrics.exactMatch)),
    byFamily:grouped(rows,c=>c.input?.familyId ?? 'UNSPECIFIED',c=>bool(c.metrics.exactMatch)),
    operationAccuracy: rate(annotatedOperation, c => bool(c.metrics.operationCorrect)),
    inappropriateMutationIntent: rate(labelledNonMutations, c => ['ADD_STOP','REMOVE_STOP','REPLACE_STOP','REPLAN_DAY','REDUCE_DENSITY','APPLY_REPLACEMENT','PLAN'].includes(c.actual.operation)),
    inappropriateMutationBoundary: 'Classifier output only, not actual state mutation',
    annotatedFields: fields(rows), latency: latency(rows),
    failures: rows.filter(c => !c.metrics.exactMatch).map(c => ({ id:c.id, query:c.query, expected:c.expected, actual:c.actual,
      failedChecks:Object.fromEntries(Object.entries(c.metrics.fieldChecks).filter(([, v])=>!v.correct)) })),
    realLlmAccuracy: { status:'NOT_RUN', value:null },
  };
}

export function summarizePlanning(report) {
  const rows = report.cases, eligible = rows.filter(c => c.expected.outcome==='PLAN');
  const nonPlans = rows.filter(c => c.expected.outcome!=='PLAN');
  return {
    mode:report.executionMode, count:rows.length, familyCount:families(rows), eligiblePlanningCount:eligible.length,
    nonEmpty:rate(eligible,c=>bool(c.metrics.nonEmpty)),
    staticEstimatedFeasibility:rate(eligible,c=>bool(c.metrics.feasible)),
    annotatedRequirementsSatisfied:rate(eligible,c=>bool(c.metrics.constraintsSatisfied)),
    annotatedPlanningSuccess:rate(eligible,c=>bool(c.metrics.successful)),
    byCity:grouped(eligible,c=>c.city,c=>bool(c.metrics.successful)),
    byFamily:grouped(eligible,c=>c.input?.familyId ?? 'UNSPECIFIED',c=>bool(c.metrics.successful)),
    nonPlanningOutcomeAccuracy:rate(nonPlans,c=>bool(c.metrics.outcomeCorrect)),
    annotatedFields:fields(rows), latency:latency(rows),
    failures:rows.filter(c=>c.expected.outcome==='PLAN'?!c.metrics.successful:!c.metrics.outcomeCorrect).map(c=>({ id:c.id, query:c.query, expected:c.expected, actual:c.actual, metrics:c.metrics })),
    measurementBoundary:'Catalog/static-estimated engine and annotated requirement subset, not full dynamic first-planning service, real routes or complete natural-language satisfaction',
    realWorldFeasibility:{status:'NOT_RUN',value:null},
  };
}

export function summarizeRetrieval(report) {
  const rows = report.cases.map(c => {
    const relevant = unique(c.expected.relevantDocumentIds), retrieved = c.actual.retrievedDocumentIds;
    assert(Array.isArray(retrieved) && unique(retrieved).size === retrieved.length, 'Duplicate or invalid retrieved IDs');
    const answerable = relevant.size > 0;
    const recall = k => answerable ? [...relevant].filter(id=>retrieved.slice(0,k).includes(id)).length / relevant.size : null;
    const first = retrieved.findIndex(id=>relevant.has(id));
    const unknown = bool(c.actual.gateDecision.isUnknown);
    const leak = c.actual.candidates.some(d=>d.scope!=='GLOBAL' && (d.scope!=='CITY' || city(d.city)!==city(c.city)));
    assert.equal(c.metrics.recallAt3,recall(3)); assert.equal(c.metrics.recallAt5,recall(5));
    assert.equal(c.metrics.mrr,answerable ? (first<0?0:1/(first+1)) : null);
    assert.equal(c.metrics.crossCityLeak,leak);
    return { ...c, answerable, unknown, leak, recallAt3:recall(3), recallAt5:recall(5), mrr:answerable?(first<0?0:1/(first+1)):null };
  });
  const answerable = rows.filter(c=>c.answerable), unknownGold = rows.filter(c=>!c.answerable);
  const rejected = rows.filter(c=>c.unknown), correctReject = unknownGold.filter(c=>c.unknown).length;
  return {
    mode:report.executionMode,count:rows.length,familyCount:families(rows),answerableCount:answerable.length,noAnswerCount:unknownGold.length,
    documentRecallAt3:{value:average(answerable.map(c=>c.recallAt3)),denominator:answerable.length},
    documentRecallAt5:{value:average(answerable.map(c=>c.recallAt5)),denominator:answerable.length},
    mrr:{value:average(answerable.map(c=>c.mrr)),denominator:answerable.length},
    retrievalBoundary:'Document ID ranking before evidence gate, after local fallback candidate limit 5',
    gateRejectPrecision:ratio(correctReject,rejected.length),gateRejectRecall:ratio(correctReject,unknownGold.length),
    unsupportedQueryAllowed:rate(unknownGold,c=>!c.unknown),answerableQueryRejected:rate(answerable,c=>c.unknown),
    crossCityLeak:rate(rows,c=>c.leak),latency:latency(rows),
    failures:rows.filter(c=>c.leak || (c.answerable ? c.unknown || c.recallAt5<1 : !c.unknown)).map(c=>({id:c.id,query:c.query,city:c.city,expected:c.expected,actual:c.actual,metrics:c.metrics})),
    strategies:{localFallback:'EXECUTED',dense:'NOT_RUN',hybrid:'NOT_RUN',bm25:'NOT_RUN'},
    answerFaithfulness:{status:'NOT_SCORED',value:null},
  };
}

export function summarizeReports(reports) {
  assert.equal(new Set(Object.values(reports).map(r=>r.runId)).size,1,'Mixed run IDs');
  return { schemaVersion:'ai-evaluation-summary-v1',runId:reports.intent.runId,
    intent:summarizeIntent(reports.intent),planning:summarizePlanning(reports.planning),retrieval:summarizeRetrieval(reports.retrieval),
    modelCalls:0,providerTokenUsage:null,tokenUsageStatus:'NOT_APPLICABLE_OFFLINE_NO_MODEL_CALLS',
    overallAiAccuracy:null,overallAiAccuracyReason:'Different tasks and controlled component modes must not be merged into a single AI score',
    limitations:['Agent-authored provisional labels, peer-reviewed; not independently human-adjudicated gold.',
      'Cases share semantic families, so case count is not independent sample size.',
      'No live model, dense/hybrid vector retrieval, supplier truth or complete answer-faithfulness score measured.'] };
}
