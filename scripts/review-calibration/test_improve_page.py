import json
import logging
from pathlib import Path
import sys
import unittest
from unittest import mock

from streamlit import config
from streamlit.testing.v1 import AppTest

import batch
import dashboard
import improve
from test_agent import Script
from test_improve import UNSEEN, Lab, writings

PAGE = Path(__file__).with_name("pages") / "Improve.py"
# As in test_dashboard: outside a running page Streamlit warns on every start that there is no page.
logging.getLogger("streamlit.runtime.scriptrunner_utils.script_run_context").addFilter(lambda record: "missing ScriptRunContext" not in record.getMessage())


class TestPage(Lab):
    def setUp(self):
        super().setUp()
        arguments = mock.patch.object(sys, "argv", ["dashboard.py", str(self.folder)])
        arguments.start()
        self.addCleanup(arguments.stop)
        config.set_option("server.address", dashboard.HOST)
        self.addCleanup(config.set_option, "server.address", None)

    def page(self):
        return AppTest.from_file(str(PAGE), default_timeout=30).run()

    def written(self, **changed):
        """The record of improvement 1, changed and kept again as the improvement keeps it."""
        own = self.folder / "improvements" / "1"
        made = json.loads((own / improve.STATE).read_text()) | changed
        (own / improve.STATE).write_text(json.dumps(made))
        return made

    def texts(self, page):
        return [part.value for kind in ("markdown", "caption", "info", "warning", "code") for part in getattr(page, kind)]

    def test_a_finished_improvement_shows_its_verdict_and_what_each_version_changed_and_no_unseen_case(self):
        self.improve(Script(*writings()))
        page = self.page()
        self.assertEqual(list(page.exception), [])
        self.assertEqual([box.label for box in page.expander], ["Improvement 1: better"])
        shown = self.texts(page)
        for line in improve.said(json.loads((self.folder / "improvements" / "1" / improve.STATE).read_text())["compared"]):
            self.assertIn(line, shown)
        self.assertIn("work-1 · work · done", shown)
        self.assertIn("- Open the tests first: No run opened a test.", shown)
        self.assertTrue(any(text.startswith("--- rules now") and "+Merged rule." in text for text in shown))  # the merge's whole diff
        self.assertFalse([text for text in shown if any(case in text for case in UNSEEN)])  # no unseen case on its own

    def test_an_improvement_is_started_from_the_experiments_and_settings_it_can_use(self):
        with mock.patch.object(improve, "start") as start:
            page = self.page()
            self.assertEqual([(box.label, box.options) for box in page.selectbox],
                             [("Tuning cases", ["tune"]), ("Unseen cases", ["hold"]), ("Setting with today's rules", ["base"])])
            page.button(key="improve_start").click().run()
        start.assert_called_once_with(self.folder, ["--tuning", "tune", "--unseen", "hold", "--setting", "base"], "tune")
        hold = json.loads((self.experiment("hold") / "plan.json").read_text())
        (self.experiment("hold") / "plan.json").write_text(json.dumps({**hold, "unseen": False}))
        self.assertEqual([note.value for note in self.page().info],
                         ['An improvement needs an experiment of tuning cases and one of unseen cases, marked with "unseen": true in its plan.'])

    def test_the_choices_start_from_the_last_improvements(self):
        other = self.experiment("aaa")  # listed before the tuning experiment of the last improvement
        other.mkdir()
        for name in ("plan.json", "rules.md"):
            (other / name).write_text((self.experiment("tune") / name).read_text())
        self.assertEqual(self.page().selectbox[0].value, "aaa")
        improve.begun(self.folder, "tune", "hold", "base")
        self.written(state="stopped")
        self.assertEqual([box.value for box in self.page().selectbox], ["tune", "hold", "base"])

    def test_a_running_improvement_shows_its_stages_and_can_be_stopped(self):
        made = improve.begun(self.folder, "tune", "hold", "base")
        self.written(done=["base", "work"], labels={**made["labels"], "work": "work-1"})
        held = batch.claim(batch.load(self.folder, "tune"))
        self.addCleanup(held.close)
        page = self.page()
        self.assertEqual([title.value for title in page.subheader][0], "Improvement 1 is running")
        self.assertEqual((page.button(key="improve_stop").label, "Versions so far: work-1" in self.texts(page)), ("Stop", True))
        self.assertEqual([button.key for button in page.button if button.key in ("improve_start", "improve_continue")], [])

    def test_a_stopped_improvement_can_be_carried_on(self):
        improve.begun(self.folder, "tune", "hold", "base")
        self.written(state="stopped")
        with mock.patch.object(improve, "start") as start:
            page = self.page()
            self.assertIn("Improvement 1 stopped.", [note.value for note in page.info])
            page.button(key="improve_continue").click().run()
        start.assert_called_once_with(self.folder, ["--continue"], "tune")


if __name__ == "__main__":
    unittest.main()
