import json
from pathlib import Path
import unittest

import pandas

import health
import resources
import saved

NOW = 1_800_000_000.0


def logs(groups=(), timings=(), **coverage):
    return {"savedAt": saved.stamp(NOW), "coverage": {"files": 1, "unreadable": 0, "truncated": 0, "omitted": 0, "parsed": 50,
                                                    "recent": 50, "first": NOW - 600, "last": NOW, **coverage},
            "errors": sum(one["count"] for one in groups if one["level"] == "ERROR"),
            "warnings": sum(one["count"] for one in groups if one["level"] == "WARN"), "groups": list(groups), "timings": list(timings)}


def group(category, count, level="WARN", component="Service"):
    return {"category": category, "count": count, "level": level, "component": component, "first": NOW - 600, "last": NOW}


def samples(seconds=720):
    parts, machine = [], []
    for at in range(int(NOW - seconds), int(NOW) + 1, 10):
        for part in ("app", "window", "gpu", "helpers", "backend", "agents", "commands", "orphaned", "monitor"):
            active = part in ("app", "window", "gpu", "helpers", "backend", "monitor")
            parts.append({"at": at, "part": part, "processes": int(active), "memory": (500 if part == "backend" else 100) * resources.MB if active else 0,
                          "cpu": .05 if active else 0, "files": 10 if active else 0})
        machine.append({"at": at, "turns": 0, "tasks": 2, "queued": 0, "app_session": "101:1000", "backend_session": "102:1001"})
    return {"parts": pandas.DataFrame(parts), "machine": pandas.DataFrame(machine)}


def grow(made, amount="memory", part="backend", delta=400 * resources.MB):
    rows = made["parts"]
    mask = (rows["part"] == part) & (rows["at"] >= NOW - 120)
    rows.loc[mask, amount] += delta
    return made


class TestLogs(unittest.TestCase):
    def test_known_errors_and_repeated_warnings_give_fixed_actionable_briefs(self):
        made = health.overall(logs([group("heap", 1, "ERROR"), group("cleanup", 2), group("cleanup", 1, component="Supervisor"), group("timeout", 2)]), None, NOW)
        self.assertEqual(made["status"], health.ATTENTION)
        self.assertEqual([(one["id"], one["severity"]) for one in made["findings"]], [("logs:heap", "high"), ("logs:cleanup", "medium")])
        self.assertIn("3 warnings", made["findings"][1]["seen"])
        for one in made["findings"]:
            self.assertEqual(set(one), {"id", "title", "severity", "seen", "do", "where", "watch"})
            self.assertTrue(all(one.values()))

    def test_unknown_recurring_failures_never_copy_source_messages(self):
        recurrent = group("other", 3, "ERROR", "Worker") | {"message": "secret password /Users/private"}
        made = health.overall(logs([recurrent, group("other", 1, "ERROR", "Once")]), None, NOW)
        self.assertEqual(len(made["findings"]), 1)
        self.assertEqual(made["findings"][0]["id"], "logs:other:Worker:ERROR")
        self.assertEqual(made["findings"][0]["title"], "Investigate recurring Worker error entries")
        self.assertNotIn("secret", json.dumps(made))
        self.assertNotIn("/Users", json.dumps(made))

    def test_slow_local_phases_need_enough_pairs_and_never_blame_provider_wait_on_the_app(self):
        timing = lambda phase, count, median: {"phase": phase, "count": count, "median": median, "p95": median + 1, "max": median + 2}
        made = health.overall(logs(timings=[timing("turn.persist", 3, 1.5), timing("task.worktree", 2, 10), timing("codex.initialize", 10, .9),
                                          timing("codex.first_output", 50, 60)]), samples(), NOW)
        self.assertEqual([one["id"] for one in made["findings"]], ["timing:turn.persist"])
        self.assertIn("1.50 seconds", made["findings"][0]["seen"])

    def test_missing_stale_and_partial_logs_do_not_imply_health(self):
        for table in (None, logs(files=0, recent=0), logs(unreadable=1), logs(truncated=1), logs(omitted=1), logs() | {"savedAt": saved.stamp(NOW - health.LOG_STALE - 1)},
                      logs() | {"activityProblem": "private failure details must not be copied"}):
            with self.subTest(table=table):
                made = health.overall(table, samples(), NOW)
                self.assertEqual(made["status"], health.UNKNOWN)
                self.assertNotIn("private failure", json.dumps(made))

    def test_stale_findings_remain_historical_and_request_a_refresh(self):
        made = health.overall(logs([group("heap", 1, "ERROR"), group("other", 3, "ERROR", "Worker")]), None, NOW + 2 * 86400)
        self.assertEqual(made["status"], health.ATTENTION)
        self.assertIn("saved analysis is stale", made["components"][0]["evidence"])
        self.assertEqual(len(made["findings"]), 2)
        for one in made["findings"]:
            self.assertIn("saved analysis's 24-hour window", one["seen"])
            self.assertNotIn("last 24 hours", one["seen"])
            self.assertTrue(one["do"].startswith("Refresh Lab data to check whether this historical signal is still present."))

    def test_findings_are_bounded_and_prioritize_memory_exhaustion(self):
        table = logs([group("other", 3, "ERROR", f"Worker{number}") for number in range(20)] + [group("heap", 1, "ERROR")])
        made = health.overall(table, samples(), NOW)
        self.assertEqual(len(made["findings"]), health.MAX_FINDINGS)
        self.assertEqual(made["findings"][0]["id"], "logs:heap")


class TestResources(unittest.TestCase):
    def test_complete_continuous_coverage_can_report_only_no_issue_observed(self):
        made = health.overall(logs(), samples(), NOW)
        self.assertEqual(made["status"], health.CLEAR)
        self.assertEqual(made["findings"], [])
        self.assertTrue(all(one["status"] == health.CLEAR for one in made["components"]))

    def test_missing_stale_partial_and_short_resource_coverage_stays_unknown(self):
        stale = samples()
        stale["parts"]["at"] -= resources.STALE + 1
        stale["machine"]["at"] -= resources.STALE + 1
        missing_part = samples()
        missing_part["parts"] = missing_part["parts"][missing_part["parts"]["part"] != "gpu"]
        absent_app = samples()
        absent_app["parts"].loc[absent_app["parts"]["part"] == "app", "processes"] = 0
        missing_metric = samples()
        missing_metric["parts"].loc[missing_metric["parts"]["part"] == "window", "memory"] = None
        for data in (None, stale, missing_part, absent_app, missing_metric, samples(60)):
            with self.subTest(data=data is None):
                self.assertEqual(health.overall(logs(), data, NOW)["status"], health.UNKNOWN)

    def test_detached_descendants_need_continuous_observations_over_thirty_seconds(self):
        data = samples()
        rows = data["parts"]
        rows.loc[(rows["part"] == "orphaned") & (rows["at"] >= NOW - 30), "processes"] = 2
        found = health.overall(logs(), data, NOW)["findings"]
        self.assertEqual([one["id"] for one in found], ["resources:detached"])
        self.assertIn("4 samples over 30 seconds", found[0]["seen"])
        self.assertIn("does not prove a leak", found[0]["do"])
        rows.loc[rows["at"] == NOW - 20, "processes"] = 0
        self.assertEqual(health.overall(logs(), data, NOW)["findings"], [])

    def test_cpu_without_turns_is_an_investigation_and_active_turns_do_not_trigger_it(self):
        data = samples()
        data["parts"].loc[data["parts"]["part"] == "backend", "cpu"] = .75
        found = health.overall(logs(), data, NOW)["findings"]
        self.assertEqual([one["id"] for one in found], ["resources:backend:cpu"])
        self.assertIn("does not mean the app is idle", found[0]["do"])
        data["machine"]["turns"] = 1
        self.assertEqual(health.overall(logs(), data, NOW)["findings"], [])

    def test_memory_and_files_growth_uses_endpoint_medians_and_same_workload(self):
        data = grow(grow(samples()), "files", delta=150)
        found = health.overall(logs(), data, NOW)["findings"]
        self.assertEqual([one["id"] for one in found], ["resources:backend:files", "resources:backend:memory"])
        self.assertIn("500 to 900 MB", found[1]["seen"])
        self.assertIn("10 to 160 open file descriptors", found[0]["seen"])
        self.assertIn("not proof of a leak", found[1]["do"])
        data["machine"].loc[data["machine"]["at"] >= NOW - 300, "tasks"] = 3
        self.assertEqual(health.overall(logs(), data, NOW)["findings"], [])

    def test_growth_requires_absolute_and_relative_memory_thresholds(self):
        small = grow(samples(), delta=200 * resources.MB)
        big = samples()
        big["parts"].loc[big["parts"]["part"] == "backend", "memory"] = 4 * resources.GB
        grow(big, delta=400 * resources.MB)
        for data in (small, big):
            self.assertEqual(health.overall(logs(), data, NOW)["findings"], [])

    def test_restarts_missing_workload_or_sample_gaps_never_become_growth_findings(self):
        restarted, unknown, gap = grow(samples()), grow(samples()), grow(samples())
        restarted["machine"].loc[restarted["machine"]["at"] >= NOW - 300, "backend_session"] = "202:2000"
        unknown["machine"]["backend_session"] = None
        gap["parts"] = gap["parts"][~gap["parts"]["at"].between(NOW - 360, NOW - 300)]
        gap["machine"] = gap["machine"][~gap["machine"]["at"].between(NOW - 360, NOW - 300)]
        missing_work = grow(samples())
        missing_work["machine"]["turns"] = None
        for data in (restarted, unknown, gap, missing_work):
            made = health.overall(logs(), data, NOW)
            self.assertEqual(made["findings"], [])
            self.assertEqual(made["status"], health.UNKNOWN)

    def test_sources_are_not_changed_and_suggested_source_paths_exist(self):
        data = grow(samples())
        before = {name: frame.copy(deep=True) for name, frame in data.items()}
        made = health.overall(logs(), data, NOW)
        for name in data:
            pandas.testing.assert_frame_equal(data[name], before[name])
        root = Path(__file__).resolve().parents[2]
        for one in made["findings"]:
            self.assertTrue((root / one["where"]).exists())
        for _, _, path in health.TARGETS.values():
            self.assertTrue((root / path).exists())
        self.assertTrue((root / health.PROCESS).exists())


if __name__ == "__main__":
    unittest.main()
