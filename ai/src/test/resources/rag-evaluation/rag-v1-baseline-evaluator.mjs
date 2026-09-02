#!/usr/bin/env node

import { readFile, writeFile } from 'node:fs/promises';
import process from 'node:process';
import { performance } from 'node:perf_hooks';

const DEFAULT_DATASET = new URL('./rag-v1-baseline-40.json', import.meta.url);

const VENUE_CANONICAL = Object.freeze({
  'cq-museum': 'cq-sanxia-museum-main',
  'cq-wulong-tiankeng': 'cq-wulong-tiansheng-sanqiao'
});

const VENUE_ALIASES = Object.freeze({
  'cq-hongyadong': ['洪崖洞'],
  'cq-jiefangbei': ['解放碑'],
  'cq-sanxia-museum-main': ['重庆中国三峡博物馆', '三峡博物馆'],
  'cq-liziba': ['李子坝', '轻轨穿楼'],
  'cq-grand-theatre': ['重庆大剧院', '大剧院'],
  'cq-ciqikou': ['磁器口'],
  'cq-kuixinglou': ['魁星楼', '空中天桥'],
  'cq-shibati': ['十八梯'],
  'cq-erling': ['鹅岭二厂', '鹅岭'],
  'cq-changjiang-cable': ['长江索道'],
  'cq-nanshan-yikeshu': ['南山一棵树'],
  'cq-danzi-shi': ['弹子石老街', '弹子石', '长嘉汇'],
  'cq-guanyinqiao': ['观音桥'],
  'cq-luohan-temple': ['罗汉寺'],
  'cq-huguang-guild': ['湖广会馆'],
  'cq-zhongshan-road': ['中山四路'],
  'cq-baiheliang': ['白鹤梁水下博物馆', '白鹤梁'],
  'cq-dazu-shike': ['大足石刻', '宝顶山石刻'],
  'cq-ronghui-hotspring': ['融汇温泉'],
  'cq-south-hotspring': ['南温泉风景区', '南温泉'],
  'cq-geleyuan': ['歌乐山烈士陵园', '渣滓洞', '白公馆'],
  'cq-maanshan': ['马鞍山传统风貌区', '马鞍山'],
  'cq-beicang': ['北仓文创街区', '北仓'],
  'cq-wulong-tiansheng-sanqiao': ['武隆天生三桥', '天生三桥', '天坑三桥'],
  'cq-xiannyshan': ['仙女山国家森林公园', '仙女山'],
  'cq-jinfoshan': ['金佛山'],
  'cq-nanbinlu': ['南滨路'],
  'cq-ziran-museum': ['重庆自然博物馆', '自然博物馆'],
  'cq-kejiguan': ['重庆科技馆', '科技馆'],
  'cq-meishuguan': ['重庆美术馆', '美术馆', '国泰艺术中心'],
  'cq-renmin-dalitang': ['重庆人民大礼堂', '人民大礼堂'],
  'cq-hongyan-memorial': ['红岩革命纪念馆'],
  'cq-war-relics-museum': ['重庆抗战遗址博物馆'],
  'cq-hanhai-ocean': ['汉海海洋公园', '海洋公园'],
  'cq-longmenhao': ['龙门浩老街'],
  'cq-wulingshan-rift': ['涪陵武陵山大裂谷', '武陵山大裂谷'],
  'cq-jindaoxia': ['北碚金刀峡景区', '金刀峡'],
  'cq-chongqing': ['长江索道和两江游', '索道和江上游船', '天气风险']
});

function parseArgs(argv) {
  const args = {};
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token.startsWith('--')) continue;
    const key = token.slice(2);
    const next = argv[index + 1];
    if (next && !next.startsWith('--')) {
      args[key] = next;
      index += 1;
    } else {
      args[key] = true;
    }
  }
  return args;
}

function canonicalId(id) {
  return VENUE_CANONICAL[id] || id;
}

function canonicalIds(ids = []) {
  return new Set(ids.map(canonicalId));
}

function allFactText(result) {
  const facts = Array.isArray(result?.facts) ? result.facts : [];
  return facts.map((fact) => [fact?.label, fact?.value, fact?.note].filter(Boolean).join('\n')).join('\n---\n');
}

function detectVenueIds(result) {
  const facts = Array.isArray(result?.facts) ? result.facts : [];
  const detected = [];
  facts.forEach((fact, factIndex) => {
    const text = [fact?.label, fact?.value, fact?.note].filter(Boolean).join('\n');
    const ids = Object.entries(VENUE_ALIASES)
      .filter(([, aliases]) => aliases.some((alias) => text.includes(alias)))
      .map(([id]) => canonicalId(id));
    const uniqueIds = [...new Set(ids)];
    detected.push({ factIndex: factIndex + 1, ids: uniqueIds, text });
  });
  const rankedVenueIds = [];
  for (const item of detected) {
    for (const id of item.ids) {
      if (!rankedVenueIds.includes(id)) rankedVenueIds.push(id);
    }
  }
  return { rankedVenueIds, factMatches: detected };
}

function classifyMode(result, httpStatus, error) {
  if (error || !result || httpStatus >= 500) return 'UNAVAILABLE';
  if (result.mode === 'Java Qdrant 语义检索' || result.mode === 'Java 深度 RAG') return 'QDRANT';
  if (result.mode === 'Java 本地知识回退（关键词检索）') return 'LOCAL_FALLBACK';
  if (result.ok === false) return 'UNAVAILABLE';
  return 'UNAVAILABLE';
}

function keywordStats(question, result) {
  const text = allFactText(result).toLocaleLowerCase('zh-CN');
  const keywords = Array.isArray(question.requiredFactKeywords) ? question.requiredFactKeywords : [];
  const matched = keywords.filter((keyword) => text.includes(String(keyword).toLocaleLowerCase('zh-CN')));
  return {
    required: keywords,
    matched,
    missing: keywords.filter((keyword) => !matched.includes(keyword)),
    hitRate: keywords.length === 0 ? null : matched.length / keywords.length,
    allHit: keywords.length === 0 ? null : matched.length === keywords.length
  };
}

function evaluateQuestion(question, result, httpStatus, elapsedMs, error = null) {
  const mode = classifyMode(result, httpStatus, error);
  const facts = Array.isArray(result?.facts) ? result.facts : [];
  const { rankedVenueIds, factMatches } = detectVenueIds(result || {});
  const expected = canonicalIds(question.expectedVenueIds);
  const acceptable = canonicalIds(question.acceptableVenueIds);
  const forbidden = new Set((question.forbiddenVenueIds || []).map(canonicalId));
  const relevantIds = new Set([...expected, ...acceptable]);
  const top1Ids = factMatches[0]?.ids || [];
  const top1ExpectedHit = top1Ids.some((id) => expected.has(id));
  const top1AcceptableHit = top1Ids.some((id) => relevantIds.has(id));
  const firstExpectedRank = rankedVenueIds.findIndex((id) => expected.has(id));
  const firstAcceptableRank = rankedVenueIds.findIndex((id) => relevantIds.has(id));
  const recallAt = (limit, target) => {
    if (target.size === 0) return null;
    const retrieved = new Set(rankedVenueIds.slice(0, limit));
    return [...target].filter((id) => retrieved.has(id)).length / target.size;
  };
  const keywords = keywordStats(question, result || {});
  const forbiddenLeak = forbidden.has('*')
    ? rankedVenueIds.length > 0
    : rankedVenueIds.some((id) => forbidden.has(id));
  const unknownFalsePositive = question.expectedRetrievalStatus === 'UNKNOWN' && facts.length > 0;

  return {
    id: question.id,
    query: question.query,
    category: question.category,
    expectedRetrievalStatus: question.expectedRetrievalStatus,
    mode,
    httpStatus,
    elapsedMs: Math.round(elapsedMs * 100) / 100,
    ok: result?.ok === true,
    resultMode: result?.mode || null,
    resultSource: result?.source || null,
    reason: result?.reason || null,
    factCount: facts.length,
    rankedVenueIds,
    factMatches,
    top1ExpectedHit,
    top1AcceptableHit,
    recallAt3: recallAt(3, expected),
    recallAt5: recallAt(5, expected),
    mrr: firstExpectedRank === -1 ? 0 : 1 / (firstExpectedRank + 1),
    acceptableFirstRank: firstAcceptableRank === -1 ? null : firstAcceptableRank + 1,
    forbiddenLeak,
    unknownFalsePositive,
    keywordStats: keywords,
    error,
    rawResult: result || null
  };
}

function extractStructuredPayload(payload) {
  if (payload && typeof payload === 'object') {
    if (payload.data && typeof payload.data === 'object') return payload.data;
    if (payload.result && typeof payload.result === 'object') return payload.result;
    if ('ok' in payload || 'facts' in payload || 'mode' in payload) return payload;
  }
  return null;
}

async function requestQuestion(baseUrl, question) {
  const started = performance.now();
  try {
    const response = await fetch(`${baseUrl.replace(/\/$/, '')}/ai/rag/retrieve`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ query: question.query, city: '重庆', constraints: {}, deep: false }),
      signal: AbortSignal.timeout(20000)
    });
    const payload = await response.json().catch(() => null);
    const result = extractStructuredPayload(payload);
    return evaluateQuestion(question, result, response.status, performance.now() - started, result ? null : 'RESPONSE_NOT_STRUCTURED');
  } catch (error) {
    return evaluateQuestion(question, null, 0, performance.now() - started, `${error.name}: ${error.message}`);
  }
}

function percentile(values, p) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil(sorted.length * p) - 1));
  return sorted[index];
}

function aggregate(modeResult) {
  const observations = modeResult.questions;
  const relevant = observations.filter((item) => item.expectedRetrievalStatus === 'RELEVANT');
  const withKeywords = relevant.filter((item) => item.keywordStats.required.length > 0);
  const unknown = observations.filter((item) => item.expectedRetrievalStatus === 'UNKNOWN');
  const latencies = observations.map((item) => item.elapsedMs).filter(Number.isFinite);
  const keywordTotal = withKeywords.reduce((sum, item) => sum + item.keywordStats.required.length, 0);
  const keywordMatched = withKeywords.reduce((sum, item) => sum + item.keywordStats.matched.length, 0);
  const modeCounts = observations.reduce((counts, item) => {
    counts[item.mode] = (counts[item.mode] || 0) + 1;
    return counts;
  }, {});
  return {
    totalQuestions: observations.length,
    relevantQuestions: relevant.length,
    unknownQuestions: unknown.length,
    top1HitRate: relevant.length === 0 ? null : relevant.filter((item) => item.top1ExpectedHit).length / relevant.length,
    top1AcceptableHitRate: relevant.length === 0 ? null : relevant.filter((item) => item.top1AcceptableHit).length / relevant.length,
    recallAt3: relevant.length === 0 ? null : relevant.reduce((sum, item) => sum + item.recallAt3, 0) / relevant.length,
    recallAt5: relevant.length === 0 ? null : relevant.reduce((sum, item) => sum + item.recallAt5, 0) / relevant.length,
    mrr: relevant.length === 0 ? null : relevant.reduce((sum, item) => sum + item.mrr, 0) / relevant.length,
    requiredFactKeywordHitRate: keywordTotal === 0 ? null : keywordMatched / keywordTotal,
    allRequiredFactQuestionsHitRate: withKeywords.length === 0 ? null : withKeywords.filter((item) => item.keywordStats.allHit).length / withKeywords.length,
    forbiddenLeakCount: observations.filter((item) => item.forbiddenLeak).length,
    unknownFalsePositiveCount: unknown.filter((item) => item.unknownFalsePositive).length,
    unknownFalsePositiveRate: unknown.length === 0 ? null : unknown.filter((item) => item.unknownFalsePositive).length / unknown.length,
    modeCounts,
    qdrantSuccessCount: modeCounts.QDRANT || 0,
    localFallbackCount: modeCounts.LOCAL_FALLBACK || 0,
    unavailableCount: modeCounts.UNAVAILABLE || 0,
    latencyMs: {
      min: latencies.length ? Math.min(...latencies) : null,
      average: latencies.length ? latencies.reduce((sum, value) => sum + value, 0) / latencies.length : null,
      p95: percentile(latencies, 0.95),
      max: latencies.length ? Math.max(...latencies) : null
    }
  };
}

function aliasLikelyMissing(question, observation) {
  if (!['景点别名与口语表达', '人文/自然/夜景等主题'].includes(question.category)) return false;
  const aliasTerms = ['轻轨穿楼', '小什字', '对面的博物馆', '悬空', '江北嘴', '古寺'];
  return aliasTerms.some((term) => question.query.includes(term)) && observation.rankedVenueIds.length === 0;
}

function classifyFailure(question, observation, counterpart) {
  if (question.expectedRetrievalStatus === 'UNKNOWN') {
    return observation.unknownFalsePositive ? 'OUT_OF_SCOPE_FALSE_POSITIVE' : null;
  }
  if (observation.mode === 'UNAVAILABLE') return 'PROVIDER_UNAVAILABLE';
  if (observation.forbiddenLeak) return 'PAYLOAD_FILTER';
  if (aliasLikelyMissing(question, observation)) return 'ALIAS_MISSING';
  if (!observation.top1ExpectedHit && observation.recallAt5 === 1 && observation.acceptableFirstRank !== null && observation.acceptableFirstRank > 1) {
    return 'SCORE_OR_TOPK';
  }
  if (observation.rankedVenueIds.some((id) => canonicalIds(question.expectedVenueIds).has(id)) && observation.keywordStats.required.length > 0 && !observation.keywordStats.allHit) {
    return 'CORPUS_INCORRECT';
  }
  if (observation.mode === 'QDRANT' && counterpart && counterpart.rankedVenueIds.some((id) => canonicalIds(question.expectedVenueIds).has(id))) {
    return 'EMBEDDING_MISS';
  }
  if (observation.mode === 'LOCAL_FALLBACK' && (!observation.rankedVenueIds.some((id) => canonicalIds(question.expectedVenueIds).has(id)) || !observation.keywordStats.allHit)) {
    return 'LOCAL_FALLBACK_WEAK';
  }
  if (observation.rankedVenueIds.length === 0) return 'CORPUS_MISSING';
  return 'QUERY_UNDERSTANDING';
}

function failuresForMode(dataset, modeResult, counterpartResult) {
  const byId = new Map((counterpartResult?.questions || []).map((item) => [item.id, item]));
  return modeResult.questions.flatMap((observation) => {
    const question = dataset.questions.find((item) => item.id === observation.id);
    const counterpart = byId.get(observation.id);
    const failed = question.expectedRetrievalStatus === 'UNKNOWN'
      ? observation.unknownFalsePositive
      : !observation.top1ExpectedHit || observation.recallAt5 < 1 || observation.forbiddenLeak || observation.keywordStats.allHit === false || observation.mode === 'UNAVAILABLE';
    if (!failed) return [];
    return [{
      id: question.id,
      query: question.query,
      category: question.category,
      mode: modeResult.mode,
      rootCause: classifyFailure(question, observation, counterpart),
      failedChecks: {
        top1ExpectedHit: observation.top1ExpectedHit,
        recallAt5: observation.recallAt5,
        requiredFactKeywordsAllHit: observation.keywordStats.allHit,
        forbiddenLeak: observation.forbiddenLeak,
        unknownFalsePositive: observation.unknownFalsePositive,
        retrievalMode: observation.mode
      },
      observedVenueIds: observation.rankedVenueIds,
      missingFactKeywords: observation.keywordStats.missing,
      reason: observation.reason,
      evidence: {
        expectedVenueIds: question.expectedVenueIds,
        acceptableVenueIds: question.acceptableVenueIds,
        forbiddenVenueIds: question.forbiddenVenueIds,
        requiredFactKeywords: question.requiredFactKeywords,
        evidenceSource: question.evidenceSource
      }
    }];
  });
}

async function runMode(args, dataset) {
  if (!args['base-url'] || !args.mode || !args.output) {
    throw new Error('run requires --base-url, --mode, and --output');
  }
  const questions = [];
  for (const question of dataset.questions) {
    process.stdout.write(`${args.mode} ${question.id}\n`);
    questions.push(await requestQuestion(args['base-url'], question));
  }
  const output = {
    schemaVersion: 'rag-v1-baseline-results-partial',
    mode: args.mode,
    baseUrl: args['base-url'],
    dataset: dataset.datasetName,
    capturedAt: new Date().toISOString(),
    questions
  };
  output.metrics = aggregate(output);
  await writeFile(args.output, JSON.stringify(output, null, 2), 'utf8');
}

async function mergeModes(args, dataset) {
  if (!args.qdrant || !args.local || !args.output) {
    throw new Error('merge requires --qdrant, --local, and --output');
  }
  const qdrant = JSON.parse(await readFile(args.qdrant, 'utf8'));
  const local = JSON.parse(await readFile(args.local, 'utf8'));
  const output = {
    schemaVersion: 'rag-v1-baseline-results',
    dataset: dataset.datasetName,
    capturedAt: new Date().toISOString(),
    evaluationContract: {
      endpoint: 'POST /ai/rag/retrieve',
      qdrantPath: 'RagRetrievalService -> ScenicDataImportService.searchFragments(query, 25, 12)',
      localFallbackPath: 'RagRetrievalService -> KnowledgeDocumentService.searchLocalKnowledge(query, 5)',
      deep: false,
      noLlmJudge: true
    },
    modes: {
      qdrant: { metrics: aggregate(qdrant), questions: qdrant.questions },
      localFallback: { metrics: aggregate(local), questions: local.questions }
    },
    failures: {
      qdrant: failuresForMode(dataset, qdrant, local),
      localFallback: failuresForMode(dataset, local, qdrant)
    }
  };
  await writeFile(args.output, JSON.stringify(output, null, 2), 'utf8');
}

const args = parseArgs(process.argv.slice(2));
const command = process.argv[2];
const datasetPath = args.dataset ? args.dataset : DEFAULT_DATASET;
const dataset = JSON.parse(await readFile(datasetPath, 'utf8'));

if (command === 'run') {
  await runMode(args, dataset);
} else if (command === 'merge') {
  await mergeModes(args, dataset);
} else {
  throw new Error('Usage: run --base-url URL --mode qdrant|local --output FILE [--dataset FILE] | merge --qdrant FILE --local FILE --output FILE [--dataset FILE]');
}
