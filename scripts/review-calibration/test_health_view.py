import logging
from datetime import datetime, timezone
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest
from unittest import mock

from streamlit import config
from streamlit.testing.v1 import AppTest

import app_logs
import dashboard
import health_view
import resources
import saved
from test_health import NOW, group, logs, samples
from test_app_logs import line
from test_desktop import database

logging.getLogger("streamlit.runtime.scriptrunner_utils.script_run_context").addFilter(lambda row: "missing ScriptRunContext" not in row.getMessage())


class TestHealthPage(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name)
        arguments = mock.patch.object(sys, "argv", ["dashboard.py", str(self.folder), "--runtime", str(self.folder / "runtime"), "--logs", str(self.folder / "logs")])
        arguments.start()
        self.addCleanup(arguments.stop)
        clock = mock.patch.object(health_view.time, "time", return_value=NOW)
        clock.start()
        self.addCleanup(clock.stop)
        config.set_option("server.address", dashboard.HOST)
        self.addCleanup(config.set_option, "server.address", None)

    def page(self):
        return AppTest.from_file(dashboard.__file__, default_timeout=30).run()

    def test_landing_health_is_available_without_turns_or_logs(self):
        page = self.page()
        self.assertEqual(list(page.exception), [])
        self.assertIn("App health", [row.value for row in page.subheader])
        self.assertIn("Unknown", [row.value for row in page.info])
        self.assertTrue(page.button(key="health_monitor"))
        self.assertFalse((self.folder / "monitor.db").exists())

    def test_missing_database_refresh_still_opens_health_and_warns_about_agent_statistics(self):
        page = self.page()
        page.button(key="refresh").click().run()
        self.assertEqual(list(page.exception), [])
        self.assertTrue((self.folder / app_logs.FILE).is_file())
        rows = page.dataframe[0].value
        self.assertIn("Agent statistics", rows["component"].tolist())
        self.assertFalse((self.folder / "bytequay-1.0.db").exists())

    def test_analyze_now_reads_new_logs_on_each_click_and_updates_the_visible_findings(self):
        database(self.folder / "bytequay-1.0.db")
        source = self.folder / "logs" / "backend.log"
        source.parent.mkdir()
        now = datetime.now(timezone.utc).timestamp()
        source.write_text(line("database is locked", "ERROR", at=now - 2))
        with mock.patch.object(health_view.time, "time", return_value=now):
            page = self.page()
            self.assertEqual(page.main.button(key="refresh").label, "Analyze now")
            self.assertNotIn("Analyze now", [button.label for button in page.sidebar.button])
            self.assertFalse((self.folder / app_logs.FILE).exists())
            page.button(key="refresh").click().run()
            self.assertEqual(list(page.exception), [])
            self.assertIn("Analysis complete. Every page of the Lab now shows what it found.", [note.value for note in page.success])
            self.assertEqual(next(tile.value for tile in page.metric if tile.label == "Errors in analyzed 24 hours"), "1")
            self.assertTrue(any("Database contention" in heading.value for heading in page.markdown))
            with source.open("a") as stream:
                stream.write(line("java.lang.OutOfMemoryError: Java heap space", "ERROR", at=now - 1))
            page.button(key="refresh").click().run()
            self.assertEqual(list(page.exception), [])
            self.assertEqual(next(tile.value for tile in page.metric if tile.label == "Errors in analyzed 24 hours"), "2")
            self.assertTrue(any("Backend memory exhaustion" in heading.value for heading in page.markdown))
        self.assertFalse((self.folder / "monitor.db").exists())
        self.assertFalse((self.folder / "runtime").exists())

    def test_failed_analysis_keeps_the_previous_snapshot_and_can_be_retried(self):
        saved.write(self.folder, app_logs.FILE, {"version": 1, **logs([group("cleanup", 3)])})
        before = (self.folder / app_logs.FILE).read_bytes()
        page = self.page()
        failure = subprocess.CompletedProcess([], 1, "", "temporary read failure")
        with mock.patch.object(dashboard.subprocess, "run", return_value=failure):
            page.button(key="refresh").click().run()
        self.assertEqual(list(page.exception), [])
        self.assertTrue(any("temporary read failure" in note.value for note in page.error))
        self.assertEqual((self.folder / app_logs.FILE).read_bytes(), before)
        page.button(key="refresh").click().run()
        self.assertEqual((list(page.error), list(page.exception)), ([], []))
        self.assertIn("Analysis complete. Every page of the Lab now shows what it found.", [note.value for note in page.success])

    def test_findings_prepare_reviewable_task_briefs_without_sending(self):
        saved.write(self.folder, app_logs.FILE, {"version": 1, **logs([group("cleanup", 3)])})
        config.set_option("server.baseUrlPath", "lab-session")
        self.addCleanup(config.set_option, "server.baseUrlPath", "")
        page = self.page()
        self.assertEqual(list(page.exception), [])
        self.assertIn("Attention needed", [row.value for row in page.warning])
        self.assertEqual([button.label for button in page.get("link_button")], ["Investigate in ByteQuay"])
        self.assertIn("What the lab saw", page.code[0].value)
        self.assertIn("Verify with the same workload", page.code[0].value)
        self.assertIn("Errors in analyzed 24 hours", [tile.label for tile in page.metric])
        self.assertIn("Warnings in analyzed 24 hours", [tile.label for tile in page.metric])
        self.assertTrue(any(f"Analyzed 24-hour window ends {dashboard.when(saved.stamp(NOW))}." in row.value for row in page.caption))
        self.assertFalse((self.folder / "runtime").exists())

    def test_fresh_resource_metrics_and_explicit_monitor_start(self):
        made = samples()
        made["parts"]["threads"] = 2
        made["machine"]["memory_used"] = 8 * resources.GB
        made["machine"]["memory_total"] = 16 * resources.GB
        saved.write(self.folder, app_logs.FILE, {"version": 1, **logs()})
        with mock.patch.object(health_view.resources, "read", return_value=made):
            page = self.page()
        self.assertEqual(list(page.exception), [])
        self.assertIn("No issue observed", [row.value for row in page.success])
        self.assertIn("App memory", [tile.label for tile in page.metric])
        page = self.page()
        with mock.patch.object(health_view.batch, "keep_agent") as start:
            page.button(key="health_monitor").click().run()
        self.assertEqual(start.call_count, 1)
        self.assertEqual(start.call_args.args[0], "com.bytequay.lab.monitor")

    def test_bad_snapshot_is_unknown_instead_of_crashing(self):
        saved.write(self.folder, app_logs.FILE, {"version": 1, "coverage": {}})
        page = self.page()
        self.assertEqual(list(page.exception), [])
        self.assertIn("Unknown", [row.value for row in page.info])


if __name__ == "__main__":
    unittest.main()
