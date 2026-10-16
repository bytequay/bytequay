import ast
from datetime import date
import logging
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest import mock

import pandas
from streamlit import config
from streamlit.testing.v1 import AppTest

import advisor
import batch
import charts
import cleanup
import dashboard
import digest
import monitor
import record
import resources
import saved
import suggest

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


def frame(page, column):
    """The first table on the page with a column of this name."""
    return next(table.value for table in page.dataframe if column in table.value)


class TestPage(Kept):
    def setUp(self):
        super().setUp()
        arguments = mock.patch.object(sys, "argv", ["dashboard.py", str(self.folder), "--runtime", str(self.folder / "runtime")])
        arguments.start()
        self.addCleanup(arguments.stop)
        config.set_option("server.address", dashboard.HOST)
        self.addCleanup(config.set_option, "server.address", None)
        # The app's data is a folder of the test's own, with the retired code index in it and no database, so a page never
        # plans for the real one.
        self.data = self.folder.parent / "data"
        baseline, copy = self.data / "agent-runtime" / "codegraph-baselines", self.data / "database-backup-9"
        (baseline / "r").mkdir(parents=True)
        (baseline / "r" / "codegraph.db").write_text("x")
        copy.mkdir()
        (copy / "bytequay-1.0.db").write_text("x")
        for path in (baseline, baseline / "r", copy, copy / "bytequay-1.0.db"):
            os.utime(path, (time.time() - 2 * 86400,) * 2)
        for name, value in (("APP_DATA", self.data), ("DATABASE", self.data / "bytequay-1.0.db")):
            patched = mock.patch.object(monitor, name, value)
            patched.start()
            self.addCleanup(patched.stop)
        self.key = mock.patch.object(batch, "has_key", return_value=False)  # a page test never asks the real Keychain
        self.key.start()
        self.addCleanup(self.key.stop)

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
        self.assertEqual([heading.value for heading in page.subheader], ["Risks", "Over time", "Every part", "Memory while no turn ran", "Disk", "Clean up"])
        self.assertEqual(len(page.get("plotly_chart")), 8)  # memory and CPU live; memory, turns, CPU and threads over time; disk by owner and by place
        self.assertEqual(frame(page, "what it is")["owner"].tolist(), [monitor.OPEN, monitor.IDLE, monitor.REMOVED, monitor.RETIRED, monitor.REPOSITORIES])
        # What a clean-up would delete, with what its owner held at the last count.
        self.assertEqual(frame(page, "folders or files").values.tolist(), [[monitor.COPIES, "top level", 1, 0.0], [monitor.RETIRED, "agent-runtime", 1, 29.0]])
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

    def test_without_a_key_the_agent_cannot_look_and_the_page_says_how_to_give_it_one(self):
        page = self.page()
        self.assertTrue(page.button(key="advice_look").disabled)
        self.assertIn("The agent needs a DeepSeek key. Store it once with `uv run batch.py --store-key`", [caption.value for caption in page.caption])

    def test_with_a_key_a_look_starts_on_the_button(self):
        with mock.patch.object(batch, "has_key", return_value=True), mock.patch.object(advisor, "start") as started:
            page = self.page()
            self.assertFalse(page.button(key="advice_look").disabled)
            page.button(key="advice_look").click().run()
        self.assertEqual(started.call_args_list, [mock.call(self.folder)])

    def test_a_running_look_shows_how_far_it_got(self):
        own = advisor.place(self.folder)
        own["folder"].mkdir(parents=True)
        held = batch.claim(own)
        self.addCleanup(held.close)
        saved.write(own["folder"], advisor.STATE, {"version": 1, "state": "running", "started": "2026-10-15T08:00:00Z", "ended": None, "model": "deepseek-v4-pro",
                                                   "steps": 3, "usage": None, "summary": None, "suggestions": [], "overview": None, "problem": None})
        with mock.patch.object(batch, "has_key", return_value=True):
            page = self.page()
        self.assertIn("The agent is looking: 3 model calls so far, 20 at most.", [note.value for note in page.info])
        self.assertTrue(page.button(key="advice_look").disabled)

    def found(self, **changed):
        suggestion = {"title": "Clean up what nothing uses", "risk": "high", "seen": "Retired features hold 29.7 GB.", "do": "Delete it.", "where": "The Clean up section",
                      "watch": "Could be freed", "action": "clean_up"}
        saved.write(advisor.place(self.folder)["folder"], advisor.STATE, {
            "version": 1, "state": "done", "started": "2026-10-15T08:00:00Z", "ended": "2026-10-15T08:02:00Z", "model": "deepseek-v4-pro", "steps": 4,
            "usage": {"inputTokens": 12000, "cachedInputTokens": 8000, "outputTokens": 900, "cost": None}, "summary": "The disk is 91% full; it costs $0 to clean.",
            "suggestions": [suggestion, suggestion | {"title": "Watch the backend", "risk": "low", "action": "none"}], "overview": "Samples from ...", "problem": None} | changed)

    def test_what_the_last_look_found_is_shown_and_its_clean_up_asks_to_be_confirmed(self):
        self.found()
        page = self.page()
        said = [text.value for text in page.markdown]
        self.assertIn("The disk is 91% full; it costs \\$0 to clean.", said)  # a dollar sign would start a formula
        self.assertEqual([text for text in said if text in ("Clean up what nothing uses", "Watch the backend")], ["Clean up what nothing uses", "Watch the backend"])
        self.assertIn("**Seen.** Retired features hold 29.7 GB.", said)
        self.assertTrue(any("High risk" in text for text in said) and any("Low risk" in text for text in said))
        self.assertTrue(next(caption.value for caption in page.caption if caption.value.startswith("Looked ")).endswith(
            "with deepseek-v4-pro: 4 model calls, 12,000 tokens in and 900 out."))
        self.assertEqual([button.key for button in page.button if button.key.startswith("advice_clean")], ["advice_clean_0"])  # only the one it carries out
        page.button(key="advice_clean_0").click().run()
        self.assertEqual([note.value for note in page.warning][-1:], ["Delete 1 folder or file? This cannot be undone."])

    def test_a_risk_says_what_the_looks_before_found_and_takes_the_marks_of_a_suggestion(self):
        own = advisor.place(self.folder)["folder"]
        self.found()
        shown = saved.read(own, advisor.STATE)
        saved.write(own / advisor.LOOKS, "2026-10-14T08-00-00Z.json", shown | {"started": "2026-10-14T08:00:00Z", "ended": "2026-10-14T08:02:00Z",
                                                                             "suggestions": [shown["suggestions"][0], shown["suggestions"][1] | {"title": "Close the idle tasks"}]})
        saved.write(own / advisor.LOOKS, "2026-10-15T08-00-00Z.json", shown)  # the look shown is kept too, as every look that finished
        page = self.page()
        said = lambda: [caption.value for caption in page.caption]
        self.assertTrue({"Earlier looks found it too: 1 of 1.", "New in this look.", "Gone since the look before: Close the idle tasks."} <= set(said()), said())
        page.button(key="risk_doing_1").click().run()
        self.assertEqual(record.marks(self.folder), {"Watch the backend": ("doing", date.today(), "")})  # the record the lab's own suggestions keep
        self.assertIn(f"Being worked on since {suggest.said(date.today())}.", said())
        page.text_input(key="risk_why_0").input("it runs by itself").run()
        page.button(key="risk_drop_0").click().run()
        self.assertNotIn("Clean up what nothing uses", [text.value for text in page.markdown])
        self.assertIn("1 risk you marked not wanted is left out. Bring one back under What became of them, on the Suggestions tab.", said())
        page.button(key="risk_noted_1").click().run()
        self.assertEqual(digest.changes(self.folder), [{"day": date.today(), "what": "Watch the backend"}])
        self.assertIn(f"You noted this change on {suggest.said(date.today())}. No look since then yet.", said())

    def test_a_look_that_failed_says_why(self):
        self.found(state="failed", problem="RuntimeError: the API is down", suggestions=[], summary=None)
        self.assertIn("The last look failed: RuntimeError: the API is down", [note.value for note in self.page().warning])

    def test_a_clean_up_starts_only_once_you_confirm_it(self):
        page = self.page()
        with mock.patch.object(cleanup, "start") as started:
            page.button(key="cleanup_ask").click().run()
            self.assertEqual([note.value for note in page.warning][-1:], ["Delete 1 folder or file? This cannot be undone."])
            page.button(key="cleanup_no").click().run()
            self.assertNotIn("cleanup_yes", [button.key for button in page.button])
            page.button(key="cleanup_ask").click().run()
            page.button(key="cleanup_yes").click().run()
            page.checkbox(key="cleanup_copies").check().run()  # the old database copies go only when asked for
            page.button(key="cleanup_ask").click().run()
            self.assertEqual([note.value for note in page.warning][-1:], ["Delete 2 folders or files? This cannot be undone."])
            page.button(key="cleanup_yes").click().run()
        self.assertEqual(started.call_args_list, [mock.call(self.folder, copies=False), mock.call(self.folder, copies=True)])

    def test_when_only_old_database_copies_are_left_the_box_must_be_ticked(self):
        cleanup.remove(self.data / "agent-runtime")
        page = self.page()
        self.assertIn("Only old database copies are left: tick the box to delete them.", [caption.value for caption in page.caption])
        self.assertNotIn("cleanup_ask", [button.key for button in page.button])
        page.checkbox(key="cleanup_copies").check().run()
        self.assertIn("cleanup_ask", [button.key for button in page.button])

    def test_a_running_clean_up_shows_how_far_it_got_and_nothing_to_start(self):
        own = cleanup.place(self.folder)
        own["folder"].mkdir(parents=True)
        held = batch.claim(own)
        self.addCleanup(held.close)
        saved.write(own["folder"], cleanup.STATE, {"version": 1, "state": "running", "started": "2026-10-15T08:00:00Z", "ended": None, "items": 5, "done": 2,
                                                   "removed": dict.fromkeys(monitor.FREEABLE, 1), "kept": [], "freeBefore": 1, "freeAfter": None, "problem": None})
        page = self.page()
        (bar,) = page.get("progress")
        self.assertEqual((bar.proto.value, bar.proto.text), (40, "Deleting 2 of 5"))
        self.assertNotIn("cleanup_ask", [button.key for button in page.button])

    def test_the_last_clean_up_says_what_it_deleted_freed_and_kept(self):
        saved.write(cleanup.place(self.folder)["folder"], cleanup.STATE, {
            "version": 1, "state": "done", "started": "2026-10-15T08:00:00Z", "ended": "2026-10-15T08:03:00Z", "items": 5, "done": 5,
            "removed": {monitor.REMOVED: 3, monitor.RETIRED: 1}, "kept": [{"what": "workspaces/w/worktrees/x", "owner": monitor.REMOVED, "why": "it holds changes not committed"}],
            "freeBefore": 10 * resources.GB, "freeAfter": 12 * resources.GB, "problem": None})
        page = self.page()
        said = next(caption.value for caption in page.caption if caption.value.startswith("Last clean-up, "))
        self.assertTrue(said.endswith(": deleted 4 of 5 folders or files and freed 2.0 GB on this disk."), said)
        self.assertEqual(frame(page, "why")[["what", "why"]].values.tolist(), [["workspaces/w/worktrees/x", "it holds changes not committed"]])

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
