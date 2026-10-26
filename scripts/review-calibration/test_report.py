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

    def run_directory(self, setting, name, status="completed", policy="a" * 64, seconds=90, used=(1000, 400, 50), cost=None, effort="high", verdicts=None, errors=0, leads=0):
        """A run as run.py leaves it. `used` is tokens in, the cached ones among them, and tokens out; None is a run that never ended."""
        run = self.root / setting / name
        run.mkdir(parents=True)
        spent = {} if used is None else {"usage": dict(zip(report.REVIEWER, used), cost=cost)}
        (run / "runtime.json").write_text(json.dumps({"status": status, "policySha256": policy, "elapsedSeconds": seconds, "provider": "codex", "model": "reviewer-model",
                                                      "effort": effort, **spent}))
        if verdicts is not None:
            given = [{"commentId": number, "verdict": verdict, "leadIds": matched} for number, (verdict, matched) in enumerate(verdicts)]
            (run / "judgement.json").write_text(json.dumps({"case": "o/n#1", "leads": leads, "verdicts": given, "usage": {"calls": 4, "prompt_tokens": 800, "completion_tokens": 60},
                                                            "errors": [{"commentId": 99, "reason": "empty"}] * errors}))
        return run

    def test_scored_run_counts_verdicts_unmatched_leads_and_both_token_bills(self):
        run = self.run_directory("today", "pr1_rep1", used=(1200, 500, 60), cost=0.25, errors=1, leads=5,
                                 verdicts=[("FOUND", ["c1", "c2"]), ("PARTLY", ["c2"]), ("MISSED", []), ("MISSED", [])])
        made = report.row(run)
        self.assertEqual({key: made[key] for key in ("setting", "run", "status", "judged", "case", "rules")},
                         {"setting": "today", "run": "pr1_rep1", "status": "completed", "judged": True, "case": "o/n#1", "rules": "aaaaaaaa reviewer-model high"})
        self.assertEqual({count: made[count] for count in report.COUNTS}, {"comments": 5, "FOUND": 1, "PARTLY": 1, "MISSED": 2, "errors": 1, "leads": 5, "extra": 3})
        self.assertEqual((made["inputTokens"], made["cachedInputTokens"], made["outputTokens"], made["cost"], made["minutes"]), (1200, 500, 60, 0.25, 1.5))
        self.assertEqual((made["prompt_tokens"], made["completion_tokens"]), (800, 60))
        never_ended = report.row(self.run_directory("today", "pr1_rep2", status="started", used=None, effort=None))
        self.assertEqual((never_ended["rules"], never_ended["inputTokens"], never_ended["cost"]), ("aaaaaaaa reviewer-model", 0, None))

    def test_failed_and_unjudged_runs_are_listed_apart_and_never_counted_as_zero(self):
        self.run_directory("today", "pr1_rep1", leads=2, verdicts=[("FOUND", ["c1"]), ("MISSED", [])])
        self.run_directory("today", "pr1_rep2", status="timed_out", verdicts=[("MISSED", [])])
        self.run_directory("today", "pr2_rep1")
        rows = report.collect(self.root)
        self.assertEqual([(made["run"], made["judged"]) for made in rows], [("pr1_rep1", True), ("pr1_rep2", False), ("pr2_rep1", False)])
        table = report.render(rows)
        self.assertIn("| all scored runs | 1 of 3 runs | 2 | 1 | 0 | 1 | 0 | 2 | 1 | 1,000 (400) / 50 | 1.5 | - |", table)  # no run of these records a cost
        self.assertIn("Not scored, and not counted as zero: pr1_rep2 (timed_out), pr2_rep1 (completed, not judged)", table)

    def test_settings_are_totalled_apart_and_a_mixed_setting_is_called_out(self):
        self.run_directory("before", "pr1_rep1", leads=1, verdicts=[("MISSED", []), ("MISSED", [])])
        self.run_directory("today", "pr1_rep1", leads=3, cost=1.5, verdicts=[("FOUND", ["c1"]), ("PARTLY", ["c2"])])
        self.run_directory("today", "pr1_rep2", policy="b" * 64, leads=3, verdicts=[("FOUND", ["c1"]), ("FOUND", ["c3"])])
        table = report.render(report.collect(self.root))
        self.assertIn("## before (aaaaaaaa reviewer-model high)", table)
        self.assertIn("| all scored runs | 1 of 1 runs | 2 | 0 | 0 | 2 | 0 | 1 | 1 |", table)
        self.assertIn("| all scored runs | 2 of 2 runs | 4 | 3 | 1 | 0 | 0 | 6 | 2 | 2,000 (800) / 100 | 3.0 | $1.50 |", table)  # the cost of the one run that records it
        self.assertIn("Mixed rules files, models or efforts in this setting, so its runs are not one comparison: aaaaaaaa reviewer-model high, bbbbbbbb reviewer-model high", table)
        self.assertIn("Judge tokens: 1,600 / 120 (in / out)", table)
        self.assertLess(table.index("## before"), table.index("## today"))

    def test_unseen_cases_show_only_each_settings_totals(self):
        self.two_settings()
        (self.root / "plan.json").write_text(json.dumps({"version": 1, "unseen": True}))
        printed = subprocess.run([sys.executable, report.__file__, str(self.root)], capture_output=True, text=True)
        self.assertEqual(printed.returncode, 0, printed.stderr)
        self.assertTrue(printed.stdout.startswith("Unseen cases: only each setting's totals are shown, so the cases stay unseen.\n"))
        self.assertIn("| all scored runs | 2 of 3 runs | 5 | 3 | 1 | 0 | 1 | 6 | 2 |", printed.stdout)
        self.assertNotIn("| pr1_rep1 |", printed.stdout)  # no row of one run, and so of one case
        self.assertIn("| pr1_rep1 | o/n#1 |", report.render(report.collect(self.root)))  # other experiments keep them

    def two_settings(self):
        self.run_directory("before", "pr1_rep1", leads=2, verdicts=[("FOUND", ["c1"]), ("MISSED", [])])
        self.run_directory("before", "pr1_rep2", status="timed_out")
        self.run_directory("today", "pr1_rep1", leads=3, errors=1, cost=1.5, verdicts=[("FOUND", ["c1"]), ("PARTLY", ["c2"])])
        self.run_directory("today", "pr1_rep2", policy="b" * 64, leads=3, verdicts=[("FOUND", ["c1"]), ("FOUND", ["c3"])])
        self.run_directory("today", "pr2_rep1")

    def test_summary_holds_what_the_tables_show_and_is_the_example_the_app_reads(self):
        self.two_settings()
        made = report.summary(report.collect(self.root), WHEN)
        matches_example(self, "saved-scores.json", made)
        self.assertEqual((made["version"], made["savedAt"], [setting["name"] for setting in made["settings"]]), (1, "2026-01-02T03:04:05Z", ["before", "today"]))
        before, today = made["settings"]
        self.assertEqual((before["rules"], before["notScored"]), (["aaaaaaaa reviewer-model high"], [{"run": "pr1_rep2", "status": "timed_out"}]))
        self.assertEqual(before["runs"], [{"run": "pr1_rep1", "case": "o/n#1", "comments": 2, "found": 1, "partly": 0, "missed": 1, "errors": 0, "leads": 2, "extra": 1,
                                           "verdicts": {"0": "found", "1": "missed"}, "inputTokens": 1000, "cachedInputTokens": 400, "outputTokens": 50, "cost": None,
                                           "seconds": 90, "judgeInputTokens": 800, "judgeOutputTokens": 60}])
        # A comment the judge could not answer has no verdict, and is not among them.
        self.assertEqual([run["verdicts"] for run in today["runs"]], [{"0": "found", "1": "partly"}, {"0": "found", "1": "found"}])
        self.assertEqual((today["rules"], today["notScored"], [(run["found"], run["partly"], run["errors"], run["comments"]) for run in today["runs"]]),
                         (["aaaaaaaa reviewer-model high", "bbbbbbbb reviewer-model high"], [{"run": "pr2_rep1", "status": "not judged"}], [(1, 1, 1, 3), (2, 0, 0, 2)]))

    def test_save_keeps_the_scores_inside_the_experiment_and_writes_nothing_when_there_are_no_runs(self):
        self.two_settings()
        printed = subprocess.run([sys.executable, report.__file__, str(self.root)], capture_output=True, text=True)
        self.assertEqual((printed.returncode, (self.root / "scores.json").exists()), (0, False))  # printing alone saves nothing
        result = subprocess.run([sys.executable, report.__file__, str(self.root), "--save"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("## before (aaaaaaaa reviewer-model high)", result.stdout)
        self.assertIn(f"Saved to {self.root / 'scores.json'}", result.stdout)
        self.assertEqual(sorted(path.name for path in self.root.iterdir()), ["before", "scores.json", "today"])
        self.assertEqual([setting["name"] for setting in json.loads((self.root / "scores.json").read_text())["settings"]], ["before", "today"])
        with tempfile.TemporaryDirectory() as empty:
            nothing = subprocess.run([sys.executable, report.__file__, empty, "--save"], capture_output=True, text=True)
            self.assertEqual((nothing.returncode, "Saved to" in nothing.stdout, list(Path(empty).iterdir())), (0, False, []))


if __name__ == "__main__":
    unittest.main()
