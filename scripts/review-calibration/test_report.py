from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import report

WHEN = datetime(2026, 1, 2, 3, 4, 5, tzinfo=timezone.utc)


def matches_example(test, name, made):
    """The example in the repository shows the saved shape, so it must be exactly what the lab writes."""
    example = Path(__file__).with_name("fixture") / name
    if os.environ.get("UPDATE_SAVED_EXAMPLES"):
        example.write_text(json.dumps(made, indent=2) + "\n")
    test.assertEqual(json.loads(example.read_text()), made, "Run the tests once with UPDATE_SAVED_EXAMPLES=1 if the saved shape changed on purpose")


class TestReport(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)

    def run_directory(self, setting, name, status="completed", policy="a" * 64, seconds=90, turns=((1000, 400, 50),), cut_off=False, verdicts=None, errors=0, leads=0):
        run = self.root / setting / name
        run.mkdir(parents=True)
        (run / "runtime.json").write_text(json.dumps({"status": status, "policySha256": policy, "elapsedSeconds": seconds,
                                                      "command": ["codex", "exec", "--model", "reviewer-model", "--json"]}))
        events = [{"type": "turn.started"}, {"type": "item.completed", "item": {"type": "agent_message"}}]
        events += [{"type": "turn.completed", "usage": {"input_tokens": read, "cached_input_tokens": cached, "cache_write_input_tokens": 0,
                                                        "output_tokens": written, "reasoning_output_tokens": 7}} for read, cached, written in turns]
        (run / "events.jsonl").write_text("".join(json.dumps(event) + "\n" for event in events) + ('{"type": "turn.comp' if cut_off else ""))
        if verdicts is not None:
            given = [{"commentId": number, "verdict": verdict, "leadIds": matched} for number, (verdict, matched) in enumerate(verdicts)]
            (run / "judgement.json").write_text(json.dumps({"case": "o/n#1", "leads": leads, "verdicts": given, "usage": {"calls": 4, "prompt_tokens": 800, "completion_tokens": 60},
                                                            "errors": [{"commentId": 99, "reason": "empty"}] * errors}))
        return run

    def test_scored_run_counts_verdicts_unmatched_leads_and_both_token_bills(self):
        run = self.run_directory("today", "pr1_rep1", turns=((1000, 400, 50), (200, 100, 10)), cut_off=True, errors=1, leads=5,
                                 verdicts=[("FOUND", ["c1", "c2"]), ("PARTLY", ["c2"]), ("MISSED", []), ("MISSED", [])])
        made = report.row(run)
        self.assertEqual({key: made[key] for key in ("setting", "run", "status", "judged", "case", "rules")},
                         {"setting": "today", "run": "pr1_rep1", "status": "completed", "judged": True, "case": "o/n#1", "rules": "aaaaaaaa reviewer-model"})
        self.assertEqual({count: made[count] for count in report.COUNTS}, {"comments": 5, "FOUND": 1, "PARTLY": 1, "MISSED": 2, "errors": 1, "leads": 5, "extra": 3})
        self.assertEqual((made["input_tokens"], made["cached_input_tokens"], made["output_tokens"], made["minutes"]), (1200, 500, 60, 1.5))
        self.assertEqual((made["prompt_tokens"], made["completion_tokens"]), (800, 60))

    def test_failed_and_unjudged_runs_are_listed_apart_and_never_counted_as_zero(self):
        self.run_directory("today", "pr1_rep1", leads=2, verdicts=[("FOUND", ["c1"]), ("MISSED", [])])
        self.run_directory("today", "pr1_rep2", status="timed_out", verdicts=[("MISSED", [])])
        self.run_directory("today", "pr2_rep1")
        rows = report.collect(self.root)
        self.assertEqual([(made["run"], made["judged"]) for made in rows], [("pr1_rep1", True), ("pr1_rep2", False), ("pr2_rep1", False)])
        table = report.render(rows)
        self.assertIn("| all scored runs | 1 of 3 runs | 2 | 1 | 0 | 1 | 0 | 2 | 1 | 1,000 (400) / 50 | 1.5 |", table)
        self.assertIn("Not scored, and not counted as zero: pr1_rep2 (timed_out), pr2_rep1 (completed, not judged)", table)

    def test_settings_are_totalled_apart_and_a_mixed_setting_is_called_out(self):
        self.run_directory("before", "pr1_rep1", leads=1, verdicts=[("MISSED", []), ("MISSED", [])])
        self.run_directory("today", "pr1_rep1", leads=3, verdicts=[("FOUND", ["c1"]), ("PARTLY", ["c2"])])
        self.run_directory("today", "pr1_rep2", policy="b" * 64, leads=3, verdicts=[("FOUND", ["c1"]), ("FOUND", ["c3"])])
        table = report.render(report.collect(self.root))
        self.assertIn("## before (aaaaaaaa reviewer-model)", table)
        self.assertIn("| all scored runs | 1 of 1 runs | 2 | 0 | 0 | 2 | 0 | 1 | 1 |", table)
        self.assertIn("| all scored runs | 2 of 2 runs | 4 | 3 | 1 | 0 | 0 | 6 | 2 | 2,000 (800) / 100 | 3.0 |", table)
        self.assertIn("Mixed rules files or models in this setting, so its runs are not one comparison: aaaaaaaa reviewer-model, bbbbbbbb reviewer-model", table)
        self.assertIn("Judge tokens: 1,600 / 120 (in / out)", table)
        self.assertLess(table.index("## before"), table.index("## today"))

    def two_settings(self):
        self.run_directory("before", "pr1_rep1", leads=2, verdicts=[("FOUND", ["c1"]), ("MISSED", [])])
        self.run_directory("before", "pr1_rep2", status="timed_out")
        self.run_directory("today", "pr1_rep1", leads=3, errors=1, verdicts=[("FOUND", ["c1"]), ("PARTLY", ["c2"])])
        self.run_directory("today", "pr1_rep2", policy="b" * 64, leads=3, verdicts=[("FOUND", ["c1"]), ("FOUND", ["c3"])])
        self.run_directory("today", "pr2_rep1")

    def test_summary_holds_what_the_tables_show_and_is_the_example_the_app_reads(self):
        self.two_settings()
        made = report.summary(report.collect(self.root), WHEN)
        matches_example(self, "saved-review-scores.json", made)
        self.assertEqual((made["version"], made["savedAt"], [setting["name"] for setting in made["settings"]]), (1, "2026-01-02T03:04:05Z", ["before", "today"]))
        before, today = made["settings"]
        self.assertEqual((before["rules"], before["notScored"]), (["aaaaaaaa reviewer-model"], [{"run": "pr1_rep2", "status": "timed_out"}]))
        self.assertEqual(before["runs"], [{"run": "pr1_rep1", "case": "o/n#1", "comments": 2, "found": 1, "partly": 0, "missed": 1, "errors": 0, "leads": 2, "extra": 1,
                                           "inputTokens": 1000, "cachedInputTokens": 400, "outputTokens": 50, "seconds": 90, "judgeInputTokens": 800, "judgeOutputTokens": 60}])
        self.assertEqual((today["rules"], today["notScored"], [(run["found"], run["partly"], run["errors"], run["comments"]) for run in today["runs"]]),
                         (["aaaaaaaa reviewer-model", "bbbbbbbb reviewer-model"], [{"run": "pr2_rep1", "status": "not judged"}], [(1, 1, 1, 3), (2, 0, 0, 2)]))

    def test_save_writes_one_file_for_the_app_and_nothing_when_there_are_no_runs(self):
        self.two_settings()
        folder = self.root.parent / (self.root.name + " saved")
        self.addCleanup(lambda: [path.unlink() for path in folder.glob("*")] and folder.rmdir())
        result = subprocess.run([sys.executable, report.__file__, str(self.root), "--save", str(folder)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("## before (aaaaaaaa reviewer-model)", result.stdout)
        self.assertIn(f"Saved to {folder / 'review-scores.json'}", result.stdout)
        self.assertEqual([path.name for path in folder.iterdir()], ["review-scores.json"])
        self.assertEqual([setting["name"] for setting in json.loads((folder / "review-scores.json").read_text())["settings"]], ["before", "today"])
        with tempfile.TemporaryDirectory() as empty:
            nothing = subprocess.run([sys.executable, report.__file__, empty, "--save", str(folder / "other")], capture_output=True, text=True)
        self.assertEqual((nothing.returncode, "Saved to" in nothing.stdout, (folder / "other").exists()), (0, False, False))


if __name__ == "__main__":
    unittest.main()
