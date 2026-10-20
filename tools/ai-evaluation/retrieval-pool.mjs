import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { parseArgs } from 'node:util';
import { pathToFileURL } from 'node:url';

export const METHODS = ['dense', 'bm25_jieba', 'hybrid_rrf', 'hybrid_rerank', 'bm25_bigram'];
const sha = value => createHash('sha256').update(value).digest('hex');
const parse = bytes => JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, ''));
const text = (value, label) => { assert(typeof value === 'string' && value.trim(), `${label} must be nonempty text`); return value; };

export function buildPool(rankingsBytes, corpusBytes) {
  const rankings = parse(rankingsBytes);
  assert.equal(rankings.schemaVersion, 'retrieval-rankings-v1');
  assert.equal(rankings.corpusSha256, sha(corpusBytes), 'corpus hash mismatch');
  for (const key of ['configSha256', 'queriesSha256']) assert(/^[a-f0-9]{64}$/.test(rankings[key]), `invalid ${key}`);
  const corpus = new Map();
  for (const line of corpusBytes.toString('utf8').replace(/^\uFEFF/, '').split(/\r?\n/).filter(s => s.trim())) {
    const d = JSON.parse(line); text(d.docId, 'docId'); text(d.city, 'document city'); text(d.content, 'document content');
    assert(!corpus.has(d.docId), `duplicate corpus docId ${d.docId}`); corpus.set(d.docId, d);
  }
  assert(corpus.size, 'empty corpus');
  assert(Array.isArray(rankings.runs) && rankings.runs.length, 'at least one repeat required');
  const inputs = { rankingsSha256: sha(rankingsBytes), corpusSha256: sha(corpusBytes), configSha256: rankings.configSha256, queriesSha256: rankings.queriesSha256 };
  const poolId = sha(JSON.stringify(inputs));
  const queries = new Map(), repeats = new Set(), problems = [];
  for (const run of rankings.runs) {
    assert(Number.isInteger(run.repeat) && run.repeat >= 1 && !repeats.has(run.repeat), 'repeat must be unique positive integer');
    repeats.add(run.repeat); assert(Array.isArray(run.queries) && run.queries.length, 'empty repeat');
    const seen = new Set();
    for (const q of run.queries) {
      text(q.id, 'query id'); text(q.query, 'query'); text(q.city, 'query city');
      assert(!seen.has(q.id), `duplicate query ${q.id} in repeat`); seen.add(q.id);
      const metadata = { queryId: q.id, query: q.query, city: q.city, split: q.split ?? null, familyId: q.familyId ?? null };
      if (!queries.has(q.id)) queries.set(q.id, { metadata, documents: new Map(), seenRepeats: new Set() });
      const row = queries.get(q.id);
      assert.deepEqual(row.metadata, metadata, `query metadata changed across repeats: ${q.id}`);
      row.seenRepeats.add(run.repeat);
      assert(q.methods && typeof q.methods === 'object' && !Array.isArray(q.methods), 'methods required');
      for (const method of Object.keys(q.methods)) assert(METHODS.includes(method), `unknown method ${method}`);
      for (const method of METHODS) {
        const result = q.methods[method];
        if (!result) { problems.push({ repeat: run.repeat, queryId: q.id, method, status: 'MISSING_METHOD' }); continue; }
        assert(['OK', 'ERROR'].includes(result.status), 'invalid method status');
        if (result.status === 'ERROR') { problems.push({ repeat: run.repeat, queryId: q.id, method, status: 'ERROR', error: String(result.error ?? 'unspecified') }); continue; }
        assert(Array.isArray(result.ranking), 'OK method requires ranking');
        const docIds = new Set(), ranks = new Set();
        for (const hit of result.ranking) {
          text(hit.docId, 'ranked docId');
          assert(Number.isInteger(hit.rank) && hit.rank >= 1 && !ranks.has(hit.rank), 'ranks must be unique positive integers');
          assert(!docIds.has(hit.docId), 'duplicate ranked document'); docIds.add(hit.docId); ranks.add(hit.rank);
          assert(hit.score == null || Number.isFinite(hit.score), 'score must be finite');
          const d = corpus.get(hit.docId); assert(d, `unknown ranked document ${hit.docId}`);
          assert.equal(d.city, q.city, `cross-city ranked document ${hit.docId}`);
          if (hit.rank > 10) continue;
          if (!row.documents.has(hit.docId)) row.documents.set(hit.docId, { d, sources: [] });
          row.documents.get(hit.docId).sources.push({ repeat: run.repeat, method, rank: hit.rank });
        }
        const expectedTopK = Math.min(10, [...corpus.values()].filter(d => d.city === q.city).length);
        const missingRanks = Array.from({ length: expectedTopK }, (_, i) => i+1).filter(rank => !ranks.has(rank));
        if (missingRanks.length) problems.push({ repeat: run.repeat, queryId: q.id, method, status: result.ranking.length ? 'INCOMPLETE_TOP10' : 'EMPTY_RANKING', missingRanks });
      }
    }
  }
  for (const [id, row] of queries) for (const repeat of repeats) if (!row.seenRepeats.has(repeat)) problems.push({ repeat, queryId: id, status: 'MISSING_QUERY' });
  const coverage = { complete: problems.length === 0, repeats: [...repeats].sort((a,b) => a-b), expectedMethods: METHODS, topK: 10, problems };
  const blindQueries = [], privateQueries = [];
  // SHA ordering is deterministic but unrelated to retrieval order or method.
  const ordered = [...queries.values()].sort((a,b) => sha(poolId + a.metadata.queryId).localeCompare(sha(poolId + b.metadata.queryId)));
  for (const [qi, row] of ordered.entries()) {
    const anonymousQueryId = `Q${String(qi+1).padStart(3, '0')}`;
    const documents = [...row.documents.values()].sort((a,b) => sha(poolId + row.metadata.queryId + a.d.docId).localeCompare(sha(poolId + row.metadata.queryId + b.d.docId)));
    const blindDocs = [], privateDocs = [];
    for (const [di, item] of documents.entries()) {
      const anonymousDocumentId = `${anonymousQueryId}-D${String(di+1).padStart(3,'0')}`;
      blindDocs.push({ anonymousDocumentId, title: String(item.d.title ?? ''), content: item.d.content, grade: null });
      privateDocs.push({ anonymousDocumentId, docId: item.d.docId, sources: item.sources.sort((a,b) => a.repeat-b.repeat || a.method.localeCompare(b.method) || a.rank-b.rank) });
    }
    blindQueries.push({ anonymousQueryId, query: row.metadata.query, city: row.metadata.city, documents: blindDocs });
    const { query: ignored, ...metadata } = row.metadata;
    privateQueries.push({ anonymousQueryId, ...metadata, documents: privateDocs });
  }
  const blind = { schemaVersion: 'retrieval-blind-pool-v1', poolId, inputs, scale: { '0': '不相关', '1': '部分相关', '2': '直接回答', unjudged: null }, queries: blindQueries };
  return { blind, privateMap: { schemaVersion: 'retrieval-private-pool-v1', poolId, inputs, blindContentSha256: sha(JSON.stringify(blind)), coverage, queries: privateQueries } };
}

export function verifyBlindPool(pool, privateMap) {
  assert.equal(pool.poolId, privateMap.poolId, 'candidate pool identity mismatch');
  assert.equal(sha(JSON.stringify(pool)), privateMap.blindContentSha256, 'blind candidate content changed');
  return privateMap.blindContentSha256;
}

// Also used in the offline page: imported judgments cannot change candidate identities.
export function validateAnnotations(value, pool, expectedBlindContentSha256) {
  if (!/^[a-f0-9]{64}$/.test(expectedBlindContentSha256 ?? '') || value?.blindContentSha256 !== expectedBlindContentSha256) throw new Error('标注文件与候选正文版本不匹配');
  if (value?.schemaVersion !== 'retrieval-pool-annotations-v1' || value.poolId !== pool.poolId) throw new Error('标注文件与本候选池不匹配');
  if (value.reviewer?.type !== 'HUMAN_SELF_ATTESTED' || typeof value.reviewer.name !== 'string' || !value.reviewer.name.trim()) throw new Error('需要人工复核者姓名声明');
  if (value.rubricNotes !== undefined && typeof value.rubricNotes !== 'string') throw new Error('非法全局标注口径');
  validateJudgments(value.judgments, pool);
  return value;
}

// Model labels use a separate explicit contract and never impersonate human review.
export function validateModelAssistedAnnotations(value, pool, expectedBlindContentSha256) {
  if (!/^[a-f0-9]{64}$/.test(expectedBlindContentSha256 ?? '') || value?.blindContentSha256 !== expectedBlindContentSha256) throw new Error('标注文件与候选正文版本不匹配');
  if (value?.schemaVersion !== 'retrieval-pool-model-annotations-v1' || value.poolId !== pool.poolId) throw new Error('模型标注文件与本候选池不匹配');
  if (value.reviewer?.type !== 'MODEL_ASSISTED_PRIMARY' || value.reviewer?.model !== 'GPT-6 Luna') throw new Error('模型标注来源声明不匹配');
  if (value.annotationStatus !== 'AI_PRELABEL_NOT_HUMAN') throw new Error('模型标注不得改成人工标签');
  if (value.rubricNotes !== undefined && typeof value.rubricNotes !== 'string') throw new Error('非法全局标注口径');
  validateJudgments(value.judgments, pool);
  const docs = new Map(pool.queries.flatMap(q => q.documents.map(d => [q.anonymousQueryId + '/' + d.anonymousDocumentId, d])));
  if (value.judgments.length !== docs.size || value.judgments.some(j => j.grade === null)) throw new Error('模型主标签必须完整，缺失候选不能当作0分');
  for (const j of value.judgments) {
    if (j.humanGrade !== null || j.status !== 'AI_PRELABEL_NOT_HUMAN') throw new Error('发现人工标注冒充或模型状态错误');
    if (j.grade > 0 && (typeof j.supportingQuote !== 'string' || !j.supportingQuote || !docs.get(j.anonymousQueryId + '/' + j.anonymousDocumentId).content.includes(j.supportingQuote))) throw new Error('模型正等级缺少对应正文原文引句');
  }
  return value;
}

export function validateJudgments(judgments, pool) {
  if (!Array.isArray(judgments)) throw new Error('缺少 judgments');
  const valid = new Set(pool.queries.flatMap(q => q.documents.map(d => q.anonymousQueryId + '/' + d.anonymousDocumentId))), seen = new Set();
  for (const j of judgments) {
    const key = j.anonymousQueryId + '/' + j.anonymousDocumentId;
    if (!valid.has(key) || seen.has(key)) throw new Error('未知或重复候选');
    if (![null,0,1,2].includes(j.grade) || typeof j.reason !== 'string') throw new Error('非法相关性等级或理由');
    seen.add(key);
  }
}

export function validateDraft(value, pool, expectedBlindContentSha256) {
  if (value?.schemaVersion !== 'retrieval-pool-draft-v1' || value.poolId !== pool.poolId || value.blindContentSha256 !== expectedBlindContentSha256) throw new Error('本地草稿版本与候选正文不匹配');
  if (typeof value.reviewerName !== 'string' || typeof value.rubricNotes !== 'string') throw new Error('非法本地草稿字段');
  if (value.cursorAnonymousQueryId != null && !pool.queries.some(q => q.anonymousQueryId === value.cursorAnonymousQueryId)) throw new Error('非法草稿题目位置');
  validateJudgments(value.judgments, pool);
  return value;
}

export function annotationHtml(pool, complete, blindContentSha256 = sha(JSON.stringify(pool)), developmentQueryIds = []) {
  assert.equal(sha(JSON.stringify(pool)), blindContentSha256, 'blind candidate content changed before HTML generation');
  assert(developmentQueryIds.every(id => pool.queries.some(q => q.anonymousQueryId === id)), 'unknown anonymous development query');
  const embedded = JSON.stringify(pool).replace(/</g, '\\u003c').replace(/>/g, '\\u003e').replace(/&/g, '\\u0026').replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
  return `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'none'; img-src 'none'"><title>检索候选盲标</title>
<style>body{font:16px system-ui;max-width:1000px;margin:24px auto;padding:0 16px;color:#222}header{position:sticky;top:0;background:white;padding:12px;border-bottom:1px solid #aaa}article{border:1px solid #bbb;padding:16px;margin:16px 0;border-radius:8px}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit}button,input,select,textarea{font:inherit;margin:4px;padding:6px}textarea{display:block;width:95%}.warn{color:#a20}small{display:block}</style>
<header><h1>检索候选盲标</h1><p>只评价资料能否回答问题；不将资料来源注册视为事实可信认证。<b>0 不相关</b>：未回答所问属性，只有同名、提名或主题相关也为 0。<b>1 部分相关</b>：回答问题的一部分或提供所问属性的部分信息。<b>2 直接回答</b>：明确回答所问属性，保留适用条件。未判不是 0。</p><p class="warn">${complete ? '候选检索覆盖完整。' : '警告：候选池检索覆盖不完整，缺失或失败的检索结果未被假装完成。'} 标注尚待人工完成。本地草稿会尝试自动保存，仍请导出文件留底。</p><label>复核者姓名 <input id="reviewer"></label><button id="export">导出标注</button><button id="confirm-saved" disabled>已确认文件保存</button><label>导入续标 <input id="import" type="file" accept="application/json"></label><label>全局标注口径<textarea id="rubric-notes" placeholder="记录歧义裁决口径；不会自动改任何等级"></textarea></label><small id="status" role="status"></small><small id="storage-status" role="status"></small><label><input id="development" type="checkbox">优先标开发集（只显示开发题）</label><input id="search" placeholder="搜索题目"><button id="prev">上一题</button><button id="next">下一题</button><span id="progress"></span></header><main id="main"></main>
<script id="pool-data" type="application/json">${embedded}</script><script>
const pool=JSON.parse(document.getElementById('pool-data').textContent);
const expectedBlindContentSha256=${JSON.stringify(blindContentSha256)};
const developmentQueryIds=new Set(${JSON.stringify(developmentQueryIds)});
const validateJudgments=${validateJudgments.toString()};
const validateAnnotations=${validateAnnotations.toString()};
const validateDraft=${validateDraft.toString()};
async function verifyPageContent(){if(!globalThis.crypto?.subtle)throw new Error('浏览器不支持正文完整性校验，请使用现代浏览器');const bytes=new TextEncoder().encode(JSON.stringify(pool));const actual=[...new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))].map(b=>b.toString(16).padStart(2,'0')).join('');if(actual!==expectedBlindContentSha256)throw new Error('候选正文已变更，禁止导入或导出旧版本标注');}
const judgments=new Map(); let visible=pool.queries, index=0, dirty=false, storageBlocked=false, pageVerified=false;
const draftKey='retrieval-pool-draft:'+pool.poolId+':'+expectedBlindContentSha256;
const el=id=>document.getElementById(id), key=(q,d)=>q+'/'+d;
function node(tag,value){const n=document.createElement(tag); if(value!==undefined)n.textContent=value;return n;}
function updateProgress(){const all=pool.queries.flatMap(q=>q.documents);const done=[...judgments.values()].filter(j=>j.grade!==null).length;el('progress').textContent=(visible.length?index+1:0)+' / '+visible.length+' 题，已判 '+done+' / '+all.length+' 篇';}
function allJudgments(){return pool.queries.flatMap(q=>q.documents.map(d=>judgments.get(key(q.anonymousQueryId,d.anonymousDocumentId))??{anonymousQueryId:q.anonymousQueryId,anonymousDocumentId:d.anonymousDocumentId,grade:null,reason:''}));}
function saveDraft(){if(!pageVerified||storageBlocked)return;try{const value={schemaVersion:'retrieval-pool-draft-v1',poolId:pool.poolId,blindContentSha256:expectedBlindContentSha256,reviewerName:el('reviewer').value,rubricNotes:el('rubric-notes').value,judgments:allJudgments(),cursorAnonymousQueryId:visible[index]?.anonymousQueryId??null};validateDraft(value,pool,expectedBlindContentSha256);localStorage.setItem(draftKey,JSON.stringify(value));el('storage-status').textContent='本地草稿已保存；尚未确认导出文件。';}catch(e){storageBlocked=true;el('storage-status').textContent='本地草稿保存失败，内存编辑仍保留；请立即导出文件。原因：'+e.message;}}
function edited(){dirty=true;el('confirm-saved').disabled=true;saveDraft();}
function render(){el('main').replaceChildren();const q=visible[index];updateProgress();if(!q)return;el('main').append(node('h2',q.anonymousQueryId+' · '+q.city+' · '+q.query));for(const d of q.documents){const k=key(q.anonymousQueryId,d.anonymousDocumentId);if(!judgments.has(k))judgments.set(k,{anonymousQueryId:q.anonymousQueryId,anonymousDocumentId:d.anonymousDocumentId,grade:null,reason:''});const j=judgments.get(k),a=node('article');a.append(node('h3',d.anonymousDocumentId+' · '+d.title),node('pre',d.content));const select=node('select');for(const [v,label]of [['','未判'],['0','0 不相关'],['1','1 部分相关'],['2','2 直接回答']]){const option=node('option',label);option.value=v;select.append(option);}select.value=j.grade===null?'':String(j.grade);select.onchange=()=>{j.grade=select.value===''?null:Number(select.value);updateProgress();edited();};const reason=node('textarea');reason.placeholder='相关性理由或争议（可留空）';reason.value=j.reason;reason.oninput=()=>{j.reason=reason.value;edited();};a.append(select,reason);el('main').append(a);}}
function filter(){const s=el('search').value.trim().toLowerCase();visible=pool.queries.filter(q=>(!el('development').checked||developmentQueryIds.has(q.anonymousQueryId))&&(q.query+' '+q.city+' '+q.anonymousQueryId).toLowerCase().includes(s));index=0;render();}
el('prev').onclick=()=>{if(index>0)index--;render();saveDraft();};el('next').onclick=()=>{if(index+1<visible.length)index++;render();saveDraft();};el('search').oninput=()=>{filter();saveDraft();};el('development').onchange=()=>{filter();saveDraft();};el('reviewer').oninput=edited;el('rubric-notes').oninput=edited;
el('export').onclick=async()=>{try{await verifyPageContent();const value={schemaVersion:'retrieval-pool-annotations-v1',poolId:pool.poolId,blindContentSha256:expectedBlindContentSha256,reviewer:{name:el('reviewer').value.trim(),type:'HUMAN_SELF_ATTESTED'},rubricNotes:el('rubric-notes').value,judgments:allJudgments()};validateAnnotations(value,pool,expectedBlindContentSha256);const url=URL.createObjectURL(new Blob([JSON.stringify(value,null,2)],{type:'application/json'}));const a=node('a');a.href=url;a.download='retrieval-annotations-'+pool.poolId.slice(0,12)+'.json';a.click();el('confirm-saved').disabled=false;setTimeout(()=>URL.revokeObjectURL(url),1000);el('status').textContent='已发起本地文件下载，请确认文件已保存；人工声明不代表系统独立核验。';}catch(e){el('status').textContent=e.message;}};
el('import').onchange=async()=>{try{await verifyPageContent();const f=el('import').files[0];if(!f)return;const value=validateAnnotations(JSON.parse(await f.text()),pool,expectedBlindContentSha256);for(const j of value.judgments)judgments.set(key(j.anonymousQueryId,j.anonymousDocumentId),j);el('reviewer').value=value.reviewer.name;if(value.rubricNotes!==undefined)el('rubric-notes').value=value.rubricNotes;edited();el('status').textContent='已载入续标；请继续导出保存。';render();}catch(e){el('status').textContent='导入失败：'+e.message;}};
el('confirm-saved').onclick=()=>{if(el('confirm-saved').disabled)return;dirty=false;el('confirm-saved').disabled=true;el('status').textContent='你已确认当前导出文件保存；新的编辑仍需重新导出确认。';};
globalThis.addEventListener('beforeunload',event=>{if(dirty){event.preventDefault();event.returnValue='还有未确认保存的编辑，离开前请确认导出文件已保存。';}});
async function initialize(){const controls=['reviewer','rubric-notes','development','search','prev','next','export','import'];for(const id of controls)el(id).disabled=true;el('development').checked=developmentQueryIds.size>0;filter();try{await verifyPageContent();pageVerified=true;const raw=localStorage.getItem(draftKey);if(raw!==null){const draft=validateDraft(JSON.parse(raw),pool,expectedBlindContentSha256);for(const j of draft.judgments)judgments.set(key(j.anonymousQueryId,j.anonymousDocumentId),j);el('reviewer').value=draft.reviewerName;el('rubric-notes').value=draft.rubricNotes;const at=visible.findIndex(q=>q.anonymousQueryId===draft.cursorAnonymousQueryId);if(at>=0)index=at;dirty=true;render();el('storage-status').textContent='已恢复本地草稿；请导出文件留底。';}}catch(e){storageBlocked=true;el('storage-status').textContent='本地草稿未恢复或存储不可用，原存储未覆盖；请保留并导出当前编辑。原因：'+e.message;}finally{for(const id of controls)el(id).disabled=false;}}
const ready=initialize();
</script></html>`;
}

export async function writePool({ rankingsPath, corpusPath, outputDir }) {
  const [rankings, corpus] = await Promise.all([readFile(rankingsPath), readFile(corpusPath)]);
  const result = buildPool(rankings, corpus);
  // A new directory is the transaction boundary: existing deliveries are never overwritten.
  await mkdir(outputDir, { recursive: false });
  for (const [file, value] of [['blind-pool.json', result.blind], ['private-pool-map.json', result.privateMap]]) await writeFile(join(outputDir,file), JSON.stringify(value,null,2)+'\n', { flag:'wx', encoding:'utf8' });
  const developmentQueryIds=result.privateMap.queries.filter(q=>['development','dev'].includes(q.split)).map(q=>q.anonymousQueryId);
  await writeFile(join(outputDir,'annotation.html'), annotationHtml(result.blind,result.privateMap.coverage.complete,result.privateMap.blindContentSha256,developmentQueryIds), { flag:'wx',encoding:'utf8' });
  return { poolId: result.blind.poolId, queries: result.blind.queries.length, judgmentsRequired: result.blind.queries.reduce((n,q)=>n+q.documents.length,0), coverage: result.privateMap.coverage };
}
async function main() {
  const { values } = parseArgs({ options: { rankings:{type:'string'},corpus:{type:'string'},'output-dir':{type:'string'} } });
  for(const key of ['rankings','corpus','output-dir'])text(values[key],'--'+key);
  console.log(JSON.stringify(await writePool({rankingsPath:values.rankings,corpusPath:values.corpus,outputDir:values['output-dir']}),null,2));
}
if(process.argv[1] && import.meta.url===pathToFileURL(process.argv[1]).href) main().catch(e=>{console.error(e.message);process.exitCode=1;});
