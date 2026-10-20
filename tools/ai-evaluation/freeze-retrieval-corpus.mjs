import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { parseArgs } from 'node:util';
import { pathToFileURL } from 'node:url';

export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const jsonBytes = bytes => JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, ''));
const isUrl = value => {
  try { return ['https:', 'http:'].includes(new URL(value).protocol); } catch { return false; }
};
const requiredText = (value, label) => {
  assert.equal(typeof value, 'string', `${label} must be text`);
  assert(value.trim(), `${label} must not be blank`);
  return value;
};
function date(value) {
  assert(/^\d{4}-\d{2}-\d{2}$/.test(value), 'date must be YYYY-MM-DD');
  assert.equal(new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10), value, 'invalid calendar date');
  return value;
}
function refs(values) {
  assert(Array.isArray(values), 'sourceIds must be an array');
  return values.map(value => requiredText(value, 'sourceId'));
}

// This is a corpus identity/provenance manifest, not a second knowledge store or a fact judge.
export function freezeCorpus(corpusBytes, registryBytes, reviewBytes, asOf) {
  date(asOf);
  const docs = corpusBytes.toString('utf8').replace(/^\uFEFF/, '').split(/\r?\n/)
    .filter(line => line.trim()).map(line => JSON.parse(line));
  assert(docs.length, 'empty corpus');
  const registry = jsonBytes(registryBytes), review = jsonBytes(reviewBytes);
  assert(Array.isArray(registry), 'registry must contain an array');
  const sources = new Map();
  for (const row of registry) {
    requiredText(row.sourceId, 'registered sourceId');
    assert(isUrl(row.url), 'registered URL must be HTTP(S)');
    assert(!sources.has(row.sourceId), `duplicate registered sourceId ${row.sourceId}`);
    sources.set(row.sourceId, row);
  }
  assert.equal(review.schemaVersion, 'retrieval-source-review-v1');
  assert(Array.isArray(review.observations) && Array.isArray(review.claimReviews));
  const ids = new Set();
  for (const d of docs) {
    requiredText(d.docId, 'docId'); requiredText(d.content, 'content');
    requiredText(d.city, 'city'); requiredText(d.entityId, 'entityId');
    assert(!ids.has(d.docId), `duplicate docId ${d.docId}`); ids.add(d.docId);
    assert(Array.isArray(d.claims ?? []));
  }
  for (const id of sources.keys()) assert(!ids.has(id), `registered sourceId collides with document ID ${id}`);
  const observations = new Map();
  for (const o of review.observations) {
    assert(isUrl(o.url), 'observation URL must be HTTP(S)'); date(o.checkedAt);
    assert(o.checkedAt <= asOf, 'observation is later than asOf');
    assert(!observations.has(o.url), 'duplicate observation URL');
    assert(['PAGE_READ', 'SEARCH_INDEX_ONLY', 'INSUFFICIENT_BODY', 'TIMEOUT'].includes(o.access));
    observations.set(o.url, o);
  }
  const reviewKeys = new Set();
  for (const r of review.claimReviews) {
    const d = docs.find(d => d.docId === r.docId);
    assert(d, `review has unknown docId ${r.docId}`);
    assert((d.claims ?? []).some(c => c.key === r.claimKey), `unknown claim ${r.claimKey}`);
    assert(observations.has(r.url), 'claim review requires a source observation');
    assert(['SUPPORTED', 'PARTIAL', 'NOT_STATED', 'CONTRADICTED'].includes(r.support));
    assert(['ORIGINAL_REFERENCE', 'INDEPENDENT_CORROBORATION'].includes(r.relationship));
    const claim = d.claims.find(c => c.key === r.claimKey);
    const declared = [...refs(d.sourceIds ?? []), ...refs(claim.sourceIds ?? [])];
    if (r.relationship === 'ORIGINAL_REFERENCE') {
      assert(declared.some(s => (isUrl(s) ? s : sources.get(s)?.url) === r.url), 'new URL cannot repair original provenance by assertion');
    }
    const key = `${r.docId}\0${r.claimKey}\0${r.url}`;
    assert(!reviewKeys.has(key), 'duplicate claim review'); reviewKeys.add(key);
  }
  const resolve = id => ({
    id,
    url: isUrl(id) ? id : sources.get(id)?.url ?? null,
    resolution: isUrl(id) ? 'DIRECT_URL' : sources.has(id) ? 'RECOVERED_REGISTER' : ids.has(id) ? 'DOCUMENT_REFERENCE_NOT_EXTERNAL_SOURCE' : 'UNRESOLVED',
    declaredMetadata: sources.get(id) ?? null,
    observation: observations.get(isUrl(id) ? id : sources.get(id)?.url) ?? null,
  });
  const documents = docs.map((d, index) => {
    const allRefs = [...new Set([...refs(d.sourceIds ?? []), ...(d.claims ?? []).flatMap(c => refs(c.sourceIds ?? []))])];
    const expiry = d.validity?.expiresAt ?? null;
    if (expiry) date(expiry);
    return {
      docId: d.docId, entityId: d.entityId, city: d.city, topic: d.topic ?? null,
      recordIndex: index, contentSha256: sha256(d.content),
      // Hash the original record, including nested claims, without dropping nested keys.
      recordSha256: sha256(JSON.stringify(d)),
      title: d.title ?? null, validity: d.validity ?? {}, expiredAtAsOf: expiry ? expiry < asOf : null,
      topLevelSourceIds: refs(d.sourceIds ?? []), sourceReferences: allRefs.map(resolve),
      claimReviews: review.claimReviews.filter(r => r.docId === d.docId),
      textRetrievalEligible: true, currentFactGoldEligible: false,
      factStatus: 'NOT_ADMITTED_REQUIRES_CLAIM_FRESHNESS_AND_HUMAN_REVIEW',
      splitGroupStatus: 'UNREVIEWED_ENTITY_ALIASES_AND_NEAR_DUPLICATES',
    };
  });
  const uniqueRefs = [...new Set(documents.flatMap(d => d.sourceReferences.map(r => r.id)))];
  const count = f => documents.filter(f).length;
  const counts = field => Object.fromEntries([...new Set(documents.map(d => d[field]))].sort().map(v => [v, count(d => d[field] === v)]));
  return {
    schemaVersion: 'retrieval-corpus-freeze-v1', asOf,
    corpusSha256: sha256(corpusBytes), registrySha256: sha256(registryBytes), reviewSha256: sha256(reviewBytes),
    stage: 'TEXT_CORPUS_FROZEN_ANNOTATIONS_AND_MODELS_PENDING',
    meaning: 'Corpus identity and provenance observations only. Registered URLs, asset verified flags and agent reviews do not confer official origin, permission or human fact gold.',
    summary: { documents: documents.length, cities: counts('city'), entityIds: new Set(documents.map(d => d.entityId)).size,
      expired: count(d => d.expiredAtAsOf === true), currentFactGold: 0,
      uniqueSourceReferencesIncludingClaims: uniqueRefs.length,
      sourceReferenceStates: Object.fromEntries(['DIRECT_URL','RECOVERED_REGISTER','DOCUMENT_REFERENCE_NOT_EXTERNAL_SOURCE','UNRESOLVED'].map(s => [s, uniqueRefs.filter(id => resolve(id).resolution === s).length])),
      corpusDocumentsWithAnyRegisteredOrDirectURL: count(d => d.sourceReferences.some(r => r.url)),
      documentsWithAgentClaimReview: count(d => d.claimReviews.length > 0),
    }, documents,
  };
}

export function verifyFreeze(manifest, corpusBytes, registryBytes, reviewBytes) {
  const rebuilt = freezeCorpus(corpusBytes, registryBytes, reviewBytes, manifest.asOf);
  assert.deepEqual(manifest, rebuilt, 'frozen inputs or manifest changed');
  return { status: 'PASS_IDENTITY_CHECK_ONLY', ...rebuilt.summary };
}

async function main() {
  const { values: v } = parseArgs({ options: {
    corpus: { type: 'string' }, registry: { type: 'string' }, reviews: { type: 'string' },
    'as-of': { type: 'string' }, output: { type: 'string' }, verify: { type: 'string' },
  } });
  for (const k of ['corpus','registry','reviews']) requiredText(v[k], `--${k}`);
  assert(Boolean(v.verify) !== Boolean(v.output), 'choose --output or --verify');
  const [corpus, registry, reviews] = await Promise.all([v.corpus,v.registry,v.reviews].map(p => readFile(p)));
  if (v.verify) console.log(JSON.stringify(verifyFreeze(jsonBytes(await readFile(v.verify)), corpus, registry, reviews), null, 2));
  else {
    const manifest = freezeCorpus(corpus, registry, reviews, requiredText(v['as-of'], '--as-of'));
    await writeFile(v.output, `${JSON.stringify(manifest, null, 2)}\n`, { encoding: 'utf8', flag: 'wx' });
    console.log(JSON.stringify(manifest.summary, null, 2));
  }
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
