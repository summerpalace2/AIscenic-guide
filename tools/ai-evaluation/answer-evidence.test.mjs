import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { bindingFor, evaluateAnswers, evaluateCase } from './answer-evidence.mjs';

const sample = () => {
  const item = { id: 'sample', answer: '展馆周一闭馆。', evidence: [{ id: 'doc-1', text: '展馆每周一不开放。' }],
    claims: [{ id: 'c1', text: '展馆周一闭馆。', kind: 'FACTUAL', citationIds: ['doc-1'] }] };
  item.annotation = { ...bindingFor(item), reviewer: 'test-reviewer', reviewedAt: '2026-10-17', inventoryComplete: true,
    inventoryReview: 'One factual claim; all covered.', judgements: [{ claimId: 'c1', verdict: 'SUPPORTS', rationale: 'Explicit same closure day.' }] };
  return item;
};

test('valid manual support is scored separately from real-world truth', () => {
  const result = evaluateAnswers({ schemaVersion: 1, cases: [sample()] });
  assert.equal(result.status, 'SCORED');
  assert.equal(result.metrics.supportRateAmongJudgedFactualClaims, 1);
  assert.equal(result.metrics.fullyAnnotatedFactualSupportRate, 1);
  assert.equal(result.realWorldTruth, 'NOT_EVALUATED');
});

test('missing annotations never become an implicit perfect faithfulness score', () => {
  const item = sample(); delete item.annotation;
  const result = evaluateAnswers({ schemaVersion: 1, cases: [item] });
  assert.equal(result.status, 'NOT_SCORED');
  assert.equal(result.metrics.annotationCoverage, 0);
  assert.equal(result.metrics.supportRateAmongJudgedFactualClaims, null);
  assert.equal(result.metrics.fullyAnnotatedFactualSupportRate, null);
  assert.equal(result.metrics.citationValidity, 1);
});

for (const field of ['answer', 'evidence', 'claims']) {
  test(`${field} mutation invalidates the review binding`, () => {
    const item = sample();
    if (field === 'answer') item.answer += '请提前查看公告。';
    if (field === 'evidence') item.evidence[0].text += '节假日除外。';
    if (field === 'claims') item.claims[0].citationIds = [];
    const result = evaluateCase(item);
    assert.equal(result.status, 'NOT_SCORED');
    assert.ok(result.errors.some(error => error.startsWith('STALE_')));
  });
}

test('nonexistent citations cannot be counted as supported even with a support label', () => {
  const item = sample(); item.claims[0].citationIds = ['invented'];
  Object.assign(item.annotation, bindingFor(item));
  const result = evaluateAnswers({ schemaVersion: 1, cases: [item] });
  assert.equal(result.metrics.citationValidity, 0);
  assert.equal(result.metrics.supportRateAmongJudgedFactualClaims, 0);
});

test('uncited fact cannot earn support; justified abstention is measured separately', () => {
  const item = sample(); item.claims[0].citationIds = [];
  Object.assign(item.annotation, bindingFor(item));
  assert.equal(evaluateCase(item).claims[0].supported, false);
  item.claims[0].kind = 'ABSTENTION'; item.claims[0].text = '暂无法确认。'; item.answer = '暂无法确认。';
  Object.assign(item.annotation, bindingFor(item));
  const result = evaluateAnswers({ schemaVersion: 1, cases: [item] });
  assert.equal(result.metrics.supportRateAmongJudgedFactualClaims, null);
  assert.equal(result.metrics.justifiedAbstentionRateAmongJudged, 1);
});

test('partial annotation exposes coverage rather than an overall perfect score', () => {
  const item = sample(); item.answer += '门票免费。';
  item.claims.push({ id: 'c2', text: '门票免费。', kind: 'FACTUAL', citationIds: ['doc-1'] });
  Object.assign(item.annotation, bindingFor(item));
  const result = evaluateAnswers({ schemaVersion: 1, cases: [item] });
  assert.equal(result.status, 'PARTIALLY_SCORED');
  assert.equal(result.metrics.annotationCoverage, 0.5);
  assert.equal(result.metrics.supportRateAmongJudgedFactualClaims, 1);
  assert.equal(result.metrics.fullyAnnotatedFactualSupportRate, null);
});

test('duplicate or unknown labels invalidate review, not selective cherry picking', () => {
  for (const label of [{ claimId: 'c1', verdict: 'SUPPORTS', rationale: 'duplicate' },
    { claimId: 'fake', verdict: 'SUPPORTS', rationale: 'unknown' }]) {
    const item = sample(); item.annotation.judgements.push(label);
    assert.equal(evaluateCase(item).status, 'NOT_SCORED');
  }
});

test('invalid claim, evidence, and dataset identities are rejected', () => {
  const item = sample(); item.claims[0].text = '并不在回答中的内容';
  assert.throws(() => evaluateCase(item), /Invalid claim/);
  const other = sample(); other.evidence.push(other.evidence[0]);
  assert.throws(() => evaluateCase(other), /duplicate evidence/);
  assert.throws(() => evaluateAnswers({ schemaVersion: 1, cases: [sample(), sample()] }), /Duplicate case/);
});

test('claim inventory review is required; empty inventories receive no score', () => {
  const item = sample(); item.annotation.inventoryComplete = false;
  assert.equal(evaluateCase(item).status, 'NOT_SCORED');
  item.claims = []; delete item.annotation;
  const result = evaluateAnswers({ schemaVersion: 1, cases: [item] });
  assert.equal(result.status, 'NOT_SCORED');
  assert.equal(result.metrics.annotationCoverage, null);
});

test('word overlap is irrelevant: labelled contradiction and unsupported citation stay failures', () => {
  for (const verdict of ['CONTRADICTS', 'INSUFFICIENT']) {
    const item = sample(); item.annotation.judgements[0].verdict = verdict;
    assert.equal(evaluateCase(item).claims[0].supported, false);
  }
});

test('synthetic evaluation fixtures exercise all judgement categories with at least twenty claims', async () => {
  const dataset = JSON.parse(await readFile(new URL('../../ai/src/test/resources/ai-evaluation/answer-evidence-cases.json', import.meta.url), 'utf8'));
  const result = evaluateAnswers(dataset);
  assert.equal(dataset.purpose, 'EVALUATOR_CONTRACT_FIXTURES_NOT_MODEL_BENCHMARK');
  assert.ok(result.counts.claims >= 20);
  assert.equal(result.status, 'SCORED');
  assert.ok(result.metrics.citationValidity < 1);
  assert.ok(result.metrics.supportRateAmongJudgedFactualClaims < 1);
  assert.ok(result.counts.contradictedFactualClaims > 0);
  assert.ok(result.counts.justifiedAbstentions > 0);
  assert.ok(result.cases.every(c => c.errors.length === 0));
});
