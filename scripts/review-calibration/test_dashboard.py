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

import charts
import dashboard

EXAMPLE = Path(__file__).with_name("fixture") / "saved-turns.json"
# Outside a running page Streamlit warns on every start that there is no page, which is how these tests run it.
# A filter and not a level, because Streamlit sets the levels again whenever an option changes.
logging.getLogger("streamlit.runtime.scriptrunner_utils.script_run_context").addFilter(lambda record: "missing ScriptRunContext" not in record.getMessage())
SAVED = "Save the turn table with `uv run activity.py --save`"


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
        self.assertEqual([(tile.label, tile.value) for tile in page.metric],
                         [("Turn time", "0.3 h"), ("Tasks", "2"), ("Turns", "3"), ("Typical turn", "5.0 min"), ("Longest turn", "10 min")])
        self.assertEqual([heading.value for heading in page.subheader],
                         ["Where the time went", "How the turns ended", "Turn time per day", "Every part of the time", "Waiting for a tool to finish: what was running",
                          "Shell time by program, summed over runs", "Model time by what just finished", "The tasks that took the most time"])
        self.assertEqual(len(page.get("plotly_chart")), 8)
        captions = [caption.value for caption in page.caption]
        self.assertIn("Two or more tools ran at once for 1.3 minutes; the most at once was 2.", captions)  # 60 seconds in one turn and 20 in another
        self.assertIn("3 of 3 turns. Days are in your own time zone.", captions)
        self.assertEqual([[row["program"], row["minutes"], row["runs"]] for _, row in page.dataframe[0].value.iterrows()], [["maven", 2, 1], ["npm", 2, 1], ["git", 0.7, 1]])

    def test_choosing_days_changes_every_number(self):
        self.save()
        page = self.page()
        last = charts.frames(json.loads(EXAMPLE.read_text()))["turns"]["day"].max()
        page.sidebar.date_input[0].set_value((last, last)).run()
        self.assertEqual(list(page.exception), [])
        self.assertEqual([(tile.label, tile.value) for tile in page.metric][:3], [("Turn time", "0.1 h"), ("Tasks", "2"), ("Turns", "2")])
        captions = [caption.value for caption in page.caption]
        self.assertIn("2 of 3 turns. Days are in your own time zone.", captions)
        self.assertIn("Two or more tools ran at once for 0.3 minutes; the most at once was 2.", captions)
        self.assertEqual([row["program"] for _, row in page.dataframe[0].value.iterrows()], ["npm", "git"])

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
