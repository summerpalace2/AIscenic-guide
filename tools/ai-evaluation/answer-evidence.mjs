/**
 * Reviewer-labelled evidence audit, not an automatic semantic judge.
 * Claims must be exact substrings of the answer. Labels bind the full answer,
 * evidence and claim inventory; changing any of them invalidates the review.
 * Fixture scores validate this audit contract, never production model quality.
 */
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const VERDICTS = new Set(['SUPPORTS', 'CONTRADICTS', 'INSUFFICIENT']);
const hash = value => createHash('sha256').update(value, 'utf8').digest('hex');
const ratio = (n, d) => d === 0 ? null : n / d;
const requiredText = value => typeof value === 'string' && value.trim().length > 0;

export function bindingFor(item) {
  return {
    answerSha256: hash(item.answer),
    evidenceSha256: hash(JSON.stringify(item.evidence.map(({ id, text }) => ({ id, text })))),
    claimsSha256: hash(JSON.stringify(item.claims.map(({ id, text, kind, citationIds }) => ({ id, text, kind, citationIds })))),
  };
}

function validateCase(item) {
  if (!requiredText(item.id) || !requiredText(item.answer) || !Array.isArray(item.evidence) || !Array.isArray(item.claims)) {
    throw new Error('Each case requires id, answer, evidence[] and claims[]');
  }
  const seenEvidence = new Set();
  for (const evidence of item.evidence) {
    if (!requiredText(evidence.id) || !requiredText(evidence.text) || seenEvidence.has(evidence.id)) throw new Error(`Invalid or duplicate evidence in ${item.id}`);
    seenEvidence.add(evidence.id);
  }
  const seenClaims = new Set();
  for (const claim of item.claims) {
    if (!requiredText(claim.id) || seenClaims.has(claim.id) || !requiredText(claim.text) || !item.answer.includes(claim.text)
      || !['FACTUAL', 'ABSTENTION'].includes(claim.kind) || !Array.isArray(claim.citationIds)
      || claim.citationIds.some(id => !requiredText(id)) || new Set(claim.citationIds).size !== claim.citationIds.length) {
      throw new Error(`Invalid claim inventory in ${item.id}`);
    }
    seenClaims.add(claim.id);
  }
}

export function evaluateCase(item) {
  validateCase(item);
  const binding = bindingFor(item);
  const errors = [];
  const annotation = item.annotation;
  const labels = new Map();
  if (annotation) {
    if (!requiredText(annotation.reviewer) || !requiredText(annotation.reviewedAt) || !requiredText(annotation.inventoryReview)
      || annotation.inventoryComplete !== true || !Array.isArray(annotation.judgements)) errors.push('INVALID_REVIEW_METADATA');
    for (const key of Object.keys(binding)) if (annotation[key] !== binding[key]) errors.push(`STALE_${key}`);
    for (const label of annotation.judgements ?? []) {
      if (!item.claims.some(c => c.id === label.claimId) || labels.has(label.claimId)
        || !VERDICTS.has(label.verdict) || !requiredText(label.rationale)) errors.push('INVALID_JUDGEMENT');
      else labels.set(label.claimId, label);
    }
  }
  const reviewValid = Boolean(annotation) && errors.length === 0;
  const evidenceIds = new Set(item.evidence.map(e => e.id));
  const claims = item.claims.map(claim => {
    const missingCitationIds = claim.citationIds.filter(id => !evidenceIds.has(id));
    const verdict = reviewValid ? labels.get(claim.id)?.verdict ?? null : null;
    return {
      id: claim.id, kind: claim.kind, citationCount: claim.citationIds.length, missingCitationIds,
      verdict, scored: verdict !== null,
      supported: verdict === 'SUPPORTS' && missingCitationIds.length === 0 && (claim.kind === 'ABSTENTION' || claim.citationIds.length > 0),
    };
  });
  const factual = claims.filter(c => c.kind === 'FACTUAL');
  const judgedFacts = factual.filter(c => c.scored);
  const judged = claims.filter(c => c.scored);
  const citationCount = claims.reduce((sum, c) => sum + c.citationCount, 0);
  const missing = claims.reduce((sum, c) => sum + c.missingCitationIds.length, 0);
  return {
    id: item.id,
    status: judged.length === 0 ? 'NOT_SCORED' : judged.length === claims.length ? 'SCORED' : 'PARTIALLY_SCORED',
    binding, errors, claims,
    reviewer: reviewValid ? { name: annotation.reviewer, reviewedAt: annotation.reviewedAt } : null,
    counts: {
      claims: claims.length, judgedClaims: judged.length, factualClaims: factual.length, judgedFactualClaims: judgedFacts.length,
      supportedFactualClaims: judgedFacts.filter(c => c.supported).length,
      contradictedFactualClaims: judgedFacts.filter(c => c.verdict === 'CONTRADICTS').length,
      abstentions: claims.filter(c => c.kind === 'ABSTENTION').length,
      judgedAbstentions: judged.filter(c => c.kind === 'ABSTENTION').length,
      justifiedAbstentions: judged.filter(c => c.kind === 'ABSTENTION' && c.supported).length,
      citationReferences: citationCount, validCitationReferences: citationCount - missing,
      citedFactualClaims: factual.filter(c => c.citationCount > 0 && c.missingCitationIds.length === 0).length,
    },
    realWorldTruth: 'NOT_EVALUATED',
    inventoryCompleteness: reviewValid ? 'REVIEWER_ATTESTED_NOT_AUTOMATICALLY_VERIFIED' : 'NOT_VERIFIED',
  };
}

export function evaluateAnswers(dataset) {
  if (dataset.schemaVersion !== 1 || !Array.isArray(dataset.cases)) throw new Error('Expected schemaVersion:1 and cases[]');
  const ids = new Set();
  const cases = dataset.cases.map(item => {
    if (ids.has(item.id)) throw new Error(`Duplicate case id: ${item.id}`);
    ids.add(item.id);
    return evaluateCase(item);
  });
  const counts = cases.reduce((sum, item) => {
    for (const [key, value] of Object.entries(item.counts)) sum[key] = (sum[key] ?? 0) + value;
    return sum;
  }, {});
  const n = key => counts[key] ?? 0;
  return {
    schemaVersion: 1, evaluator: 'manual-bound-answer-evidence-v1', purpose: dataset.purpose ?? 'UNSPECIFIED',
    status: cases.length === 0 || cases.every(c => c.status === 'NOT_SCORED') ? 'NOT_SCORED'
      : cases.every(c => c.status === 'SCORED') ? 'SCORED' : 'PARTIALLY_SCORED',
    counts,
    metrics: {
      citationValidity: ratio(n('validCitationReferences'), n('citationReferences')),
      factualCitationCoverage: ratio(n('citedFactualClaims'), n('factualClaims')),
      annotationCoverage: ratio(n('judgedClaims'), n('claims')),
      supportRateAmongJudgedFactualClaims: ratio(n('supportedFactualClaims'), n('judgedFactualClaims')),
      fullyAnnotatedFactualSupportRate: n('claims') > 0 && n('judgedClaims') === n('claims')
        ? ratio(n('supportedFactualClaims'), n('factualClaims')) : null,
      contradictionRateAmongJudgedFactualClaims: ratio(n('contradictedFactualClaims'), n('judgedFactualClaims')),
      justifiedAbstentionRateAmongJudged: ratio(n('justifiedAbstentions'), n('judgedAbstentions')),
    },
    realWorldTruth: 'NOT_EVALUATED',
    limitations: ['Semantic labels are imported reviewer judgements; this tool does not infer entailment or authenticate reviewer identity.',
      'Claim inventory completeness and structured citation attribution are reviewer-attested, not automatically verified.',
      'Source support does not establish current real-world truth.',
      'Fixture results verify evaluator behaviour, not deployed-model faithfulness.'],
    cases,
  };
}

async function main(argv) {
  const inputIndex = argv.indexOf('--input');
  const outputIndex = argv.indexOf('--output');
  if (inputIndex < 0 || !argv[inputIndex + 1]) throw new Error('Usage: node answer-evidence.mjs --input FILE [--output FILE] [--annotation-template]');
  const dataset = JSON.parse(await readFile(argv[inputIndex + 1], 'utf8'));
  const result = argv.includes('--annotation-template') ? {
    schemaVersion: 1, purpose: dataset.purpose,
    cases: dataset.cases.map(item => {
      validateCase(item);
      return { ...item, annotation: { ...bindingFor(item), reviewer: '', reviewedAt: '', inventoryComplete: false, inventoryReview: '',
        judgements: item.claims.map(claim => ({ claimId: claim.id, verdict: null, rationale: '' })) } };
    }),
  } : evaluateAnswers(dataset);
  const text = `${JSON.stringify(result, null, 2)}\n`;
  if (outputIndex >= 0) {
    if (!argv[outputIndex + 1]) throw new Error('--output requires a file');
    await writeFile(argv[outputIndex + 1], text, 'utf8');
  }
  process.stdout.write(text);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).catch(error => { process.stderr.write(`${error.message}\n`); process.exitCode = 1; });
}
