"""Acquisition provenance tests use tiny local files; never install or call a model."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

loader = importlib.util.spec_from_file_location("smoke", Path(__file__).with_name("local-retrieval-smoke.py"))
smoke = importlib.util.module_from_spec(loader)
loader.loader.exec_module(smoke)


class AcquisitionTest(unittest.TestCase):
    def test_offline_revision_change_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "config.json").write_text("{}", encoding="utf-8")
            spec = {"repoId": "author/model", "revision": "a" * 40, "files": ["config.json"]}
            smoke.record_acquisition(directory, spec)
            self.assertEqual(len(smoke.verify_acquisition(directory, spec)), 1)
            with self.assertRaisesRegex(ValueError, "revision differs"):
                smoke.verify_acquisition(directory, {**spec, "revision": "b" * 40})

    def test_file_drift_and_missing_acquisition_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            spec = {"repoId": "author/model", "revision": "a" * 40, "files": ["config.json"]}
            (directory / "config.json").write_text("{}", encoding="utf-8")
            with self.assertRaises(FileNotFoundError):
                smoke.verify_acquisition(directory, spec)
            smoke.record_acquisition(directory, spec)
            (directory / "config.json").write_text('{"changed":true}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "bytes differ"):
                smoke.verify_acquisition(directory, spec)


if __name__ == "__main__":
    unittest.main()
