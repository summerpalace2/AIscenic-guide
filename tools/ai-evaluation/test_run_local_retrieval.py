import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("retrieval_runner", Path(__file__).with_name("run-local-retrieval.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class RetrievalMechanicsTest(unittest.TestCase):
    def test_rrf_uses_ranks_not_incompatible_raw_score_scales(self):
        a = runner.ordered(["a", "b"], [100, 5], 2)
        b = runner.ordered(["a", "b"], [0.1, 0.9], 2)
        result = runner.rrf([a, b], 60, 2)
        self.assertAlmostEqual(result[0]["score"], 1 / 61 + 1 / 62)
        self.assertEqual([h["docId"] for h in result], ["a", "b"])

    def test_stable_ties_and_nonfinite_scores(self):
        self.assertEqual(runner.ordered(["b", "a"], [0, 0], 1)[0]["docId"], "a")
        with self.assertRaises(ValueError):
            runner.ordered(["a"], [float("nan")], 1)
        with self.assertRaises(ValueError):
            runner.ordered(["a", "a"], [1, 2], 2)

    def test_bigram_normalization_keeps_singletons_and_word_boundaries(self):
        self.assertEqual(runner.bigrams("ＡＢ 西湖，山"), ["ab", "西湖", "山"])

    def test_gold_projection_does_not_consume_provisional_answers(self):
        query = {"id": "q", "query": "x", "city": "c", "split": "dev", "familyId": "f", "gradedRelevantDocuments": [{"docId": "secret"}]}
        self.assertNotIn("gradedRelevantDocuments", runner.public_queries({"queries": [query]})[0])


if __name__ == "__main__":
    unittest.main()
