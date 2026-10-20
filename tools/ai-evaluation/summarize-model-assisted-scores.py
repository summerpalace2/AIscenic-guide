"""Create split summaries and per-query rerank-vs-hybrid paired outcomes."""
import argparse
import csv
import json
import math
from pathlib import Path
import random


def mean(values):
    return sum(values)/len(values) if values else None


def nearest_rank(values, p):
    values=sorted(values)
    return values[math.ceil(len(values)*p)-1] if values else None


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--scores',type=Path,required=True)
    parser.add_argument('--rankings',type=Path,required=True)
    parser.add_argument('--pool',type=Path,required=True)
    parser.add_argument('--deepseek',type=Path,required=True)
    parser.add_argument('--qrels',type=Path,required=True)
    parser.add_argument('--policy',type=Path,required=True)
    parser.add_argument('--outdir',type=Path,required=True)
    args=parser.parse_args()
    scores=json.loads(args.scores.read_text(encoding='utf-8'))
    rankings=json.loads(args.rankings.read_text(encoding='utf-8'))
    pool=json.loads(args.pool.read_text(encoding='utf-8'))
    deep=json.loads(args.deepseek.read_text(encoding='utf-8'))
    qrels=json.loads(args.qrels.read_text(encoding='utf-8'))
    policy=json.loads(args.policy.read_text(encoding='utf-8'))
    args.outdir.mkdir(parents=True,exist_ok=True)
    if scores['qualityTrack']!='MODEL_ASSISTED_EXPLORATORY' or scores['adoptionEligibility']!='INSUFFICIENT_NO_ENABLE':
        raise ValueError('Model-assisted guard missing')
    summaries={(s['split'],s['method']):s for s in scores['summaries'] if s['city']=='重庆市'}
    pair=next(p for p in scores['pairs'] if p['left']=='hybrid_rerank' and p['right']=='hybrid_rrf' and p['metric']=='nDCG@5')
    pair_by_split={x['split']:x for x in pair['byCitySplit'] if x['city']=='重庆市'}
    details=[]
    for split,group in pair_by_split.items():
        for row in group['queries']:
            details.append({'split':split,'queryId':row['queryId'],'query':row['query'],'familyId':row['familyId'],
                            'rerankNdcg5':row['left'],'hybridNdcg5':row['right'],'diffRerankMinusHybrid':row['diff'],'outcome':row['outcome'],'exclusionReason':''})
    seen={(x['split'],x['queryId']) for x in details}
    per_pair={}
    for row in scores['perQuery']:
        if row['method'] in ('hybrid_rerank','hybrid_rrf'):
            per_pair.setdefault((row['split'],row['queryId']),[]).append(row)
    for (split,query_id),rows in per_pair.items():
        if (split,query_id) in seen: continue
        status_set={r['status'] for r in rows}
        reasons=sorted({p.get('reason','POOL_COVERAGE_INCOMPLETE') for r in rows for p in r.get('poolCoverageProblems',[])})
        if status_set=={'NO_RELEVANT_JUDGMENTS'}: reason='NO_RELEVANT_JUDGMENTS'
        elif reasons: reason=';'.join(reasons)
        else: reason=';'.join(sorted(status_set))
        sample=rows[0]
        details.append({'split':split,'queryId':query_id,'query':sample['query'],'familyId':sample['familyId'],
                        'rerankNdcg5':None,'hybridNdcg5':None,'diffRerankMinusHybrid':None,'outcome':'EXCLUDED','exclusionReason':reason})
    details.sort(key=lambda x:(x['split'],x['queryId']))
    with (args.outdir/'rerank-vs-hybrid-per-query.csv').open('x',encoding='utf-8-sig',newline='') as f:
        fields=['split','queryId','query','familyId','rerankNdcg5','hybridNdcg5','diffRerankMinusHybrid','outcome','exclusionReason']
        writer=csv.DictWriter(f,fieldnames=fields);writer.writeheader();writer.writerows(details)
    # Strict sensitivity population: every pooled pair has a valid two-pass DeepSeek consensus equal to Luna.
    deepmap={(r['anonymousQueryId'],r['anonymousDocumentId']):r for r in deep['items']}
    lumap={(j['anonymousQueryId'],j['anonymousDocumentId']):j['grade'] for j in qrels['judgments']}
    unanimous=set()
    for q in pool['queries']:
        good=True
        for d in q['documents']:
            row=deepmap[(q['anonymousQueryId'],d['anonymousDocumentId'])]
            j1,j2=row['judgments']
            if not j1 or not j2 or j1['grade']!=j2['grade'] or j1['grade']!=lumap[(q['anonymousQueryId'],d['anonymousDocumentId'])]:
                good=False;break
        if good: unanimous.add(q['anonymousQueryId'])
    sensitivity={}
    for split in ('dev','holdout'):
        chosen=[d for d in details if d['split']==split and d['queryId'] in unanimous]
        wins=sum(x['outcome']=='WIN' for x in chosen);losses=sum(x['outcome']=='LOSS' for x in chosen);ties=sum(x['outcome']=='TIE' for x in chosen)
        sensitivity[split]={'strictAllCandidateCrossModelAgreementQueries':len(chosen),'rerankWins':wins,'rerankLosses':losses,'ties':ties,
                            'meanNdcg5Gain':mean([x['diffRerankMinusHybrid'] for x in chosen])}
    hold=pair_by_split['holdout']; dev=pair_by_split['dev']
    gains={s: summaries[(s,'hybrid_rerank')]['metrics']['nDCG@5']-summaries[(s,'hybrid_rrf')]['metrics']['nDCG@5'] for s in ('dev','holdout')}
    ranked=rankings['runs']
    rerank_ms=[q['stages']['rerankMs'] for run in ranked for q in run['queries']
               if q.get('methods',{}).get('hybrid_rerank',{}).get('status')=='OK' and 'rerankMs' in q.get('stages',{})]
    eligible_families=len({x['familyId'] for x in hold['queries']})
    by_family={}
    for row in hold['queries']:
        by_family.setdefault(row['familyId'],[]).append(row['diff'])
    family_ids=sorted(by_family)
    rng=random.Random(20261018)
    bootstrap_means=[]
    for _ in range(10000):
        sampled=rng.choices(family_ids,k=len(family_ids))
        values=[diff for family in sampled for diff in by_family[family]]
        bootstrap_means.append(mean(values))
    bootstrap_means.sort()
    bootstrap_ci=[bootstrap_means[math.ceil(.025*len(bootstrap_means))-1],bootstrap_means[math.ceil(.975*len(bootstrap_means))-1]]
    thresholds=policy['adoption']
    gates={
      'holdoutAnswerableQueriesAtLeast50':len(hold['queries'])>=thresholds['minimumAnswerableHoldoutQueries'],
      'holdoutFamiliesAtLeast25':eligible_families>=thresholds['minimumFamilies'],
      'holdoutAbsoluteNdcg5GainAtLeast030':gains['holdout']>=thresholds['minimumAbsoluteNdcg5Gain'],
      'holdoutWinsAtLeastTwiceLosses':hold['wins']>=thresholds['minimumWinLossRatio']*hold['losses'],
      'holdoutWinsMinusLossesAtLeast6':hold['wins']-hold['losses']>=thresholds['minimumWinsMinusLosses'],
      'familyBootstrapLower95StrictlyAboveZero':bootstrap_ci[0]>0,
      'all60HumanLabeled':False,
      'cpuRerankP95AtMost1000ms':nearest_rank(rerank_ms,.95)<=thresholds['maxCpuRerankP95Ms'],
      'realIntegratedTtftP95MeasuredAtMost5000ms':False,
      'javaMainChainVerified':False
    }
    report={'schemaVersion':'model-assisted-retrieval-evaluation-v1','qualityTrack':'MODEL_ASSISTED_EXPLORATORY',
            'adoptionDecision':'INSUFFICIENT_NO_ENABLE','policyFrozenBeforeQualityScoring':policy['frozenBeforeRetrievalQualityScoring'],
            'population':{'queries':80,'developmentQueries':20,'holdoutQueries':60,'judgments':1671,'fullPoolQueries':scores['coverage']['fullyJudgedQueries'],
                          'developmentAnswerableQueries':len(dev['queries']),'holdoutAnswerableQueries':len(hold['queries']),
                          'holdoutFamilies':eligible_families,'holdoutNoRelevantJudgmentQueries':60-len(hold['queries'])},
            'methodSummaries':{split:{method:summaries[(split,method)]['metrics'] for method in ('dense','bm25_jieba','hybrid_rrf','hybrid_rerank','bm25_bigram')} for split in ('dev','holdout')},
            'primaryPair':{'comparison':'hybrid_rerank minus hybrid_rrf','metric':'nDCG@5','tieTolerance':1e-12,
                           'development':{'rerankWins':dev['wins'],'rerankLosses':dev['losses'],'ties':dev['ties'],'meanGain':gains['dev']},
                           'holdout':{'rerankWins':hold['wins'],'rerankLosses':hold['losses'],'ties':hold['ties'],'meanGain':gains['holdout'],'meanRerankNdcg5':summaries[('holdout','hybrid_rerank')]['metrics']['nDCG@5'],'meanHybridNdcg5':summaries[('holdout','hybrid_rrf')]['metrics']['nDCG@5']}},
            'familyBootstrap':{'unit':'familyId','iterations':10000,'seed':20261018,'eligibleFamilies':eligible_families,
                               'meanDiff95PercentileInterval':bootstrap_ci,'lowerBoundStrictlyAboveZero':bootstrap_ci[0]>0,
                               'estimator':'sample sorted families with replacement, include all paired query diffs for each sampled family, average query diffs; nearest rank'},
            'strictAgreementSensitivity':sensitivity,
            'latency':{'rerankStageObservations':len(rerank_ms),'rerankStageP95Ms':nearest_rank(rerank_ms,.95),'rerankStageLimitMs':thresholds['maxCpuRerankP95Ms'],
                       'integratedChatTtftP95Ms':None,'integratedChatTtftLimitMs':thresholds['maxIntegratedChatTtftP95Ms']},
            'preRegisteredGates':gates,'limitations':scores['limitations']}
    (args.outdir/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    lines=['# GPT-6 Luna 模型辅助检索评分','',
      '本报告使用 GPT-6 Luna 单轮主标签，定位为模型辅助探索性评分，不是人工金标结果。DeepSeek 同模型双轮仅作复核信号。采用门槛在计算质量分数前冻结。', '',
      '## 覆盖','',f"- 全量：80题、1,671个候选判断；开发集可答题 {len(dev['queries'])}/20，保留集可答题 {len(hold['queries'])}/60。各候选均有Luna标签；无相关候选题单独排除，不造nDCG=1。", '',
      '## 主要比较：hybrid_rerank 对 hybrid_rrf（nDCG@5）','',
      f"- 开发集：rerank {summaries[('dev','hybrid_rerank')]['metrics']['nDCG@5']:.4f}，hybrid {summaries[('dev','hybrid_rrf')]['metrics']['nDCG@5']:.4f}，绝对差 {gains['dev']:+.4f}；胜/负/平 {dev['wins']}/{dev['losses']}/{dev['ties']}。",
      f"- 保留集：rerank {summaries[('holdout','hybrid_rerank')]['metrics']['nDCG@5']:.4f}，hybrid {summaries[('holdout','hybrid_rrf')]['metrics']['nDCG@5']:.4f}，绝对差 {gains['holdout']:+.4f}；胜/负/平 {hold['wins']}/{hold['losses']}/{hold['ties']}。", '',
      '逐题 nDCG@5 差值、胜负平和排除理由见 `rerank-vs-hybrid-per-query.csv`。两套 split 指标未合并。其他方法、Recall、nDCG@1/3/10 与 MRR 位于机器可读摘要及底层评分 JSON。','',
      '## 采用门槛核对','',f"- 保留集绝对提升门槛：≥{thresholds['minimumAbsoluteNdcg5Gain']:.2f}；观察到 {gains['holdout']:+.4f}。",
      f"- 胜负门槛：胜题至少负题 {thresholds['minimumWinLossRatio']} 倍且净胜至少 {thresholds['minimumWinsMinusLosses']}；观察到 {hold['wins']}/{hold['losses']}，净胜 {hold['wins']-hold['losses']}。",
      f"- family bootstrap 10,000次95%百分位区间：[{bootstrap_ci[0]:+.4f}, {bootstrap_ci[1]:+.4f}]；下界大于0：{bootstrap_ci[0]>0}。",
      f"- 保留集题数 {len(hold['queries'])}，可答题 family {eligible_families}。严格全候选跨模型同分敏感性样本：开发 {sensitivity['dev']['strictAllCandidateCrossModelAgreementQueries']} 题，保留 {sensitivity['holdout']['strictAllCandidateCrossModelAgreementQueries']} 题。",
      f"- rerank 阶段 p95 {nearest_rank(rerank_ms,.95):.1f}ms，门槛 {thresholds['maxCpuRerankP95Ms']}ms；真实聊天端到端 TTFT 未测。",
      '- 本轮标签均由模型生成、未有人审；因此人工门槛不通过，固定结论为 `INSUFFICIENT_NO_ENABLE`。本轮不启用 rerank。', '',
      '## 限制','',
      '- 同模型两轮和 GPT-6 Luna 一轮有共享/相关误差的可能。跨模型同分不等于真实相关性，分歧也不等于模型哪一方错误。',
      '- Pooled Recall 只相对已检索并判定的 Top-10 并集，不是全语料召回；仅重庆资料文本相关性，未核查事实。',
      '- 当前数值适合决定下一步人工复核优先级，不可用来声称人类质量或全国表现。']
    (args.outdir/'REPORT.md').write_text('\n'.join(lines)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({'development':report['primaryPair']['development'],'holdout':report['primaryPair']['holdout'],'holdoutFamilies':eligible_families,'rerankP95Ms':report['latency']['rerankStageP95Ms'],'decision':'INSUFFICIENT_NO_ENABLE','strictAgreementSensitivity':sensitivity},ensure_ascii=False))


if __name__=='__main__':
    main()
