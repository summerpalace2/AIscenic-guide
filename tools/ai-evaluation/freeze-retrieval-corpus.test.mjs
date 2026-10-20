import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { freezeCorpus, verifyFreeze } from './freeze-retrieval-corpus.mjs';

const bytes = x => Buffer.from(JSON.stringify(x));
const doc = { docId:'doc', entityId:'entity', city:'city', content:'original', sourceIds:['registered'],
  claims:[{key:'a',sourceIds:['claim-only']},{key:'b',sourceIds:['doc']}], validity:{expiresAt:'2026-10-16'} };
const registry = [{sourceId:'registered',url:'https://example.org/page',urlStatus:'verified',sourceType:'official_venue'}];
const empty = {schemaVersion:'retrieval-source-review-v1',observations:[],claimReviews:[]};
const corpus = (...docs) => Buffer.from(docs.map(d => JSON.stringify(d)).join('\n'));
const freeze = (docs=[doc],reg=registry,review=empty) => freezeCorpus(corpus(...docs),bytes(reg),bytes(review),'2026-10-17');

test('claim-only and self references cannot disappear from provenance counts', () => {
  const m=freeze(); assert.equal(m.summary.uniqueSourceReferencesIncludingClaims,3);
  assert.equal(m.summary.sourceReferenceStates.UNRESOLVED,1);
  assert.equal(m.summary.sourceReferenceStates.DOCUMENT_REFERENCE_NOT_EXTERNAL_SOURCE,1);
  assert.equal(m.documents[0].expiredAtAsOf,true);
});
test('asset verified and official labels do not grant fact gold or imply page was read', () => {
  const d=freeze().documents[0]; assert.equal(d.currentFactGoldEligible,false);
  assert.equal(d.sourceReferences[0].observation,null); assert.equal(d.textRetrievalEligible,true);
});
test('frozen source drift, nested claim drift and altered manifest fail identity verification', () => {
  const m=freeze(); assert.equal(verifyFreeze(m,corpus(doc),bytes(registry),bytes(empty)).status,'PASS_IDENTITY_CHECK_ONLY');
  assert.throws(()=>verifyFreeze(m,corpus({...doc,claims:[{key:'changed'}]}),bytes(registry),bytes(empty)));
  assert.throws(()=>verifyFreeze({...m,summary:{...m.summary,currentFactGold:1}},corpus(doc),bytes(registry),bytes(empty)));
});
test('new corroborating URL cannot be relabeled the original source', () => {
  const review={...empty,observations:[{url:'https://example.org/new',checkedAt:'2026-10-17',access:'PAGE_READ'}],
    claimReviews:[{docId:'doc',claimKey:'a',url:'https://example.org/new',support:'PARTIAL',relationship:'ORIGINAL_REFERENCE'}]};
  assert.throws(()=>freeze([doc],registry,review));
  review.claimReviews[0].relationship='INDEPENDENT_CORROBORATION'; assert.equal(freeze([doc],registry,review).summary.currentFactGold,0);
});
test('a source named only by a sibling claim cannot become this claim original reference', () => {
  const d={...doc,sourceIds:[],claims:[{key:'a',sourceIds:['claim-only']},{key:'b',sourceIds:['registered']}]};
  const review={...empty,observations:[{url:registry[0].url,checkedAt:'2026-10-17',access:'PAGE_READ'}],
    claimReviews:[{docId:'doc',claimKey:'a',url:registry[0].url,support:'SUPPORTED',relationship:'ORIGINAL_REFERENCE'}]};
  assert.throws(()=>freeze([d],registry,review));
});
test('registry names cannot override internal document references', () => {
  assert.throws(()=>freeze([doc],[...registry,{sourceId:'doc',url:'https://example.org/pretend-origin'}]));
});
test('unknown doc/claim references, duplicate IDs and registry conflicts fail', () => {
  assert.throws(()=>freeze([doc,doc])); assert.throws(()=>freeze([doc],[...registry,...registry]));
  assert.throws(()=>freeze([doc],registry,{...empty,claimReviews:[{docId:'missing'}]}));
});
test('invalid dates are rejected, missing expiry stays unknown, boundary day is not expired', () => {
  assert.throws(()=>freeze([{...doc,validity:{expiresAt:'2026-02-30'}}]));
  assert.equal(freeze([{...doc,validity:{}}]).documents[0].expiredAtAsOf,null);
  assert.equal(freeze([{...doc,validity:{expiresAt:'2026-10-17'}}]).documents[0].expiredAtAsOf,false);
});
test('agent support review cannot silently become human fact gold', () => {
  const review={...empty,observations:[{url:registry[0].url,checkedAt:'2026-10-17',access:'PAGE_READ'}],
    claimReviews:[{docId:'doc',claimKey:'a',url:registry[0].url,support:'SUPPORTED',relationship:'ORIGINAL_REFERENCE'}]};
  assert.equal(freeze([doc],registry,review).summary.documentsWithAgentClaimReview,1);
  assert.equal(freeze([doc],registry,review).documents[0].currentFactGoldEligible,false);
  review.observations[0].checkedAt='2026-10-18';assert.throws(()=>freeze([doc],registry,review));
});

test('CLI creates immutable output, verifies exact inputs and rejects later corpus changes', async () => {
  const dir=await mkdtemp(join(tmpdir(),'retrieval-freeze-'));
  try {
    const paths={corpus:join(dir,'corpus.jsonl'),registry:join(dir,'registry.json'),reviews:join(dir,'reviews.json')};
    await Promise.all([writeFile(paths.corpus,corpus(doc)),writeFile(paths.registry,bytes(registry)),writeFile(paths.reviews,bytes(empty))]);
    const args=Object.entries(paths).flatMap(([key,path])=>[`--${key}`,path]);
    const output=join(dir,'manifest.json');
    const run=tail=>spawnSync(process.execPath,[fileURLToPath(new URL('./freeze-retrieval-corpus.mjs',import.meta.url)),...args,...tail],{encoding:'utf8'});
    assert.equal(run(['--as-of','2026-10-17','--output',output]).status,0);
    const original=await readFile(output,'utf8');
    assert.equal(run(['--verify',output]).status,0);
    assert.notEqual(run(['--as-of','2026-10-17','--output',output]).status,0);
    assert.equal(await readFile(output,'utf8'),original);
    await writeFile(paths.corpus,corpus({...doc,content:'changed'}));
    assert.notEqual(run(['--verify',output]).status,0);
  } finally { await rm(dir,{recursive:true,force:true}); }
});
