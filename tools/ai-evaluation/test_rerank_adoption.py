"""Synthetic unit fixtures only: these are not measured project AI quality results."""
import copy
import importlib.util
import json
from pathlib import Path
import random
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('rerank_adoption', HERE/'rerank-adoption.py')
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)
FROZEN_POLICY = HERE.parents[1]/'docs/refactor/evidence/retrieval-annotation-20261018/rerank-adoption-policy.json'


def packed(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode('utf-8')


def fixture(*, labeled=True, delta=.10, families=30, zero_relevant=0, dev_unjudged=True, stage_ms=100):
    policy = json.loads(FROZEN_POLICY.read_text(encoding='utf-8'))
    rankings = {'schemaVersion': 'retrieval-rankings-v1', 'corpusSha256': 'a'*64,
                'configSha256': 'b'*64, 'queriesSha256': 'c'*64, 'runs': []}
    scored = {'schemaVersion': 'retrieval-pool-scores-v1', 'status': 'PARTIAL' if labeled else 'NOT_SCORED',
              'reviewer': {'name': 'Synthetic unit fixture', 'type': 'HUMAN_SELF_ATTESTED'} if labeled else None,
              'artifactSha256': {'annotations': 'd'*64 if labeled else None},
              'perQuery': [], 'pairs': [],
              'poolCoverage': {'complete': True, 'problems': [], 'topK': 10,
                               'expectedMethods': list(tool.METHODS), 'repeats': [1, 2, 3]}}
    for repeat in [1, 2, 3]:
        run = {'repeat': repeat, 'queries': []}
        for i in range(61):
            holdout = i < 60
            q = {'id': f'q{i:03}', 'query': f'Synthetic question {i}', 'city': '重庆市',
                 'split': 'holdout' if holdout else 'dev', 'familyId': f'family-{(i//2)%families:02}',
                 'methods': {m: {'status': 'OK', 'ranking': []} for m in tool.METHODS},
                 'stages': {'rerankMs': stage_ms}}
            run['queries'].append(q)
            for method in tool.METHODS:
                status = ('UNJUDGED_POOL' if not labeled or (not holdout and dev_unjudged)
                          else 'NO_RELEVANT_JUDGMENTS' if i >= 60-zero_relevant and holdout else 'SCORED')
                value = .5 + (delta if method == 'hybrid_rerank' else 0)
                row = {'repeat': repeat, 'queryId': q['id'],
                       **{k: q[k] for k in ['query', 'city', 'split', 'familyId']},
                       'method': method, 'status': status,
                       'metrics': {'nDCG@5': value} if status == 'SCORED' else None,
                       'poolCoverageProblems': []}
                scored['perQuery'].append(row)
        rankings['runs'].append(run)
    refresh_pairs(scored)
    return policy, rankings, scored


def refresh_pairs(scored):
    groups = {}
    by_q = {}
    for row in scored['perQuery']:
        if row['method'] in ['hybrid_rrf', 'hybrid_rerank']:
            by_q.setdefault(row['queryId'], []).append(row)
    for qid, rows in by_q.items():
        if any(r['status'] != 'SCORED' or r['poolCoverageProblems'] for r in rows):
            continue
        left = tool.mean([r['metrics']['nDCG@5'] for r in rows if r['method'] == 'hybrid_rerank'])
        right = tool.mean([r['metrics']['nDCG@5'] for r in rows if r['method'] == 'hybrid_rrf'])
        q = {'queryId': qid, **{k: rows[0][k] for k in ['query', 'city', 'split', 'familyId']},
             'left': left, 'right': right, 'diff': left-right}
        groups.setdefault((q['city'], q['split']), []).append(q)
    scored['pairs'] = [{'left': 'hybrid_rerank', 'right': 'hybrid_rrf', 'metric': 'nDCG@5',
                        'byCitySplit': [{'city': city, 'split': split, 'queries': rows}
                                        for (city, split), rows in groups.items()], 'excluded': []}]


def bind(f):
    policy, rankings, scored = copy.deepcopy(f)
    rb = packed(rankings)
    inputs = {'rankingsSha256': tool.digest(rb), **{k: rankings[k] for k in ['corpusSha256', 'configSha256', 'queriesSha256']}}
    pool_id = tool.digest(packed(inputs))
    policy['appliesTo']['rankingsSha256'] = tool.digest(rb)
    policy['appliesTo']['poolId'] = scored['poolId'] = pool_id
    scored['inputs'] = inputs
    return packed(policy), rb, packed(scored)


class AdoptionTest(unittest.TestCase):
    def test_real_frozen_policy_is_loaded_not_reimplemented_thresholds(self):
        p = fixture()[0]
        self.assertEqual(p['quality']['minimumAbsoluteMeanGain'], .03)
        self.assertEqual(p['quality']['minimumAnswerablePairedQueries'], 50)
        self.assertEqual(p['quality']['minimumPairedFamilies'], 25)
        self.assertEqual(p['quality']['bootstrap']['iterations'], 10000)
        self.assertEqual(p['quality']['bootstrap']['seed'], 20261018)
        self.assertEqual(p['latency']['maximumAdditionalRerankP95Ms'], 1000)

    def test_no_annotations_do_not_invent_gain_or_bootstrap(self):
        r = tool.evaluate(*bind(fixture(labeled=False, stage_ms=3149)))
        self.assertEqual(r['quality']['status'], 'UNKNOWN')
        self.assertIsNone(r['quality']['meanGain'])
        self.assertIsNone(r['quality']['bootstrap'])
        self.assertEqual(r['latency']['status'], 'FAIL')
        self.assertEqual(r['decision'], 'INSUFFICIENT_NO_ENABLE')
        self.assertFalse(r['canEnableInteractive'])

    def test_holdout_can_pass_while_dev_is_unjudged_and_report_partial(self):
        r = tool.evaluate(*bind(fixture()))
        self.assertEqual(r['quality']['status'], 'PASS')
        self.assertEqual(r['quality']['answerablePairedQueries'], 60)
        self.assertEqual(r['quality']['pairedFamilies'], 30)
        self.assertEqual(r['quality']['wins'], 60)
        self.assertEqual(r['quality']['bootstrap']['lowerIndexZeroBased'], 249)
        self.assertEqual(r['quality']['bootstrap']['upperIndexZeroBased'], 9749)
        self.assertEqual(r['decision'], 'INSUFFICIENT_NO_ENABLE')
        self.assertEqual(r['integratedTTFT']['measurement'], 'NOT_MEASURED')
        self.assertFalse(r['canEnableInteractive'])

    def test_quality_pass_latency_fail_only_offline_candidate(self):
        r = tool.evaluate(*bind(fixture(stage_ms=3149)))
        self.assertEqual(r['quality']['status'], 'PASS')
        self.assertEqual(r['latency']['status'], 'FAIL')
        self.assertEqual(r['decision'], 'OFFLINE_CANDIDATE_ONLY_NO_INTERACTIVE_ENABLE')
        self.assertFalse(r['canEnableInteractive'])

    def test_low_gain_fails_registered_threshold(self):
        r = tool.evaluate(*bind(fixture(delta=.02)))
        self.assertEqual(r['quality']['status'], 'FAIL')
        self.assertFalse(r['quality']['checks']['meanGain'])
        self.assertEqual(r['decision'], 'NO_ENABLE')

    def test_registered_gain_and_latency_boundaries_are_inclusive(self):
        r = tool.evaluate(*bind(fixture(delta=.03, stage_ms=1000)))
        self.assertTrue(r['quality']['checks']['meanGain'])
        self.assertEqual(r['quality']['status'], 'PASS')
        self.assertEqual(r['latency']['status'], 'PASS')
        self.assertFalse(r['canEnableInteractive'])

    def test_cluster_uncertainty_can_fail_despite_mean_gain_and_win_gates(self):
        f = fixture(delta=.08)
        for row in f[2]['perQuery']:
            if row['method'] == 'hybrid_rerank' and row['split'] == 'holdout' and int(row['queryId'][1:]) >= 50:
                row['metrics']['nDCG@5'] = .3
        refresh_pairs(f[2])
        r = tool.evaluate(*bind(f))
        self.assertTrue(r['quality']['checks']['meanGain'])
        self.assertTrue(r['quality']['checks']['winsAtLeastTwiceLosses'])
        self.assertTrue(r['quality']['checks']['winsMinusLosses'])
        self.assertFalse(r['quality']['checks']['clusteredIntervalLowerPositive'])
        self.assertEqual(r['quality']['status'], 'FAIL')

    def test_win_loss_gate_is_not_just_mean_gain(self):
        f = fixture(delta=.01)
        for row in f[2]['perQuery']:
            if row['method'] == 'hybrid_rerank' and row['split'] == 'holdout':
                row['metrics']['nDCG@5'] = .99 if int(row['queryId'][1:]) < 10 else .49
        refresh_pairs(f[2])
        r = tool.evaluate(*bind(f))
        self.assertTrue(r['quality']['checks']['meanGain'])
        self.assertFalse(r['quality']['checks']['winsAtLeastTwiceLosses'])
        self.assertEqual(r['quality']['status'], 'FAIL')

    def test_zero_relevant_questions_are_excluded_not_perfect(self):
        r = tool.evaluate(*bind(fixture(zero_relevant=10)))
        self.assertEqual(r['quality']['answerablePairedQueries'], 50)
        self.assertEqual(r['quality']['pairedFamilies'], 25)
        self.assertEqual(r['quality']['status'], 'PASS')
        self.assertTrue(all(e['reason'] == 'NO_RELEVANT_JUDGMENTS' for e in r['quality']['excluded']))
        s = tool.evaluate(*bind(fixture(zero_relevant=11)))
        self.assertEqual(s['quality']['status'], 'UNKNOWN')
        self.assertIsNone(s['quality']['meanGain'])

    def test_family_count_not_repeats_controls_sufficiency(self):
        r = tool.evaluate(*bind(fixture(families=20)))
        self.assertEqual(r['quality']['pairedFamilies'], 20)
        self.assertEqual(r['quality']['status'], 'UNKNOWN')
        self.assertIsNone(r['quality']['bootstrap'])

    def test_family_bootstrap_matches_registered_question_weighted_sampling(self):
        rows = [{'familyId': 'B', 'diff': 1}, {'familyId': 'A', 'diff': 0},
                {'familyId': 'A', 'diff': 0}, {'familyId': 'A', 'diff': 0}]
        r = tool.clustered_bootstrap(rows, 10000, 20261018)
        rng = random.Random(20261018)
        samples = []
        for _ in range(10000):
            picked = rng.choices(['A', 'B'], k=2)
            differences = [x for family in picked for x in ([0, 0, 0] if family == 'A' else [1])]
            samples.append(sum(differences)/len(differences))
        samples.sort()
        self.assertEqual(r['lower95'], samples[249])
        self.assertEqual(r['upper95'], samples[9749])
        self.assertNotEqual(tool.mean([0, 0, 0, 1]), tool.mean([0, 1]))

    def test_error_repeat_or_incomplete_pool_blocks_quality_even_with_math_values(self):
        for error in [True, False]:
            f = fixture()
            row = next(r for r in f[2]['perQuery'] if r['queryId'] == 'q000' and r['repeat'] == 2 and r['method'] == 'bm25_bigram')
            problem = {'repeat': 2, 'queryId': 'q000', 'method': 'bm25_bigram',
                       'status': 'ERROR' if error else 'INCOMPLETE_TOP10'}
            f[2]['poolCoverage']['complete'] = False
            f[2]['poolCoverage']['problems'].append(problem)
            row['poolCoverageProblems'] = [problem]
            if error:
                f[1]['runs'][1]['queries'][0]['methods']['bm25_bigram'] = {'status': 'ERROR', 'error': 'synthetic timeout'}
                row['status'] = 'ERROR'; row['metrics'] = None
            r = tool.evaluate(*bind(f))
            self.assertEqual(r['quality']['status'], 'UNKNOWN')
            self.assertIsNone(r['quality']['meanGain'])
            self.assertIsNone(r['quality']['bootstrap'])

    def test_hashes_pairs_duplicates_and_missing_human_provenance_fail(self):
        pb, rb, sb = bind(fixture())
        with self.assertRaisesRegex(ValueError, 'rankings hash'):
            tool.evaluate(pb, rb+b' ', sb)
        for mutate in [lambda s: s['inputs'].update(configSha256='0'*64),
                       lambda s: s['perQuery'].append(s['perQuery'][0]),
                       lambda s: s['pairs'][0]['byCitySplit'][0]['queries'][0].update(diff=.9),
                       lambda s: s.update(reviewer=None),
                       lambda s: s['artifactSha256'].update(annotations=None)]:
            s = json.loads(sb);mutate(s)
            with self.assertRaises(ValueError):
                tool.evaluate(pb, rb, packed(s))

    def test_p95_is_stage_percentile_not_difference_of_percentiles(self):
        f = fixture(labeled=False)
        for run in f[1]['runs']:
            for i, q in enumerate(run['queries']):
                q['stages']['rerankMs'] = 2000 if i % 5 == 0 else 1
                for result in q['methods'].values():
                    result['latencyMs'] = 999999
        r = tool.evaluate(*bind(f))
        self.assertEqual(r['latency']['extraRerankP95Ms'], 2000)
        self.assertEqual(r['latency']['observations'], 183)
        self.assertEqual(r['latency']['status'], 'FAIL')

    def test_failed_rerank_timing_is_not_treated_as_complete_latency_evidence(self):
        f = fixture(labeled=False)
        f[1]['runs'][1]['queries'][0]['methods']['hybrid_rerank'] = {'status': 'ERROR'}
        problem = {'repeat': 2, 'queryId': 'q000', 'method': 'hybrid_rerank', 'status': 'ERROR'}
        f[2]['poolCoverage']['complete'] = False
        f[2]['poolCoverage']['problems'].append(problem)
        row = next(r for r in f[2]['perQuery'] if r['queryId'] == 'q000' and r['repeat'] == 2 and r['method'] == 'hybrid_rerank')
        row.update(status='ERROR', metrics=None, poolCoverageProblems=[problem])
        r = tool.evaluate(*bind(f))
        self.assertEqual(r['latency']['status'], 'UNKNOWN')
        self.assertEqual(r['latency']['missing'][0]['reason'], 'RERANK_EXECUTION_FAILED')
        self.assertEqual(r['quality']['status'], 'UNKNOWN')

    def test_cli_creates_once_and_refuses_overwrite(self):
        values = bind(fixture(labeled=False))
        with tempfile.TemporaryDirectory(prefix='rerank-adoption-test-') as temporary:
            directory = Path(temporary)
            paths = [directory/name for name in ['policy.json', 'rankings.json', 'scored.json']]
            for p, v in zip(paths, values):
                p.write_bytes(v)
            output = directory/'result.json'
            cmd = [sys.executable, str(HERE/'rerank-adoption.py')]
            for flag, path in zip(['--policy', '--rankings', '--scored'], paths):
                cmd += [flag, str(path)]
            cmd += ['--output', str(output)]
            first = subprocess.run(cmd, capture_output=True, text=True)
            self.assertEqual(first.returncode, 0, first.stderr)
            original = output.read_bytes()
            second = subprocess.run(cmd, capture_output=True, text=True)
            self.assertNotEqual(second.returncode, 0)
            self.assertEqual(output.read_bytes(), original)


if __name__ == '__main__':
    unittest.main()
