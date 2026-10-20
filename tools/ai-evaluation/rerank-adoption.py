"""Offline decision support for the registered v1 retrieval experiment.

This tool never enables production. It consumes a scored artifact, not annotations,
and therefore validates the artifact's declared human provenance, not human identity.
"""
import argparse
from collections import defaultdict
import hashlib
import json
import math
from pathlib import Path
import random

METHODS = ('dense', 'bm25_jieba', 'hybrid_rrf', 'hybrid_rerank', 'bm25_bigram')
SCORE_STATES = {'SCORED', 'NO_RELEVANT_JUDGMENTS', 'UNJUDGED_POOL',
                'UNJUDGED_RESULT', 'ERROR', 'MISSING_METHOD', 'MISSING_QUERY'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(value):
    return hashlib.sha256(value).hexdigest()


def number(value, label):
    require(isinstance(value, (int, float)) and not isinstance(value, bool)
            and math.isfinite(value), f'{label} must be finite numeric')
    return value


def mean(values):
    return sum(values) / len(values)


def nearest_rank(values, p):
    require(bool(values), 'cannot calculate percentile without observations')
    return sorted(values)[math.ceil(p * len(values)) - 1]


def clustered_bootstrap(rows, iterations, seed):
    """Resample families, retaining all questions and question weights in each draw."""
    groups = defaultdict(list)
    for row in rows:
        groups[row['familyId']].append(row['diff'])
    families = sorted(groups)  # Python Unicode codepoint ordering, per registration.
    require(bool(families), 'bootstrap requires families')
    rng = random.Random(seed)
    samples = []
    for _ in range(iterations):
        picked = rng.choices(families, k=len(families))
        samples.append(mean([x for family in picked for x in groups[family]]))
    return {'iterations': iterations, 'seed': seed,
            'unit': 'familyId', 'familyCount': len(families),
            'estimator': 'mean paired query differences after family resampling',
            'percentileMethod': 'nearest-rank',
            'lowerIndexZeroBased': math.ceil(.025 * iterations) - 1,
            'upperIndexZeroBased': math.ceil(.975 * iterations) - 1,
            'lower95': nearest_rank(samples, .025),
            'upper95': nearest_rank(samples, .975)}


def parse_bytes(value):
    return json.loads(value.decode('utf-8-sig'),
                      parse_constant=lambda x: (_ for _ in ()).throw(ValueError(f'invalid JSON constant {x}')))


def evaluate(policy_bytes, rankings_bytes, scored_bytes):
    policy, rankings, scored = map(parse_bytes, [policy_bytes, rankings_bytes, scored_bytes])
    require(policy['schemaVersion'] == 'rerank-adoption-policy-v1', 'unsupported policy schema')
    require(rankings['schemaVersion'] == 'retrieval-rankings-v1', 'invalid rankings schema')
    require(scored['schemaVersion'] == 'retrieval-pool-scores-v1', 'invalid scored schema')
    applies, rule = policy['appliesTo'], policy['quality']
    require(applies['comparison'] == ['hybrid_rerank', 'hybrid_rrf'], 'unsupported comparison')
    require(applies['split'] == 'holdout' and rule['primaryMetric'] == 'nDCG@5', 'unsupported primary endpoint')
    require(rule['bootstrap']['iterations'] == 10000 and rule['bootstrap']['unit'] == 'familyId', 'unsupported bootstrap policy')
    for field in ['minimumAbsoluteMeanGain', 'minimumAnswerablePairedQueries',
                  'minimumPairedFamilies', 'tiesEpsilon', 'minimumWinLossRatio', 'minimumWinsMinusLosses']:
        require(number(rule[field], field) >= 0, f'negative policy threshold {field}')
    require(isinstance(rule['bootstrap']['seed'], int), 'invalid bootstrap seed')
    require(digest(rankings_bytes) == applies['rankingsSha256'], 'policy rankings hash mismatch')
    expected_inputs = {'rankingsSha256': digest(rankings_bytes),
                       **{k: rankings[k] for k in ['corpusSha256', 'configSha256', 'queriesSha256']}}
    require(scored['inputs'] == expected_inputs, 'scored input hashes mismatch')
    for value in expected_inputs.values():
        require(isinstance(value, str) and len(value) == 64 and all(c in '0123456789abcdef' for c in value), 'invalid input SHA')
    # Match the JS pool builder's insertion order and compact JSON encoding.
    pool_id = digest(json.dumps(expected_inputs, ensure_ascii=False, separators=(',', ':')).encode('utf-8'))
    require(scored['poolId'] == applies['poolId'] == pool_id, 'pool binding mismatch')
    require(scored['status'] in ['SCORED', 'PARTIAL', 'NOT_SCORED'], 'invalid scored report status')
    require(isinstance(scored.get('poolCoverage'), dict), 'missing pool coverage')
    pool_coverage = scored['poolCoverage']
    require(isinstance(pool_coverage.get('problems'), list), 'missing pool coverage problems')
    require(isinstance(pool_coverage.get('complete'), bool)
            and pool_coverage['complete'] == (len(pool_coverage['problems']) == 0), 'inconsistent pool coverage')
    require(pool_coverage.get('expectedMethods') == list(METHODS) and pool_coverage.get('topK') == 10, 'unexpected pool design')

    queries, raw, repeats = {}, {}, set()
    stages, stage_missing = [], []
    for run in rankings['runs']:
        repeat = run['repeat']
        require(isinstance(repeat, int) and repeat > 0 and repeat not in repeats, 'duplicate or invalid repeat')
        repeats.add(repeat)
        seen = set()
        for query in run['queries']:
            qid = query['id']
            require(isinstance(qid, str) and qid and qid not in seen, 'duplicate or invalid ranked query')
            seen.add(qid)
            metadata = {k: query.get(k) for k in ['query', 'city', 'split', 'familyId']}
            require(all(isinstance(v, str) and v for v in metadata.values()), 'missing query metadata')
            require(qid not in queries or queries[qid] == metadata, 'query metadata changed between repeats')
            queries[qid] = metadata
            require(not (set(query['methods']) - set(METHODS)), 'unknown ranked method')
            for method in METHODS:
                result = query['methods'].get(method, {'status': 'MISSING_METHOD'})
                require(result['status'] in ['OK', 'ERROR', 'MISSING_METHOD'], 'invalid method execution state')
                raw[(repeat, qid, method)] = result
            stage = query.get('stages', {}).get('rerankMs')
            if stage is None:
                require(query['methods'].get('hybrid_rerank', {}).get('status') != 'OK', 'successful rerank lacks rerankMs')
                stage_missing.append({'repeat': repeat, 'queryId': qid, 'reason': 'RERANK_STAGE_NOT_RECORDED'})
            else:
                require(number(stage, 'rerankMs') >= 0, 'negative rerank stage')
                stages.append(stage)
                if query['methods'].get('hybrid_rerank', {}).get('status') != 'OK':
                    stage_missing.append({'repeat': repeat, 'queryId': qid, 'reason': 'RERANK_EXECUTION_FAILED'})
    require(repeats == {1, 2, 3}, 'registered experiment requires repeats 1,2,3')
    for repeat in sorted(repeats):
        for qid in queries:
            if (repeat, qid, 'hybrid_rerank') not in raw:
                stage_missing.append({'repeat': repeat, 'queryId': qid, 'reason': 'MISSING_QUERY'})
    require(pool_coverage.get('repeats') == [1, 2, 3], 'pool repeats mismatch')
    holdout = {qid: q for qid, q in queries.items() if q['split'] == 'holdout'}
    require(len(holdout) == 60, 'registered holdout must contain 60 queries')
    require({q['city'] for q in holdout.values()} == {'重庆市'}, 'registered scope is Chongqing only')
    rows = {}
    for row in scored['perQuery']:
        k = (row['repeat'], row['queryId'], row['method'])
        require(k not in rows, 'duplicate scored row')
        require(row['repeat'] in repeats and row['queryId'] in queries and row['method'] in METHODS, 'unknown scored row')
        require(all(row.get(p) == queries[row['queryId']][p] for p in ['query', 'city', 'split', 'familyId']), 'scored query metadata mismatch')
        require(row['status'] in SCORE_STATES, 'unknown scoring state')
        result = raw.get(k, {'status': 'MISSING_QUERY'})
        if result['status'] != 'OK':
            require(row['status'] == result['status'], 'execution error was hidden by scored report')
        else:
            require(row['status'] not in ['ERROR', 'MISSING_METHOD', 'MISSING_QUERY'], 'scored execution state contradicts rankings')
        problems = [p for p in pool_coverage['problems'] if p.get('repeat') == row['repeat']
                    and p.get('queryId') == row['queryId'] and p.get('method', row['method']) == row['method']]
        require(row.get('poolCoverageProblems') == problems, 'scored row pool coverage mismatch')
        if row['status'] == 'SCORED':
            metric = number(row['metrics']['nDCG@5'], 'nDCG@5')
            require(0 <= metric <= 1 + 1e-12, 'invalid nDCG range')
        else:
            require(row.get('metrics') is None, 'unscored row contains quality values')
        rows[k] = row
    expected_keys = {(repeat, qid, method) for repeat in repeats for qid in queries for method in METHODS}
    require(set(rows) == expected_keys, 'missing or extra scored rows')

    # Independent from top-level SCORED/PARTIAL: dev may still be unjudged.
    excluded, eligible = [], []
    for qid, metadata in holdout.items():
        qrows = [rows[(repeat, qid, method)] for repeat in sorted(repeats) for method in METHODS]
        states = {r['status'] for r in qrows}
        require(not ('SCORED' in states and states.intersection({'NO_RELEVANT_JUDGMENTS', 'UNJUDGED_POOL'})), 'query has contradictory human judgment states')
        problems = [{'repeat': r['repeat'], 'method': r['method'], 'status': r['status'],
                     'poolCoverageProblems': r['poolCoverageProblems']} for r in qrows
                    if r['status'] not in ['SCORED', 'NO_RELEVANT_JUDGMENTS'] or r['poolCoverageProblems']]
        if problems:
            excluded.append({'queryId': qid, 'reason': 'INCOMPLETE_HUMAN_OR_EXECUTION_COVERAGE', 'details': problems})
        elif states == {'NO_RELEVANT_JUDGMENTS'}:
            excluded.append({'queryId': qid, 'reason': 'NO_RELEVANT_JUDGMENTS'})
        else:
            left = mean([rows[(repeat, qid, 'hybrid_rerank')]['metrics']['nDCG@5'] for repeat in sorted(repeats)])
            right = mean([rows[(repeat, qid, 'hybrid_rrf')]['metrics']['nDCG@5'] for repeat in sorted(repeats)])
            eligible.append({'queryId': qid, **metadata, 'left': left, 'right': right, 'diff': left-right})

    # Check the scorer's independently reported paired means rather than trusting
    # one aggregate field. Fully covered pairs must appear once and agree.
    pairs = [p for p in scored['pairs'] if p.get('left') == 'hybrid_rerank'
             and p.get('right') == 'hybrid_rrf' and p.get('metric') == 'nDCG@5']
    require(len(pairs) == 1, 'missing or duplicate registered pair comparison')
    reported = {}
    for group in pairs[0]['byCitySplit']:
        if group['split'] != 'holdout':
            continue
        require(group['city'] == '重庆市', 'paired table city mismatch')
        for item in group['queries']:
            require(item['queryId'] not in reported, 'duplicate reported query pair')
            reported[item['queryId']] = item
    # A failed unrelated method can disqualify adoption while the scorer still
    # legitimately reports the rerank/hybrid comparison for that question.
    expected_paired = {}
    for qid, metadata in holdout.items():
        rs = [rows[(repeat, qid, method)] for repeat in sorted(repeats)
              for method in ['hybrid_rerank', 'hybrid_rrf']]
        if all(r['status'] == 'SCORED' and not r['poolCoverageProblems'] for r in rs):
            a = mean([rows[(repeat, qid, 'hybrid_rerank')]['metrics']['nDCG@5'] for repeat in sorted(repeats)])
            b = mean([rows[(repeat, qid, 'hybrid_rrf')]['metrics']['nDCG@5'] for repeat in sorted(repeats)])
            expected_paired[qid] = (a, b, metadata)
    require(set(reported) == set(expected_paired), 'paired query coverage mismatch')
    for qid, (a, b, metadata) in expected_paired.items():
        item = reported[qid]
        require(item.get('familyId') == metadata['familyId'] and item.get('query') == metadata['query'], 'paired query identity mismatch')
        require(all(abs(number(item[k], f'paired {k}')-v) <= rule['tiesEpsilon']
                    for k, v in [('left', a), ('right', b), ('diff', a-b)]), 'paired values contradict per-query repeats')

    reviewer = scored.get('reviewer')
    annotation_sha = scored.get('artifactSha256', {}).get('annotations')
    human_declared = (isinstance(reviewer, dict) and reviewer.get('type') == 'HUMAN_SELF_ATTESTED'
                      and isinstance(reviewer.get('name'), str) and bool(reviewer['name'].strip())
                      and isinstance(annotation_sha, str) and len(annotation_sha) == 64
                      and all(c in '0123456789abcdef' for c in annotation_sha))
    require(human_declared or not eligible, 'scored human quality lacks reviewer or annotation provenance')
    incomplete = [e for e in excluded if e['reason'] != 'NO_RELEVANT_JUDGMENTS']
    families = {r['familyId'] for r in eligible}
    quality = {'status': 'UNKNOWN', 'reason': None, 'holdoutQueries': len(holdout),
               'answerablePairedQueries': len(eligible), 'pairedFamilies': len(families),
               'excluded': excluded, 'meanGain': None, 'wins': None, 'losses': None,
               'ties': None, 'bootstrap': None, 'checks': None}
    if not human_declared or incomplete:
        quality['reason'] = 'INCOMPLETE_HUMAN_OR_EXECUTION_COVERAGE'
    elif len(eligible) < rule['minimumAnswerablePairedQueries'] or len(families) < rule['minimumPairedFamilies']:
        quality['reason'] = 'INSUFFICIENT_ANSWERABLE_QUERIES_OR_FAMILIES'
    else:
        gain = mean([r['diff'] for r in eligible])
        eps = rule['tiesEpsilon']
        wins = sum(r['diff'] > eps for r in eligible)
        losses = sum(r['diff'] < -eps for r in eligible)
        bootstrap = clustered_bootstrap(eligible, rule['bootstrap']['iterations'], rule['bootstrap']['seed'])
        checks = {'meanGain': gain >= rule['minimumAbsoluteMeanGain'],
                  'winsAtLeastTwiceLosses': wins >= losses*rule['minimumWinLossRatio'],
                  'winsMinusLosses': wins-losses >= rule['minimumWinsMinusLosses'],
                  'clusteredIntervalLowerPositive': bootstrap['lower95'] > 0}
        quality.update(status='PASS' if all(checks.values()) else 'FAIL', reason='REGISTERED_QUALITY_GATES',
                       meanGain=gain, wins=wins, losses=losses, ties=len(eligible)-wins-losses,
                       bootstrap=bootstrap, checks=checks)
    p95 = nearest_rank(stages, .95) if stages else None
    threshold = number(policy['latency']['maximumAdditionalRerankP95Ms'], 'latency budget')
    latency = {'status': 'UNKNOWN' if stage_missing or not stages else 'PASS' if p95 <= threshold else 'FAIL',
               'observations': len(stages), 'missing': stage_missing, 'extraRerankP95Ms': p95,
               'maximumExtraRerankP95Ms': threshold, 'scope': 'Fixed local CPU rerank stage only; not integrated chat TTFT'}
    if quality['status'] == 'UNKNOWN':
        decision = 'INSUFFICIENT_NO_ENABLE'
    elif quality['status'] == 'FAIL':
        decision = 'NO_ENABLE'
    elif latency['status'] == 'FAIL':
        decision = 'OFFLINE_CANDIDATE_ONLY_NO_INTERACTIVE_ENABLE'
    else:
        decision = 'INSUFFICIENT_NO_ENABLE'
    return {'schemaVersion': 'rerank-adoption-decision-v1',
            'artifacts': {'policySha256': digest(policy_bytes), 'rankingsSha256': digest(rankings_bytes),
                          'scoredReportSha256': digest(scored_bytes), 'annotationSha256': annotation_sha,
                          'registrationUnjudgedReportSha256': applies['unjudgedReportSha256'],
                          'poolId': pool_id, 'inputBindings': expected_inputs},
            'quality': quality, 'latency': latency,
            'integratedTTFT': {'status': 'UNKNOWN', 'measurement': 'NOT_MEASURED',
                               'maximumP95Ms': policy['latency']['maximumIntegratedTTFTP95Ms']},
            'decision': decision, 'canEnableInteractive': False,
            'limitations': ['No production switch is implemented',
                            'Human declaration and annotation file hash are provenance, not independently verified identity',
                            'Judged pool relevance is not exhaustive corpus recall or verified document truth',
                            'Clustered confidence interval is exploratory single-city evidence',
                            'No TTFT evidence input is accepted by this version']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['policy', 'rankings', 'scored', 'output']:
        parser.add_argument('--'+name, required=True, type=Path)
    args = parser.parse_args()
    try:
        result = evaluate(args.policy.read_bytes(), args.rankings.read_bytes(), args.scored.read_bytes())
        with args.output.open('x', encoding='utf-8', newline='\n') as stream:
            json.dump(result, stream, ensure_ascii=False, indent=2, allow_nan=False)
            stream.write('\n')
        print(json.dumps({'decision': result['decision'], 'quality': result['quality']['status'],
                          'latency': result['latency']['status'], 'canEnableInteractive': False}))
    except (ValueError, KeyError, TypeError, OSError) as exc:
        parser.exit(1, str(exc)+'\n')


if __name__ == '__main__':
    main()
