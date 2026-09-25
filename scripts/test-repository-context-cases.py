#!/usr/bin/env python3
"""Run directly with Python; no app, provider, credentials or network required."""

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("context_cases", Path(__file__).with_name("repository-context-cases.py"))
cases = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cases)


class TestRepositoryContextCases(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="bytequay-context-cases-test-")
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name) / "corpus"

    def test_frozen_bug_and_safe_change_have_distinct_reproducible_oracles(self):
        result = cases.create(self.output)
        self.assertEqual({name: value["exitCode"] for name, value in result["oracles"].items()},
                         {"legacy": 0, "base": 0, "bug": 1, "safe": 0})
        data = json.loads((self.output / "manifest.json").read_text())
        self.assertEqual(len(data["cases"]), 2)
        self.assertEqual(len(data["proposedNotes"]), 8)
        notes = {note["key"]: note for note in data["proposedNotes"]}
        self.assertNotEqual(notes["stale"]["proposedSource"]["blobId"], notes["correct"]["proposedSource"]["blobId"])
        self.assertEqual(notes["false"]["proposedSource"], notes["corrected"]["proposedSource"])
        verified = cases.verify(self.output)
        self.assertEqual({name: value["exitCode"] for name, value in verified["oracles"].items()},
                         {name: value["exitCode"] for name, value in result["oracles"].items()})
        cases.git(self.output / "repository", "checkout", "--detach", "-q", "fixture-safe")
        with self.assertRaisesRegex(ValueError, "fixture-base"):
            cases.verify(self.output)

    def test_manifest_tampering_is_rejected_before_any_oracle_executes(self):
        cases.create(self.output)
        path = self.output / "manifest.json"
        data = json.loads(path.read_text())
        data["cases"][0]["expectedOracleExit"] = 0
        path.write_text(json.dumps(data))
        with patch.object(cases, "run_oracles") as oracle:
            with self.assertRaisesRegex(ValueError, "Manifest integrity"):
                cases.verify(self.output)
            oracle.assert_not_called()

    def test_source_tampering_is_rejected_before_any_oracle_executes(self):
        cases.create(self.output)
        repository = self.output / "repository"
        (repository / "fields.py").write_text("raise RuntimeError('unexpected source must never run')\n")
        cases.git(repository, "add", "fields.py")
        cases.git(repository, "commit", "-qm", "Tampered fixture")
        cases.git(repository, "tag", "-f", "fixture-safe")
        with patch.object(cases, "run_oracles") as oracle:
            with self.assertRaisesRegex(ValueError, "Frozen source integrity"):
                cases.verify(self.output)
            oracle.assert_not_called()

    def test_existing_output_is_never_overwritten(self):
        self.output.mkdir()
        sentinel = self.output / "keep.txt"
        sentinel.write_bytes(b"caller-owned bytes\x00")
        with self.assertRaises(FileExistsError):
            cases.create(self.output)
        self.assertEqual(sentinel.read_bytes(), b"caller-owned bytes\x00")
        self.assertEqual(list(self.output.iterdir()), [sentinel])

    def test_git_hidden_checkout_changes_are_rejected_before_oracles(self):
        cases.create(self.output)
        repository = self.output / "repository"
        (repository / ".git" / "info" / "exclude").parent.mkdir(exist_ok=True)
        (repository / ".git" / "info" / "exclude").write_text("hidden.py\n")
        hidden = repository / "hidden.py"
        hidden.write_text("unexpected = True\n")
        with patch.object(cases, "run_oracles") as oracle:
            self.assertFalse(cases.git(repository, "status", "--porcelain").strip())
            with self.assertRaisesRegex(ValueError, "Checkout integrity"):
                cases.verify(self.output)
            hidden.unlink()
            cases.git(repository, "update-index", "--assume-unchanged", "fields.py")
            (repository / "fields.py").write_text("raise RuntimeError('hidden change')\n")
            self.assertFalse(cases.git(repository, "status", "--porcelain").strip())
            with self.assertRaisesRegex(ValueError, "Checkout integrity"):
                cases.verify(self.output)
            oracle.assert_not_called()


if __name__ == "__main__":
    unittest.main()
