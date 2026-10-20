import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("prelabel", Path(__file__).with_name("ai-prelabel.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class PrelabelContracts(unittest.TestCase):
    def setUp(self):
        self.docs = [{"anonymousDocumentId": "Q-D1", "title": "title", "content": "七点开放，九点关闭。"}]
        self.row = {"anonymousDocumentId": "Q-D1", "grade": 2, "confidence": "HIGH", "quote": "七点开放", "reason": "直接说明时间"}

    def validate(self, rows):
        return m.validate(json.dumps({"judgments": rows}), self.docs)

    def test_exact_quote(self):
        self.assertEqual(self.validate([self.row])[0]["grade"], 2)

    def test_bad_quote(self):
        self.row["quote"] = "八点开放"
        with self.assertRaises(ValueError): self.validate([self.row])

    def test_blank_positive_quote(self):
        self.row["quote"] = " "
        with self.assertRaises(ValueError): self.validate([self.row])

    def test_bool_is_not_grade(self):
        self.row["grade"] = True
        with self.assertRaises(ValueError): self.validate([self.row])

    def test_duplicates_and_missing(self):
        for rows in ([], [self.row, self.row]):
            with self.assertRaises(ValueError): self.validate(rows)

    def test_unknown_id(self):
        self.row["anonymousDocumentId"] = "foreign"
        with self.assertRaises(ValueError): self.validate([self.row])

    def test_zero_no_quote(self):
        self.row.update(grade=0, quote="")
        self.assertEqual(self.validate([self.row])[0]["grade"], 0)

    def test_no_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp)/"x.json"
            m.write_new(p, {})
            with self.assertRaises(FileExistsError): m.write_new(p, {})

    def test_two_pass_no_leakage(self):
        q = {"anonymousQueryId":"Q", "query":"何时", "city":"重庆", "documents": [dict(self.docs[0], grade=2)]}
        p = m.query_payload(q, 1)
        self.assertNotIn("grade", p["documents"][0])
        self.assertNotIn("judgments", p)

    def test_missing_not_zero(self):
        pool={"queries":[{"anonymousQueryId":"Q", "documents":self.docs}]}
        item=m.merge(pool, [])[0]
        self.assertIsNone(item["consensusGrade"])
        self.assertIsNone(item["humanGrade"])
        self.assertTrue(item["unresolved"])

    def test_disagreement_not_consensus(self):
        pool={"queries":[{"anonymousQueryId":"Q", "documents":self.docs}]}
        rows=[{"anonymousQueryId":"Q", "pass":p, "judgments":[dict(self.row, grade=p)]} for p in (1,2)]
        item=m.merge(pool, rows)[0]
        self.assertTrue(item["disagreement"])
        self.assertIsNone(item["consensusGrade"])

    def test_endpoint_no_double_v1(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/"env"
            p.write_text("DEEPSEEK_API_KEY=fake\nDEEPSEEK_MODEL=fake\nDEEPSEEK_BASE_URL=https://example.com/v1\n",encoding="utf-8")
            self.assertEqual(m.env_config(p)[1],"https://example.com/v1/chat/completions")


if __name__ == "__main__": unittest.main()
