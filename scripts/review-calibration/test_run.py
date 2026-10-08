import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("calibration", Path(__file__).with_name("run.py"))
calibration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(calibration)


class TestFrozenInput(unittest.TestCase):
    def test_export_excludes_dirty_files_and_future_history(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = root / "repo"
            repo.mkdir()
            def git(*args):
                return subprocess.check_output(["git", "-C", str(repo), *args], stderr=subprocess.DEVNULL).decode().strip()
            git("init")
            git("config", "user.email", "fixture@example.invalid")
            git("config", "user.name", "Fixture")
            source = repo / "rows.py"
            source.write_text("base\n")
            (repo / ".gitattributes").write_text("rows.py export-ignore\n")
            git("add", ".")
            git("commit", "-m", "base")
            base = git("rev-parse", "HEAD")
            source.write_text("head\n")
            git("commit", "-am", "head")
            head = git("rev-parse", "HEAD")
            source.write_text("future answer\n")
            git("commit", "-am", "future")
            source.write_text("dirty answer\n")
            (repo / "review-comments.txt").write_text("untracked answer\n")
            policy = root / "policy.md"
            policy.write_text("assigned policy\n")
            context = root / "context.md"
            context.write_text("frozen intent\n")
            bundle, manifest = calibration.freeze(repo, base, head, policy, context, root / "output")
            self.assertEqual((bundle / "base/rows.py").read_text(), "base\n")
            self.assertEqual((bundle / "head/rows.py").read_text(), "head\n")
            self.assertFalse(list(bundle.rglob(".git")))
            self.assertFalse(list(bundle.rglob("review-comments.txt")))
            self.assertNotIn("answer", (bundle / "change.diff").read_text())
            self.assertEqual(manifest["mergeBase"], base)
            self.assertIn("head/rows.py", manifest["files"])
            with self.assertRaises(FileExistsError):
                calibration.freeze(repo, base, head, policy, context, root / "output")


class TestCompletion(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.output = Path(directory.name)

    def events(self, *types):
        (self.output / "events.jsonl").write_text("".join(json.dumps({"type": event}) + "\n" for event in types))

    def response(self, candidates):
        (self.output / "response.json").write_text(json.dumps({"candidates": candidates, "coverage": "Inspected rows.py", "limitations": ""}))

    def test_interrupted_zero_exit_is_not_completed(self):
        self.events("thread.started", "turn.started", "item.completed")
        self.assertEqual(calibration.completion_result(self.output, 0), {
            "status": "incomplete_output", "reason": "No successful turn.completed event was recorded"})
        self.response([])
        self.assertEqual(calibration.completion_result(self.output, 0)["status"], "incomplete_output")

    def test_completed_turn_requires_valid_response_including_empty_candidates(self):
        self.events("thread.started", "turn.started", "item.completed", "turn.completed")
        self.assertEqual(calibration.completion_result(self.output, 0)["status"], "incomplete_output")
        for candidates in [[], [{"responsibility": "Ownership", "hypothesis": "Reader may already be closed", "evidence": "rows.py:8", "nextCheck": "Trace caller"}]]:
            with self.subTest(candidates=candidates):
                self.response(candidates)
                self.assertEqual(calibration.completion_result(self.output, 0), {"status": "completed"})

    def test_malformed_response_is_not_completed(self):
        self.events("turn.started", "turn.completed")
        for response in ['{"candidates":', 'null', '{}',
                         '{"candidates": ["claim"], "coverage": "source", "limitations": ""}',
                         '{"candidates": [{"hypothesis": "claim"}], "coverage": "source", "limitations": ""}',
                         '{"candidates": [], "coverage": [], "limitations": ""}']:
            with self.subTest(response=response):
                (self.output / "response.json").write_text(response)
                self.assertEqual(calibration.completion_result(self.output, 0)["status"], "incomplete_output")

    def test_explicit_errors_and_nonzero_exit_fail_even_with_valid_response(self):
        self.response([])
        for event in ["turn.failed", "error"]:
            with self.subTest(event=event):
                self.events("turn.started", event, "turn.completed")
                self.assertEqual(calibration.completion_result(self.output, 0), {
                    "status": "failed", "reason": f"CLI recorded {event}"})
        self.events("turn.started", "turn.completed")
        self.assertEqual(calibration.completion_result(self.output, 1), {
            "status": "failed", "reason": "CLI exited with status 1"})

    def test_truncated_events_or_unfinished_later_turn_are_incomplete(self):
        self.response([])
        self.events("turn.started", "turn.completed", "turn.started")
        self.assertEqual(calibration.completion_result(self.output, 0)["status"], "incomplete_output")
        self.events("turn.started", "turn.completed")
        with (self.output / "events.jsonl").open("a") as events:
            events.write('{"type":')
        self.assertEqual(calibration.completion_result(self.output, 0)["status"], "incomplete_output")


class TestArguments(unittest.TestCase):
    def test_reasoning_must_be_explicit_before_any_provider_call(self):
        result = subprocess.run([sys.executable, calibration.__file__, "--repo", "unused", "--base", "base", "--head", "head",
                                 "--policy", "policy", "--context", "context", "--output", "output", "--model", "configured",
                                 "--codex", "/must-not-run"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn("required: --reasoning", result.stderr)


if __name__ == "__main__":
    unittest.main()
