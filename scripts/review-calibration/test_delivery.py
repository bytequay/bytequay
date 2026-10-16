import ast
import json
import logging
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

from streamlit import config
from streamlit.testing.v1 import AppTest

import dashboard
import delivery
import saved

PAGE = Path(__file__).with_name("pages") / "Delivery.py"
# As in test_dashboard: outside a running page Streamlit warns on every start that there is no page.
logging.getLogger("streamlit.runtime.scriptrunner_utils.script_run_context").addFilter(lambda record: "missing ScriptRunContext" not in record.getMessage())
DAY = 86400


def turn(task, day, minutes, result=None, model="opus", effort="max", tokens=(1000, 10), cost=1.0):
    return {"task": task, "start": saved.stamp(1_800_000_000 + day * DAY), "seconds": minutes * 60, "result": result, "model": model, "effort": effort,
            "inputTokens": tokens and tokens[0], "outputTokens": tokens and tokens[1], "cost": cost}


def table():
    """Five development tasks and one task that never reported a result. Task d ran two settings, most of its turns on Codex,
    which records no cost; task e is no longer in the database, so its setting is not known."""
    return {"version": 1, "savedAt": "2027-01-15T08:00:00Z", "turns": [
        turn("a", 0, 10), turn("a", 0, 20, "not ready"), turn("a", 1, 30, "ready"),
        turn("b", 1, 15, "ready"), turn("c", 2, 5, "ready"), turn("c", 2, 5, "ready"),
        turn("d", 3, 8, "not ready", model="gpt-6-astra", cost=None), turn("d", 3, 4, model="gpt-6-astra", cost=None), turn("d", 3, 2, "not ready"),
        turn("e", 4, 12, "ready", model=None, effort=None, tokens=None, cost=None),
        turn("f", 5, 50)]}


class TestTasks(unittest.TestCase):
    def test_every_task_with_a_result_counts_under_the_setting_most_of_its_turns_ran(self):
        rows = delivery.tasks(table()).set_index("task")
        self.assertEqual(list(rows.index), ["a", "b", "c", "d", "e"])  # f never reported a result
        self.assertEqual(rows.loc["a", ["setting", "turns", "minutes", "results", "ready"]].tolist(), ["opus · max", 3, 60, 2, 1])
        self.assertEqual(rows.loc["d", "setting"], "gpt-6-astra · max")
        self.assertEqual(rows.loc["e", "setting"], delivery.UNKNOWN)
        # Tokens and dollars are summed only where every turn of the task records them.
        self.assertEqual(rows.loc["a", "tokens"], 3 * 1010)
        self.assertEqual((rows.loc["a", "dollars"], rows["dollars"].isna().tolist()), (3.0, [False, False, False, True, True]))
        self.assertTrue(rows.loc["e", ["tokens", "dollars"]].isna().all())

    def test_only_turns_since_a_moment_count(self):
        since = 1_800_000_000 + 2 * DAY
        self.assertEqual(delivery.tasks(table(), since)["task"].tolist(), ["c", "d", "e"])
        self.assertTrue(delivery.tasks({"turns": []}).empty)


class TestBySetting(unittest.TestCase):
    def test_settings_come_most_tasks_first_and_all_of_them_last(self):
        made = delivery.by_setting(delivery.tasks(table()))
        self.assertEqual(made["setting"].tolist(), ["opus · max", "gpt-6-astra · max", delivery.UNKNOWN, delivery.ALL])
        opus = made.iloc[0]
        self.assertEqual((opus["tasks"], opus["results"], opus["ready"], opus["share"], opus["minutes"]), (3, 5, 4, 80, (60 + 15 + 10) / 4))
        # Minutes per ready result count the minutes of every task, also of those never ready, as task d.
        whole = made.iloc[-1]
        self.assertEqual((whole["ready"], whole["results"], whole["share"], whole["minutes"]), (5, 8, 62.5, (60 + 15 + 10 + 14 + 12) / 5))
        self.assertIsNotNone(whole["minutes range"])
        self.assertIsNone(made.iloc[1]["minutes range"])  # one task: no range
        self.assertTrue(made.iloc[1][["minutes", "dollars"]].isna().all())  # never ready, and Codex records no cost
        # Dollars per ready result come from the tasks that record a cost: a, b and c spent 3 + 1 + 2 dollars for 4.
        self.assertEqual(whole["dollars"], 6 / 4)

    def test_a_single_setting_has_no_all_row_and_words_say_where_no_range_is(self):
        one = delivery.by_setting(delivery.tasks({"turns": [turn("a", 0, 10, "ready")]}))
        self.assertEqual(one["setting"].tolist(), ["opus · max"])
        shown = delivery.shown(one).iloc[0]
        self.assertEqual((shown["ready"], shown["ready % range"], shown["minutes range"]), ("1 of 1", "no range yet", "no range yet"))

    def test_tasks_never_ready_are_listed_by_their_agent_time(self):
        stuck = delivery.never_ready(delivery.tasks(table() | {"turns": [*table()["turns"], turn("g", 6, 70, "not ready")]}))
        self.assertEqual(stuck[["task", "model and effort", "results", "minutes"]].values.tolist(), [["g", "opus · max", 1, 70.0], ["d", "gpt-6-astra · max", 2, 14.0]])

    def test_the_chart_has_a_bar_for_each_setting_with_a_ready_result_and_a_range_where_there_is_one(self):
        figure = delivery.minutes_figure(delivery.by_setting(delivery.tasks(table())), "light")
        bars, whiskers = figure.data
        self.assertEqual(list(bars.y), ["opus · max  (3 tasks)", "not known  (1 task)"])
        self.assertEqual(list(whiskers.y), ["opus · max  (3 tasks)"])  # one task: no range, and no whisker


class TestPage(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.folder = Path(directory.name).resolve()
        arguments = mock.patch.object(sys, "argv", ["dashboard.py", str(self.folder)])
        arguments.start()
        self.addCleanup(arguments.stop)
        config.set_option("server.address", dashboard.HOST)
        self.addCleanup(config.set_option, "server.address", None)

    def page(self, made=None):
        if made is not None:
            (self.folder / "turns.json").write_text(json.dumps(made))
        return AppTest.from_file(str(PAGE), default_timeout=30).run()

    def test_tiles_the_chart_and_both_tables_are_drawn(self):
        page = self.page(table())
        self.assertEqual(list(page.exception), [])
        self.assertEqual([(tile.label, tile.value) for tile in page.metric], [("Development tasks", "5"), ("Ready", "5 of 8"), ("Minutes per ready", "22"), ("Never ready", "1")])
        self.assertEqual([heading.value for heading in page.subheader], ["Agent minutes per ready change", "Why results were not ready", "Never ready"])
        self.assertEqual((len(page.get("plotly_chart")), len(page.dataframe)), (1, 2))  # a table saved before the reasons were named has none to list
        self.assertEqual(page.dataframe[1].value["task"].tolist(), ["d"])

    def test_a_result_that_asked_for_no_change_is_left_out_and_why_the_rest_were_not_ready_is_counted(self):
        made = table()
        for row, named in zip(made["turns"], (None, "waiting for you", None, None, None, None, "no change asked", None, "no change asked", None, None)):
            row["notReady"] = named
        self.assertEqual(delivery.tasks(made)["task"].tolist(), ["a", "b", "c", "e"])  # d only answered questions
        self.assertEqual(delivery.not_ready(made).values.tolist(), [["no change asked", 2], ["waiting for you", 1]])
        page = self.page(made)
        self.assertEqual(page.metric[0].value, "4")
        self.assertEqual(page.dataframe[1].value.values.tolist(), [["no change asked", 2], ["waiting for you", 1]])

    def test_a_table_saved_before_results_says_to_save_again(self):
        old = {"version": 1, "savedAt": "2027-01-15T08:00:00Z", "turns": [{key: value for key, value in turn("a", 0, 10).items() if key != "result"}]}
        self.assertEqual([note.value for note in self.page(old).info], ["This table was saved before development results were kept. Save it again with `uv run activity.py --save`"])

    def test_nothing_saved_and_no_result_say_so(self):
        self.assertEqual([note.value for note in self.page().info], ["Nothing saved yet. Save the turn table with `uv run activity.py --save`"])
        self.assertEqual([note.value for note in self.page({"version": 1, "savedAt": "2027-01-15T08:00:00Z", "turns": [turn("f", 5, 50)]}).info],
                         ["No development task reported a result in this stretch."])

    def test_page_started_another_way_shows_nothing(self):
        config.set_option("server.address", "0.0.0.0")
        page = self.page(table())
        self.assertEqual(([note.value for note in page.error], len(page.metric)), (["This page is only shown to this machine. Start it with `uv run dashboard.py`"], 0))

    def test_page_has_no_bare_expression_that_streamlit_would_print(self):
        tree = ast.parse(PAGE.read_text())
        bare = [node.lineno for node in ast.walk(tree) if isinstance(node, ast.Expr) and not isinstance(node.value, ast.Call)
                and not (isinstance(node.value, ast.Constant) and isinstance(node.value.value, str))]
        self.assertEqual(bare, [])


if __name__ == "__main__":
    unittest.main()
