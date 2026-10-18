import logging
from pathlib import Path
import signal
from types import SimpleNamespace
import sys
import unittest
from unittest import mock

from streamlit import config
from streamlit.testing.v1 import AppTest

import dashboard
import monitor
import processes

PAGE = Path(__file__).with_name("pages") / "Processes.py"
PYTHON = str(processes.PYTHON / "bin" / "python")
# As in test_dashboard: outside a running page Streamlit warns on every start that there is no page.
logging.getLogger("streamlit.runtime.scriptrunner_utils.script_run_context").addFilter(lambda record: "missing ScriptRunContext" not in record.getMessage())


def process(pid, *arguments, started=0.0):
    return SimpleNamespace(pid=pid, info={"cmdline": list(arguments) or None, "create_time": started, "memory_info": SimpleNamespace(rss=50 * 2**20)})


MACHINE = [
    process(10, PYTHON, "/lab/monitor.py", "--sample", "--folder", "/lab", started=3.0),
    process(11, PYTHON, "dashboard.py", started=1.0),
    process(12, PYTHON, "dashboard.py", "--port", "8527", started=2.0),
    process(13, PYTHON, "/lab/batch.py", "nightly", "--folder", "/lab", "--judge", started=4.0),
    process(14, PYTHON, "/lab/batch.py", "--due", "--folder", "/lab", started=5.0),
    process(15, PYTHON, "/lab/run.py", "--repo", "/clone", started=6.0),
    process(16, PYTHON, "-m", "unittest", started=7.0),
    process(17, "/usr/bin/python3", "dashboard.py"),  # another Python: not the lab's
    process(18),  # another user's process: its arguments cannot be read
]


class TestProcesses(unittest.TestCase):
    def setUp(self):
        patched = mock.patch.object(processes.psutil, "process_iter", return_value=MACHINE)
        patched.start()
        self.addCleanup(patched.stop)

    def test_every_lab_program_is_named_oldest_first_and_no_other(self):
        self.assertEqual([(row["pid"], row["what"]) for row in processes.running()],
                         [(11, "Page on port 8517"), (12, "Page on port 8527"), (10, "Monitor"), (13, "Experiment nightly"), (14, "Nightly rounds"),
                          (15, "Review run"), (16, "python -m unittest")])

    def test_stopping_the_monitor_removes_its_agent_first_and_a_number_not_the_labs_is_left_alone(self):
        with mock.patch.object(processes.batch, "keep_agent") as keep, mock.patch.object(processes.os, "kill") as kill:
            processes.stop(11)
            processes.stop(10)
            processes.stop(17)
        self.assertEqual((keep.call_args_list, kill.call_args_list),
                         ([mock.call(monitor.AGENT, None)], [mock.call(11, signal.SIGTERM), mock.call(10, signal.SIGTERM)]))

    def test_bundled_dashboard_and_refresh_are_listed_without_including_other_python_programs(self):
        for directory in (processes.BUNDLED_PYTHON, processes.DEVELOPMENT_PYTHON):
            with self.subTest(directory=directory):
                python = str(directory / "bin" / "python3")
                rows = [process(20, python, "-B", "-s", "/bundle/app/dashboard.py", "--port", "42123", started=1.0),
                        process(21, python, "-B", "-s", "/bundle/app/desktop.py", started=2.0),
                        process(22, "/usr/bin/python3", "dashboard.py")]
                with mock.patch.object(processes.psutil, "process_iter", return_value=rows):
                    self.assertEqual([(row["pid"], row["what"]) for row in processes.running()], [(20, "Page on port 42123"), (21, "Refresh data")])
                    with mock.patch.object(processes.os, "kill") as kill:
                        processes.stop(20)
                        processes.stop(22)
                    kill.assert_called_once_with(20, signal.SIGTERM)


class TestPage(unittest.TestCase):
    def setUp(self):
        for patched in (mock.patch.object(processes.psutil, "process_iter", return_value=MACHINE[:2]), mock.patch.object(processes, "loaded", return_value=False),
                        mock.patch.object(sys, "argv", ["dashboard.py", "/lab"])):
            patched.start()
            self.addCleanup(patched.stop)
        config.set_option("server.address", dashboard.HOST)
        self.addCleanup(config.set_option, "server.address", None)

    def test_stop_and_start_reach_the_program_and_the_monitors_agent(self):
        page = AppTest.from_file(str(PAGE), default_timeout=30).run()
        self.assertEqual(([button.label for button in page.button], list(page.exception)), (["Stop", "Stop", "Start the monitor"], []))
        with mock.patch.object(processes, "stop") as stop, mock.patch.object(processes.batch, "keep_agent") as keep:
            page.button(key="stop-10").click().run()
            page.button[2].click().run()
        self.assertEqual((stop.call_args_list, keep.call_args_list), ([mock.call(10)], [mock.call(monitor.AGENT, monitor.agent(Path("/lab")))]))

    def test_page_started_another_way_shows_nothing(self):
        config.set_option("server.address", "0.0.0.0")
        page = AppTest.from_file(str(PAGE), default_timeout=30).run()
        self.assertEqual(([note.value for note in page.error], len(page.button)), (["This page is only shown to this machine. Start it with `uv run dashboard.py`"], 0))


if __name__ == "__main__":
    unittest.main()
