import ast
import json
import logging
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest import mock

from streamlit import config
from streamlit.testing.v1 import AppTest

import activity
import charts
import dashboard

EXAMPLE = Path(__file__).with_name("fixture") / "saved-turns.json"
# Outside a running page Streamlit warns on every start that there is no page, which is how these tests run it.
# A filter and not a level, because Streamlit sets the levels again whenever an option changes.
logging.getLogger("streamlit.runtime.scriptrunner_utils.script_run_context").addFilter(lambda record: "missing ScriptRunContext" not in record.getMessage())
SAVED = "Save the turn table with `uv run activity.py --save`"
OVERVIEW = ["Where the time went", "How the turns ended", "Turn time per day"]
TIME = ["Every part of the time", "Waiting for a tool to finish: what was running", "Shell time by program, summed over runs", "Model time by what just finished",
        "The tasks that took the most time", "Turn time by repository"]
SLOW = ["Long single commands", "Waiting for you", "Rate limits and retries"]
MODELS = ["Typical turn, by model and effort", "How long a turn took", "How each split its time", "How the turns ended"]
COST = ["Tokens by kind", "Tokens by model and effort", "Tokens per day", "The tasks that used the most tokens", "Cost in dollars"]


def table(page, column):
    """The one table on the page that has this column, wherever it stands."""
    (found,) = [frame.value for frame in page.dataframe if column in frame.value.columns]
    return found


def headings(page):
    return [heading.value for heading in page.subheader]


def captions(page):
    return [caption.value for caption in page.caption]


class Page(unittest.TestCase):
    """The whole page, run without a browser on the saved example."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.folder = Path(directory.name)
        arguments = mock.patch.object(sys, "argv", ["dashboard.py", str(self.folder)])
        arguments.start()
        self.addCleanup(arguments.stop)
        config.set_option("server.address", dashboard.HOST)  # what the page's own start-up sets
        self.addCleanup(config.set_option, "server.address", None)

    def page(self):
        return AppTest.from_file(dashboard.__file__, default_timeout=30).run()

    def save(self, text=None):
        (self.folder / "turns.json").write_text(EXAMPLE.read_text() if text is None else text)

    def test_page_shows_the_saved_turns_in_tiles_and_charts(self):
        self.save()
        page = self.page()
        self.assertEqual(list(page.exception), [])
        self.assertEqual([title.value for title in page.title], ["ByteQuay Lab"])
        self.assertEqual([(tile.label, tile.value) for tile in page.metric][:5],
                         [("Turn time", "0.3 h"), ("Tasks", "2"), ("Turns", "3"), ("Typical turn", "5.0 min"), ("Longest turn", "10 min")])
        self.assertEqual(headings(page), OVERVIEW + TIME + SLOW + COST)  # the models tab has too few turns to draw
        self.assertEqual(len(page.get("plotly_chart")), 18)  # three, six, four, none and five on the five tabs
        self.assertIn("Two or more tools ran at once for 1.3 minutes; the most at once was 2.", captions(page))  # 60 seconds in one turn and 20 in another
        self.assertIn("4 of 4 turns. Days are in your own time zone.", captions(page))
        self.assertIn("With a log, so the time split is known: 3. In ByteQuay's records, so the model is known: 3. In both: 2.", captions(page))
        self.assertEqual(table(page, "runs").values.tolist(), [["maven", 2, 1], ["npm", 2, 1], ["git", 0.7, 1]])

    def test_models_tab_lists_every_model_and_effort_and_draws_only_those_with_enough_turns(self):
        self.save()
        page = self.page()
        self.assertEqual(table(page, "typical minutes")[["model and effort", "turns", "typical minutes", "typical tokens in", "completed %"]].values.tolist(),
                         [["gpt-6-astra · max", 1, 10, 900000, 100], ["gpt-6-astra · medium", 1, 5, 200000, 0], ["opus · high", 1, 4, 50000, 100]])
        self.assertIn("Different tasks ran on each model and effort, so this shows what happened, not which one is better. One with fewer than 5 turns is in the table only. "
                      "Typical means the middle turn: half are below it, half above.", [caption.value for caption in page.caption])
        self.assertNotIn("How long a turn took", headings(page))  # one turn each: the table has them, the charts do not
        with mock.patch.object(charts, "MIN_TURNS", 1):
            drawn = self.page()
        self.assertEqual(list(drawn.exception), [])
        self.assertEqual(headings(drawn), OVERVIEW + TIME + SLOW + MODELS + COST)
        self.assertEqual(len(drawn.get("plotly_chart")) - len(page.get("plotly_chart")), 4)

    def test_choosing_a_model_and_effort_narrows_every_tab(self):
        self.save()
        page = self.page()
        self.assertEqual(page.sidebar.multiselect[0].options, ["gpt-6-astra · max", "gpt-6-astra · medium", "opus · high"])
        page.sidebar.multiselect[0].set_value(["gpt-6-astra · medium"]).run()
        self.assertEqual(list(page.exception), [])
        self.assertEqual([(tile.label, tile.value) for tile in page.metric][:3], [("Turn time", "0.1 h"), ("Tasks", "1"), ("Turns", "1")])
        self.assertEqual(table(page, "runs")["program"].tolist(), ["npm"])
        self.assertEqual(table(page, "typical minutes")["model and effort"].tolist(), ["gpt-6-astra · medium"])
        # A turn the database knows and no log holds: the time tabs say so, and the Models tab still has it.
        page.sidebar.multiselect[0].set_value(["opus · high"]).run()
        self.assertEqual(([note.value for note in page.info], [tile.label for tile in page.metric][0]), (["No turn of this choice has a log."] * 3, "Tokens in"))
        self.assertEqual(table(page, "typical minutes")["model and effort"].tolist(), ["opus · high"])

    def test_slow_spots_tab_lists_long_commands_waits_for_the_user_and_rate_limit_notices(self):
        self.save()
        page = self.page()
        self.assertIn("3 single commands ran for 30 seconds or more: 4.7 minutes in all.", captions(page))
        self.assertEqual(table(page, "day")[["program", "minutes", "task"]].values.tolist(), [["maven", 2, "aaaa-1111"], ["npm", 2, "aaaa-1111"], ["git", 0.7, "bbbb-2222"]])
        self.assertIn("The agent asked 1 time and waited 1.0 minutes in all. A typical answer took 60 seconds.", captions(page))
        self.assertIn("The logs say how often these came, not how long they held a turn up. rate limit: allowed warning: 2, rate limit: rejected: 1, retry: 1.", captions(page))

    def test_slow_spots_tab_says_so_when_there_is_nothing_slow_and_when_a_wait_ran_out(self):
        saved = json.loads(EXAMPLE.read_text())
        quiet = [{**row, "longRuns": [], "approvals": [], "notices": {}} for row in saved["turns"]]
        self.save(json.dumps({**saved, "turns": quiet}))
        page = self.page()
        self.assertEqual(list(page.exception), [])
        for expected in ["No single command ran for 30 seconds or more.", "The agent never had to wait for your answer.", "No rate limit notice and no retry."]:
            self.assertIn(expected, captions(page))
        self.assertEqual(len(page.get("plotly_chart")), 14)  # the four charts of this tab are not drawn
        waited = [{**row, "approvals": [{"kind": "command", "outcome": "expired", "seconds": 300}, {"kind": "command", "outcome": "allowed", "seconds": 20}] * row["logged"]}
                  for row in saved["turns"]]
        self.save(json.dumps({**saved, "turns": waited}))
        self.assertIn("The agent asked 6 times and waited 16.0 minutes in all. A typical answer took 160 seconds. 3 waits ran out with no answer, which ends the turn.",
                      captions(self.page()))

    def test_choosing_a_repository_narrows_every_tab(self):
        self.save()
        page = self.page()
        self.assertEqual(page.sidebar.multiselect[1].options, ["bytequay/bytequay", "trinodb/trino"])
        page.sidebar.multiselect[1].set_value(["trinodb/trino"]).run()
        self.assertEqual(list(page.exception), [])
        self.assertEqual([(tile.label, tile.value) for tile in page.metric][:3], [("Turn time", "0.2 h"), ("Tasks", "1"), ("Turns", "2")])
        self.assertEqual((table(page, "runs")["program"].tolist(), table(page, "typical minutes")["model and effort"].tolist()),
                         (["maven", "npm"], ["gpt-6-astra · max", "gpt-6-astra · medium"]))
        self.assertIn("2 of 4 turns. Days are in your own time zone.", captions(page))

    def test_models_tab_says_why_when_the_database_was_not_read(self):
        table = json.loads(EXAMPLE.read_text())
        logged = [{**row, **dict.fromkeys(activity.FACTS)} for row in table["turns"] if row["logged"]]
        self.save(json.dumps({**table, "database": {"problem": "the database is not there"}, "turns": logged}))
        page = self.page()
        self.assertEqual(list(page.exception), [])
        self.assertEqual([note.value for note in page.info], ["Model, effort and tokens were not saved: the database is not there. Save again with `uv run activity.py --save`",
                                                              "Tokens and cost were not saved: the database is not there. Save again with `uv run activity.py --save`"])
        self.assertEqual((len(page.metric), len(page.sidebar.multiselect)), (5, 0))  # the time tabs are whole, and there is no model to pick

    def test_cost_tab_adds_up_tokens_and_keeps_recorded_cost_apart_from_what_it_cannot_know(self):
        self.save()
        page = self.page()
        self.assertEqual([(tile.label, tile.value) for tile in page.metric][5:], [("Tokens in", "1.1 M"), ("From the cache", "90.4%"), ("Tokens out", "7.0 K"), ("Recorded cost", "$1.50")])
        self.assertEqual(headings(page)[-5:], COST)
        self.assertIn("Tokens are known for 3 of 4 turns. Each amount is the turn's own.", captions(page))
        cost = table(page, "how")
        self.assertEqual(cost[["model and effort", "dollars", "how"]].values.tolist()[0], ["opus · high", 1.5, "recorded"])
        self.assertEqual(cost["how"].tolist()[1:], ["unknown", "unknown"])  # no recorded cost and no price: nothing is made up
        # The models without a cost are named with a price table to fill in, and nothing was written for the reader.
        self.assertIn(f"No cost is known for gpt-6-astra. To estimate it, save this as `prices.json` in `{self.folder}` with your own prices, in dollars for a million tokens:",
                      captions(page))
        self.assertEqual(json.loads(page.code[0].value), {"version": 1, "dollarsPerMillionTokens": {"gpt-6-astra": {"input": 0, "cachedInput": 0, "output": 0}}})
        self.assertEqual(sorted(path.name for path in self.folder.iterdir()), ["turns.json"])

    def test_cost_tab_estimates_from_the_readers_own_prices(self):
        self.save()
        prices = {"version": 1, "dollarsPerMillionTokens": {"gpt-6-astra": {"input": 2, "cachedInput": 0.2, "output": 10}}}
        (self.folder / "prices.json").write_text(json.dumps(prices))
        page = self.page()
        self.assertEqual(list(page.exception), [])
        self.assertEqual([(tile.label, tile.value) for tile in page.metric][8:], [("Recorded cost", "$1.50"), ("Estimated cost", "$0.45")])
        self.assertEqual(table(page, "how")[["model and effort", "dollars", "how"]].values.tolist(),
                         [["opus · high", 1.5, "recorded"], ["gpt-6-astra · max", 0.31, "estimated"], ["gpt-6-astra · medium", 0.14, "estimated"]])
        self.assertEqual((len(page.code), [caption for caption in captions(page) if caption.startswith("No cost is known")]), (0, []))

    def test_price_table_to_fill_in_keeps_the_prices_already_given(self):
        self.save()
        opus = {"input": 15.0, "cachedInput": 1.5, "output": 75.0}
        (self.folder / "prices.json").write_text(json.dumps({"version": 1, "dollarsPerMillionTokens": {"opus": opus}}))
        page = self.page()
        self.assertEqual(json.loads(page.code[0].value), {"version": 1, "dollarsPerMillionTokens": {"opus": opus, "gpt-6-astra": {"input": 0, "cachedInput": 0, "output": 0}}})

    def test_price_table_in_another_shape_estimates_nothing_and_says_so(self):
        self.save()
        for name, text in [("cut off", '{"version": 1, "dollarsPer'), ("later version", json.dumps({"version": 2, "dollarsPerMillionTokens": {}})),
                           ("no prices", json.dumps({"version": 1})), ("a price missing", json.dumps({"version": 1, "dollarsPerMillionTokens": {"gpt-6-astra": {"input": 2}}})),
                           ("text for a price", json.dumps({"version": 1, "dollarsPerMillionTokens": {"gpt-6-astra": {"input": "cheap", "cachedInput": 0, "output": 0}}})),
                           ("below zero", json.dumps({"version": 1, "dollarsPerMillionTokens": {"gpt-6-astra": {"input": -2, "cachedInput": 0, "output": 0}}})),
                           ("a list", json.dumps({"version": 1, "dollarsPerMillionTokens": [1, 2]}))]:
            with self.subTest(name=name):
                (self.folder / "prices.json").write_text(text)
                page = self.page()
                self.assertEqual(([note.value for note in page.warning], list(page.exception)), (["The price table `prices.json` could not be read, so no cost is estimated."], []))
                self.assertEqual([tile.label for tile in page.metric][5:], ["Tokens in", "From the cache", "Tokens out", "Recorded cost"])
                self.assertEqual(table(page, "how")["how"].tolist(), ["recorded", "unknown", "unknown"])

    def test_choosing_days_changes_every_number(self):
        self.save()
        page = self.page()
        last = charts.frames(json.loads(EXAMPLE.read_text()))["turns"]["day"].max()
        page.sidebar.date_input[0].set_value((last, last)).run()
        self.assertEqual(list(page.exception), [])
        self.assertEqual([(tile.label, tile.value) for tile in page.metric][:3], [("Turn time", "0.1 h"), ("Tasks", "2"), ("Turns", "2")])
        self.assertIn("3 of 4 turns. Days are in your own time zone.", captions(page))
        self.assertIn("Two or more tools ran at once for 0.3 minutes; the most at once was 2.", captions(page))
        self.assertEqual(table(page, "runs")["program"].tolist(), ["npm", "git"])

    def test_what_was_left_out_when_saving_is_said_in_the_side_bar(self):
        table = json.loads(EXAMPLE.read_text())
        self.save(json.dumps({**table, "since": "2026-01-01", "task": "aaaa", "leftOut": {"skippedLines": 2, "unreadLogs": 1}}))
        captions = [caption.value for caption in self.page().caption]
        for expected in ["Only turns that started on or after 2026-01-01 were saved.", "Only the task whose id starts with aaaa was saved.",
                         "2 log lines cut off or in an old format were skipped.", "1 task log with no turn in the current format was left out."]:
            self.assertIn(expected, captions)

    def test_nothing_saved_says_how_to_save(self):
        page = self.page()
        self.assertEqual(([note.value for note in page.info], len(page.metric), list(page.exception)), ([f"Nothing saved yet. {SAVED}"], 0, []))

    def test_table_without_a_turn_says_so(self):
        self.save(json.dumps({**json.loads(EXAMPLE.read_text()), "turns": []}))
        page = self.page()
        self.assertEqual(([note.value for note in page.info], len(page.metric), list(page.exception)), (["The saved turn table holds no turn."], 0, []))

    def test_file_in_another_shape_is_said_to_be_unreadable_and_is_never_drawn(self):
        table = json.loads(EXAMPLE.read_text())
        unknown_part = json.loads(EXAMPLE.read_text())
        unknown_part["turns"][0]["groups"]["a part from a later version"] = 5
        for name, text in [("cut off", EXAMPLE.read_text()[:200]), ("not a table", "[]"), ("later version", json.dumps({**table, "version": 2})),
                           ("no turns", json.dumps({"version": 1, "savedAt": table["savedAt"]})), ("unknown part", json.dumps(unknown_part)),
                           ("text for a number", json.dumps({**table, "turns": [{**table["turns"][0], "seconds": "long"}]}))]:
            with self.subTest(name=name):
                self.save(text)
                page = self.page()
                self.assertEqual(([note.value for note in page.warning], list(page.exception)),
                                 (["The saved turn table could not be read. Save it again with `uv run activity.py --save`"], []))
                self.assertEqual((len(page.metric), len(page.get("plotly_chart")), len(page.info)), (0, 0, 0))

    def test_page_started_another_way_shows_nothing(self):
        self.save()
        for address in (None, "0.0.0.0", "192.168.1.5"):
            with self.subTest(address=address):
                config.set_option("server.address", address)
                page = self.page()
                self.assertEqual([note.value for note in page.error], ["This page is only shown to this machine. Start it with `uv run dashboard.py`"])
                self.assertEqual((len(page.title), len(page.metric), len(page.get("plotly_chart"))), (0, 0, 0))


class TestSource(unittest.TestCase):
    def test_page_has_no_bare_expression_that_streamlit_would_print(self):
        # Streamlit writes the value of any expression that stands alone in the page's file, wherever it stands. A call is
        # the one kind it leaves alone, so `draw(x) if y else z` on a line of its own prints "None" under the chart.
        tree = ast.parse(Path(dashboard.__file__).read_text())
        bare = [node.lineno for node in ast.walk(tree) if isinstance(node, ast.Expr) and not isinstance(node.value, ast.Call)
                and not (isinstance(node.value, ast.Constant) and isinstance(node.value.value, str))]
        self.assertEqual(bare, [])


class TestStart(unittest.TestCase):
    def test_plain_start_hands_over_to_streamlit_and_keeps_the_page_on_this_machine(self):
        seen = []
        with mock.patch.object(sys, "argv", ["dashboard.py", "/some/folder", "--port", "9000"]), mock.patch.object(dashboard, "get_script_run_ctx", return_value=None), \
                mock.patch("streamlit.web.cli.main", side_effect=lambda: seen.extend(sys.argv) or 7), self.assertRaises(SystemExit) as stopped:
            dashboard.main()
        self.assertEqual(stopped.exception.code, 7)  # the page's start ends as Streamlit ends
        self.assertEqual(seen[:3], ["streamlit", "run", dashboard.__file__])
        options = dict(zip(seen[3:-2:2], seen[4:-2:2]))
        self.assertEqual((options["--server.address"], options["--server.port"], options["--server.headless"], options["--browser.gatherUsageStats"]),
                         ("127.0.0.1", "9000", "true", "false"))
        self.assertEqual(seen[-2:], ["--", "/some/folder"])  # the folder goes to the page, not to Streamlit

    def test_default_folder_and_port_are_the_labs_own(self):
        seen = []
        with mock.patch.object(sys, "argv", ["dashboard.py"]), mock.patch.object(dashboard, "get_script_run_ctx", return_value=None), \
                mock.patch("streamlit.web.cli.main", side_effect=lambda: seen.extend(sys.argv) or 0), self.assertRaises(SystemExit):
            dashboard.main()
        self.assertEqual((seen[seen.index("--server.port") + 1], seen[-1]), (str(dashboard.PORT), str(dashboard.saved.FOLDER)))


if __name__ == "__main__":
    unittest.main()
