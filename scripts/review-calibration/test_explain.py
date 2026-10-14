from datetime import date, timezone
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import explain

REVIEW = "backend/app/src/main/java/com/bytequay/app/review/ReviewTools.java"

WHOLE = "{:,.0f}".format


def by(name, **amounts):
    """The numbers of each model and effort: ten turns each, and an amount of one total."""
    return {setting: {"Turns": 10, name: amount} for setting, amount in amounts.items()}


class TestWhy(unittest.TestCase):
    def test_only_a_share_of_the_turns_that_moved_ten_points_or_more_is_named(self):
        self.assertIsNone(explain.shifted({"a": 50, "b": 50}, {"a": 59, "b": 41}))
        self.assertEqual(explain.shifted({"a": 50, "b": 50}, {"a": 60, "b": 40, "c": 0}),
                         "The turns ran with other models and efforts than in the days before: a 60% of the turns (50% before), b 40% of the turns (50% before).")

    def test_a_total_gets_a_reason_when_it_moved_a_quarter_and_is_known_on_both_sides(self):
        name = "Questions to you"
        reason = explain.total(name, by(name, a=20, b=4), by(name, a=4, b=4, c=12), {name: 24}, {name: 20}, WHOLE)
        self.assertIsNone(reason)  # 24 to 20 is less than a quarter, however much each model and effort moved
        self.assertEqual(explain.total(name, by(name, a=20, b=4), by(name, a=4, b=3, c=1), {name: 24}, {name: 8}, WHOLE),
                         "Questions to you 24 → 8: a -16 (10 → 10 turns).")  # b and c moved it by less than a tenth
        self.assertEqual(explain.total(name, by(name, a=20), {}, {name: 20}, {name: 0}, WHOLE), "Questions to you 20 → 0: a -20 (10 → 0 turns).")
        for before, now in ((5, 1), (None, 30), (30, None), (0, 30)):  # a small count, a side not known, and growth from nothing have no reason
            self.assertIsNone(explain.total(name, by(name, a=before), by(name, a=now), {name: before}, {name: now}, WHOLE))
        self.assertEqual(explain.total("Turns", by("Turns", a=10), by("Turns", a=10, b=10), {"Turns": 10}, {"Turns": 20}, WHOLE), "Turns 10 → 20: b +10.")


class TestCommits(unittest.TestCase):
    def test_commits_of_the_days_asked_for_come_with_the_files_they_touched_the_latest_first(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)

            def git(*words, when=None):
                dated = {"GIT_COMMITTER_DATE": when, "GIT_AUTHOR_DATE": when} if when else {}
                subprocess.run(["git", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "-C", folder, *words], check=True, capture_output=True,
                               env={**os.environ, **dated})

            git("init", "-q")
            git("config", "user.email", "lab@example.com")
            git("config", "user.name", "Lab")
            for path, subject, when in ((REVIEW, "Too early", "2026-01-07T23:59:59+00:00"), (REVIEW, "Shorter review rules", "2026-01-08T00:00:00+00:00"),
                                        ("frontend/src/App.tsx", "Not agent code", "2026-01-09T12:00:00+00:00"),
                                        ("backend/agent-core/src/main/java/Turns.java", "Retry rate limits", "2026-01-14T23:59:59+00:00"),
                                        (REVIEW, "Too late", "2026-01-15T00:00:00+00:00")):
                (root / path).parent.mkdir(parents=True, exist_ok=True)
                (root / path).write_text(subject)
                git("add", path)
                git("commit", "-q", "-m", subject, when=when)
            found = explain.commits(date(2026, 1, 8), date(2026, 1, 14), timezone.utc, root)
        self.assertEqual([(subject, paths) for _, subject, paths in found], [("Retry rate limits", ("backend/agent-core/src/main/java/Turns.java",)), ("Shorter review rules", (REVIEW,))])
        self.assertIsNone(explain.commits(date(2026, 1, 8), date(2026, 1, 14), timezone.utc, root))  # the repository is gone: git cannot say

    def test_every_part_of_the_agent_code_is_still_in_bytequay(self):
        if not (explain.ROOT / "backend").is_dir():
            self.skipTest("the application's source is not beside the lab")
        for name, prefixes in explain.AREAS.items():
            for prefix in prefixes:
                with self.subTest(part=name, path=prefix):
                    self.assertTrue((explain.ROOT / prefix).exists())
        self.assertEqual(explain.area("backend/agent-cli/src/main/java/com/bytequay/agent/cli/mcp/PermissionBroker.java"), "tools and approvals")
        self.assertIsNone(explain.area("backend/agent-cli/src/test/java/com/bytequay/agent/cli/mcp/TestPermissionBroker.java"))  # a test is not agent code


if __name__ == "__main__":
    unittest.main()
