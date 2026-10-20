import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("candidate", Path(__file__).with_name("export_candidate.py"))
candidate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(candidate)


class ReviewExportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="审阅 空格-")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / "repository"
        self.root.mkdir()
        self.git("init", "-q")
        (self.root / ".gitignore").write_text("scripts/\n.env*\n", encoding="utf-8")
        self.git("add", ".gitignore")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "fixture")
        (self.root / "scripts").mkdir()
        (self.root / "scripts/check.mjs").write_text("export const fixture = true;\n", encoding="utf-8")
        (self.root / "ordinary.py").write_text("fixture = True\n", encoding="utf-8")
        self.output = self.base / "output"

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], stderr=subprocess.DEVNULL)

    def test_explicit_ignored_source_is_exported_and_verified(self):
        snapshot = candidate.export_repo("bff", self.root, self.output, ["scripts/check.mjs"])
        candidate.verify_repo("bff", snapshot, self.output)
        record = next(item for item in snapshot["files"] if item["path"] == "scripts/check.mjs")
        self.assertTrue(record["untracked"])
        self.assertEqual(snapshot["explicitFiles"], ["scripts/check.mjs"])
        self.assertEqual((self.output / "bff/files/scripts/check.mjs").read_bytes(),
                         (self.root / "scripts/check.mjs").read_bytes())
        self.assertTrue((self.output / "bff/files/ordinary.py").is_file())

    def test_explicit_credentials_and_generated_files_are_rejected(self):
        for name in [".env", ".ENV", "private.pem", "node_modules/source.js"]:
            file = self.root / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text("synthetic-fixture-only", encoding="utf-8")
            with self.subTest(name=name), self.assertRaises(ValueError):
                candidate.explicit_files(self.root, [name])

    def test_missing_file_and_repository_escape_are_rejected(self):
        outside = self.base / "outside.py"
        outside.write_text("fixture = True", encoding="utf-8")
        for name in ["../outside.py", str(outside), "missing.py", "scripts"]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                candidate.explicit_files(self.root, [name])

    def test_changed_ignored_source_fails_verification(self):
        snapshot = candidate.export_repo("bff", self.root, self.output, ["scripts/check.mjs"])
        (self.root / "scripts/check.mjs").write_text("export const fixture = false;", encoding="utf-8")
        with self.assertRaises(RuntimeError):
            candidate.verify_repo("bff", snapshot, self.output)

    def test_repository_subdirectory_cannot_mark_existing_files_as_deleted(self):
        nested = self.root / "web"
        nested.mkdir()
        (nested / "app.js").write_text("export const value = 1;", encoding="utf-8")
        self.git("add", "web/app.js")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "web fixture")
        (nested / "app.js").write_text("export const value = 2;", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Git repository root"):
            candidate.export_repo("frontend", nested, self.output)
        snapshot = candidate.export_repo("frontend", self.root, self.output)
        record = next(item for item in snapshot["files"] if item["path"] == "web/app.js")
        self.assertFalse(record["deleted"])
        self.assertEqual((self.output / "frontend/files/web/app.js").read_bytes(), (nested / "app.js").read_bytes())


if __name__ == "__main__":
    unittest.main()
