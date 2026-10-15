import ast
import logging
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest import mock

import pandas
from streamlit import config
from streamlit.testing.v1 import AppTest

import charts
import dashboard
import monitor
import resources

PAGE = Path(__file__).with_name("pages") / "Resources.py"
# As in test_dashboard: outside a running page Streamlit warns on every start that there is no page.
logging.getLogger("streamlit.runtime.scriptrunner_utils.script_run_context").addFilter(lambda record: "missing ScriptRunContext" not in record.getMessage())
MACHINE = {"memory_used": 40 * resources.GB, "memory_total": 64 * resources.GB, "cpu": 2.5, "turns": 1, "tasks": 6, "queued": 2}


def numbers(**changed):
    """One sample of every part: the app's helpers small, the backend large, one agent at work."""
    made = {part: {"processes": 1, "memory": 100 * resources.MB, "cpu": 0.1, "threads": 10, "files": 5} for part in monitor.PARTS}
    made["backend"] |= {"memory": 1000 * resources.MB, "cpu": 0.5, "threads": 100}
    made["commands"] |= {"processes": 0, "memory": 0, "cpu": 0, "threads": 0, "files": 0}
    for part, amounts in changed.items():
        made[part] |= amounts
    return made


class Kept(unittest.TestCase):
    """A lab folder whose monitor kept a few samples."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.folder = Path(directory.name).resolve() / "lab"

    def keep(self, *samples):
        connection = monitor.store(self.folder)
        for moment, made, machine in samples:
            monitor.keep(connection, moment, made, machine)
        connection.close()


class TestFrames(Kept):
    def test_parts_add_up_to_groups_and_every_part_is_listed_with_now_typical_and_peak(self):
        now = 1_800_001_800.0  # half past an hour, so the three samples share one
        self.keep((now - 20, numbers(), MACHINE), (now - 10, numbers(backend={"memory": 1400 * resources.MB, "cpu": 1.5}), MACHINE | {"turns": 0}),
                  (now, numbers(window={"processes": 2, "memory": 300 * resources.MB}), MACHINE | {"turns": None}))
        connection = monitor.store(self.folder)  # and one hour that was folded: its average hides its peak
        connection.execute("INSERT INTO parts VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", (now - 50, 3600, "backend", 1, 1000 * resources.MB, 2000 * resources.MB, 0.5, 0.9, 100, 5))
        connection.commit()
        connection.close()
        made = resources.read(self.folder, now - 60)
        groups = resources.grouped(made["parts"])
        newest = groups[groups["at"] == now].set_index("group")
        self.assertEqual(newest["memory"].div(resources.MB).to_dict(), {"Agents": 100, "Electron app": 600, "Java backend": 1000})  # the monitor is not the app
        self.assertEqual(resources.now(made["parts"], made["machine"]) | {"memory": None},
                         {"memory": None, "cpu": 1.0, "threads": 150, "processes": 7, "turns": None, "tasks": 6, "queued": 2, "used": 40 * resources.GB, "total": 64 * resources.GB})
        table = resources.parts_table(made["parts"]).set_index("part")
        self.assertEqual(list(table.index), ["app, main process", "app windows", "app GPU", "app helpers", "Java backend", "agent command lines", "this monitor"])  # no command ran
        self.assertEqual(table.loc["Java backend", ["memory MB", "typical MB", "peak MB", "CPU cores", "typical CPU", "peak CPU"]].tolist(), [1000, 1000, 2000, 0.5, 0.5, 1.5])
        self.assertEqual(table.loc["app windows", ["processes", "memory MB"]].tolist(), [2, 300])
        self.assertEqual(resources.bucketed(groups, 3600, ["group"]).groupby("group")["memory"].count().tolist(), [1, 1, 1])  # one bucket of each

    def test_idle_memory_is_taken_only_from_samples_when_no_turn_ran(self):
        start = pandas.Timestamp("2026-10-12 12:00").timestamp()
        self.keep((start, numbers(backend={"memory": 900 * resources.MB}), MACHINE | {"turns": 0}),
                  (start + 60, numbers(backend={"memory": 5000 * resources.MB}), MACHINE),  # a turn ran: left out
                  (start + 86400, numbers(backend={"memory": 1100 * resources.MB}), MACHINE | {"turns": 0}))
        made = resources.read(self.folder, 0)
        days = resources.idle_days(made["parts"], made["machine"])
        self.assertEqual(days["Java backend"].tolist(), [900, 1100])
        self.assertEqual(list(days.columns), ["day", "Electron app", "Java backend", "Agents"])
        self.assertIsNone(resources.idle_days(made["parts"], made["machine"].assign(turns=1)))

    def test_disk_lists_the_biggest_places_with_their_growth_and_adds_up_the_rest(self):
        space = pandas.DataFrame([(1.0, monitor.OPEN, "workspaces", 30 * resources.GB), (1.0, monitor.REMOVED, "workspaces", 20 * resources.GB),
                                  (1.0, monitor.REPOSITORIES, "repos", 9 * resources.GB), (1.0, monitor.APP, "logs", 1 * resources.GB),
                                  (2.0, monitor.OPEN, "workspaces", 60 * resources.GB), (2.0, monitor.REPOSITORIES, "repos", 9 * resources.GB),
                                  (2.0, monitor.APP, "logs", 2 * resources.GB), (2.0, monitor.APP, "new", 1 * resources.GB)], columns=["at", "owner", "place", "bytes"])
        unknown = -1  # a growth that cannot be known: the place was not there at the first count
        self.assertEqual(resources.disk_rows(space).fillna(unknown).to_dict("records"), [{"place": "workspaces", "GB": 60, "grew GB": 10}, {"place": "repos", "GB": 9, "grew GB": 0},
                                                                                          {"place": "logs", "GB": 2, "grew GB": 1}, {"place": "new", "GB": 1, "grew GB": unknown}])
        self.assertEqual(resources.disk_rows(space, top=2).fillna(unknown).to_dict("records")[-1], {"place": "2 more", "GB": 3, "grew GB": unknown})  # one was not there before
        self.assertIsNone(resources.disk_rows(space.iloc[:0]))

    def test_owners_of_the_newest_count_come_in_the_monitors_order_with_what_they_are(self):
        space = pandas.DataFrame([(1.0, monitor.OPEN, "workspaces", 9 * resources.GB, 10), (2.0, monitor.APP, "Cache", 1 * resources.GB, 7),
                                  (2.0, monitor.RETIRED, "agent-runtime", 3 * resources.GB, 2), (2.0, monitor.RETIRED, "new-flow-worktrees", 1 * resources.GB, 5),
                                  (2.0, monitor.OPEN, "workspaces", 4 * resources.GB, 20)], columns=["at", "owner", "place", "bytes", "files"])
        rows = resources.owner_rows(space)
        self.assertEqual(rows[["owner", "GB", "files"]].to_dict("records"), [{"owner": monitor.OPEN, "GB": 4, "files": 20}, {"owner": monitor.RETIRED, "GB": 4, "files": 7},
                                                                             {"owner": monitor.APP, "GB": 1, "files": 7}])
        self.assertEqual(set(resources.NOTES), set(monitor.OWNERS))  # every owner says what it is
        self.assertIsNone(resources.owner_rows(space.iloc[:0]))
        # What could be freed wears the warning color; a place never does.
        colors = list(resources.disk_figure(rows, "light", by="owner").data[0].marker.color)
        self.assertEqual(colors, [charts.COLORS["light"]["model"], charts.COLORS["light"]["warning"], charts.COLORS["light"]["model"]])
        places = resources.disk_rows(space)
        self.assertEqual(set(resources.disk_figure(places, "light").data[0].marker.color), {charts.COLORS["light"]["model"]})

    def test_nothing_kept_reads_as_nothing(self):
        self.assertIsNone(resources.read(self.folder, 0))


class TestPage(Kept):
    def setUp(self):
        super().setUp()
        arguments = mock.patch.object(sys, "argv", ["dashboard.py", str(self.folder), "--runtime", str(self.folder / "runtime")])
        arguments.start()
        self.addCleanup(arguments.stop)
        config.set_option("server.address", dashboard.HOST)
        self.addCleanup(config.set_option, "server.address", None)

    def page(self):
        return AppTest.from_file(str(PAGE), default_timeout=30).run()

    def test_live_tiles_charts_and_every_part_are_drawn_from_fresh_samples(self):
        now = time.time()
        self.keep((now - 6, numbers(), MACHINE), (now - 1, numbers(), MACHINE))
        connection = monitor.store(self.folder)
        connection.executemany("INSERT INTO space VALUES (?, ?, ?, ?, ?)", [(now - 30, monitor.OPEN, "workspaces", 40 * resources.GB, 9), (now - 30, monitor.IDLE, "workspaces", 15.5 * resources.GB, 8),
                                                                            (now - 30, monitor.REMOVED, "workspaces", 12 * resources.GB, 7),
                                                                            (now - 30, monitor.RETIRED, "agent-runtime", 29 * resources.GB, 6),
                                                                            (now - 30, monitor.REPOSITORIES, "repos", 9 * resources.GB, 5)])
        connection.commit()
        connection.close()
        page = self.page()
        # A turn ran in every sample, so nothing is known of memory at rest.
        self.assertEqual((list(page.exception), [note.value for note in page.warning], [note.value for note in page.info]), ([], [], ["No sample of this stretch was taken while no turn ran."]))
        self.assertEqual([(tile.label, tile.value) for tile in page.metric][:8],  # 1,500 MB; the monitor's own 100 MB are not the app's
                         [("App memory", "1.46 GB"), ("CPU cores", "1.00"), ("Threads", "150"), ("Processes", "6"), ("Turns running", "1"), ("Open tasks", "6"),
                          ("Could be freed", "41.0 GB"), ("Idle open tasks", "15.5 GB")])
        self.assertEqual(page.metric[8].label, "Free on this disk")
        self.assertEqual([heading.value for heading in page.subheader], ["Over time", "Every part", "Memory while no turn ran", "Disk"])
        self.assertEqual(len(page.get("plotly_chart")), 8)  # memory and CPU live; memory, turns, CPU and threads over time; disk by owner and by place
        self.assertEqual(page.dataframe[-2].value["owner"].tolist(), [monitor.OPEN, monitor.IDLE, monitor.REMOVED, monitor.RETIRED, monitor.REPOSITORIES])
        self.assertTrue({"This Mac: 40.0 of 64 GB of memory in use.", "The monitor itself typically holds 100 MB and keeps 10.0% of one core busy."}
                        <= {caption.value for caption in page.caption})

    def test_stopped_monitor_says_so_and_still_shows_what_it_kept(self):
        self.keep((time.time() - 120, numbers(), MACHINE | {"turns": 0}))
        page = self.page()
        self.assertEqual(([note.value for note in page.warning], list(page.exception)),
                         (["The monitor is not running, so this is not live. Start the monitor with `uv run monitor.py --start`"], []))
        self.assertIn("Memory while no turn ran", [heading.value for heading in page.subheader])
        self.assertIn("The app's data is counted a few times a day. The first count starts with the monitor and takes a few minutes.", [note.value for note in page.info])

    def test_nothing_sampled_says_how_to_start_and_draws_nothing(self):
        page = self.page()
        self.assertEqual(([note.value for note in page.info][:2], len(page.get("plotly_chart")), list(page.exception)),
                         (["Nothing is sampled yet. Start the monitor with `uv run monitor.py --start`", "Nothing was sampled in this stretch."], 0, []))

    def test_page_started_another_way_shows_nothing(self):
        config.set_option("server.address", "0.0.0.0")
        page = self.page()
        self.assertEqual(([note.value for note in page.error], len(page.metric)), (["This page is only shown to this machine. Start it with `uv run dashboard.py`"], 0))

    def test_page_has_no_bare_expression_that_streamlit_would_print(self):
        tree = ast.parse(PAGE.read_text())
        bare = [node.lineno for node in ast.walk(tree) if isinstance(node, ast.Expr) and not isinstance(node.value, ast.Call)
                and not (isinstance(node.value, ast.Constant) and isinstance(node.value.value, str))]
        self.assertEqual(bare, [])


if __name__ == "__main__":
    unittest.main()
