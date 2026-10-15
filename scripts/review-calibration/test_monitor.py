import os
from pathlib import Path
import plistlib
import re
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

import monitor
from test_batch import TOOLS

SCRIPT = Path(monitor.__file__).resolve()
BACKEND = Path(__file__).resolve().parents[2] / "backend/agent-core/src/main/java/com/bytequay/agent/core"
ELECTRON = "/Users/me/src/bytequay/frontend/node_modules/electron/dist/Electron.app/Contents/MacOS/Electron"
HELPER = "/Users/me/src/bytequay/frontend/node_modules/electron/dist/Electron.app/Contents/Frameworks/Electron Helper.app/Contents/MacOS/Electron Helper"


class Folder(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.folder = Path(directory.name).resolve() / "lab"


class TestParts(unittest.TestCase):
    def test_every_process_of_bytequay_gets_its_part_and_nothing_else_does(self):
        programs = {
            200: (ELECTRON, [ELECTRON, "."]), 201: (HELPER, [HELPER, "--type=renderer", "--lang=en"]), 202: (HELPER, [HELPER, "--type=gpu-process"]),
            203: (HELPER, [HELPER, "--type=utility"]), 204: ("/x/Electron Framework.framework/Helpers/chrome_crashpad_handler", ["chrome_crashpad_handler"]),
            205: ("/bin/zsh", ["-zsh"]),  # a terminal of the app
            300: (ELECTRON, [ELECTRON, "/var/folders/x/main.cjs"]), 301: (HELPER, [HELPER, "--type=renderer"]),  # a browser test's Electron
            400: ("/usr/bin/java", ["java", "-jar", "/Users/me/src/bytequay/backend/app/target/bytequay-backend.jar"]),
            401: ("/opt/homebrew/bin/codex", ["/opt/homebrew/bin/codex", "app-server"]), 402: ("/opt/homebrew/bin/node", ["node", "/x/bin/claude", "--print"]),
            403: ("/bin/zsh", ["/bin/zsh", "-c", "mvn test"]), 404: ("/usr/bin/git", ["git", "status"]),
            500: ("/Applications/ByteQuay.app/Contents/MacOS/ByteQuay", ["/Applications/ByteQuay.app/Contents/MacOS/ByteQuay"]),
            501: ("/Applications/ByteQuay.app/Contents/Resources/jre/bin/java", ["java", "-jar", "bytequay-backend.jar"]),
            600: ("/Applications/Slack.app/Contents/MacOS/Slack", ["Slack"]), 601: ("/usr/bin/java", ["java", "-jar", "other.jar"]),
        }
        parents = {200: 199, 201: 200, 202: 200, 203: 200, 204: 200, 205: 200, 300: 298, 301: 300, 400: 399, 401: 400, 402: 400, 403: 401, 404: 403,
                   500: 1, 501: 500, 600: 1, 601: 1, 405: 400}  # 405 ended before its program was read
        self.assertEqual(monitor.assign(parents, programs), {
            200: "app", 201: "window", 202: "gpu", 203: "helpers", 204: "helpers", 205: "commands", 400: "backend", 401: "agents", 402: "agents",
            403: "commands", 404: "commands", 405: "commands", 500: "app", 501: "backend"})

    def test_a_process_that_turns_into_another_program_is_read_again(self):
        # dev.sh starts the backend as a copy of itself that first runs the build, then turns into Java with the same pid.
        table = {10: (1, "/bin/sh", ["sh", "dev.sh"]), 11: (10, "/bin/sh", ["sh", "dev.sh"])}
        reads = []

        def program(pid):
            reads.append(pid)
            return table[pid][1:]
        listed = lambda attributes: [mock.Mock(pid=pid, info={"ppid": parent, "exe": executable}) for pid, (parent, executable, _) in table.items()]
        watch = monitor.Watch(database=Path("/nowhere/bytequay.db"))
        with mock.patch.object(monitor.psutil, "process_iter", listed), mock.patch.object(monitor, "program", program):
            self.assertNotIn("backend", watch.parts().values())
            table[11] = (10, "/usr/bin/java", ["java", "-jar", "/tmp/bytequay-backend.x/bytequay-backend.jar"])
            self.assertEqual(watch.parts().get(11), "backend")
            watch.parts()
        self.assertEqual(reads, [10, 11, 11])  # when first seen and when its program changed, not on every look

    def test_this_process_is_counted_as_the_monitor_with_real_numbers(self):
        watch = monitor.Watch(database=Path("/nowhere/bytequay.db"))
        watch.sample()
        busy = time.perf_counter() + 0.3
        while time.perf_counter() < busy:  # keep one core busy between the two looks
            pass
        totals, machine = watch.sample()
        self.assertEqual(list(totals), list(monitor.PARTS))
        mine = totals["monitor"]
        self.assertEqual(mine["processes"], 1)
        self.assertTrue(mine["memory"] > 0 and mine["threads"] >= 1 and mine["files"] >= 1)
        self.assertTrue(0.3 < mine["cpu"] <= 1.5, mine["cpu"])  # in cores: about one, never a hundred
        self.assertTrue(0 < machine["memory_used"] < machine["memory_total"])
        self.assertEqual((machine["turns"], machine["tasks"], machine["queued"]), (None, None, None))  # no database: not known, and not zero


class TestRecords(Folder):
    def test_running_turns_open_tasks_and_waiting_inputs_are_counted_without_writing(self):
        self.folder.mkdir()
        database = self.folder / "bytequay.db"
        with sqlite3.connect(database) as connection:
            connection.executescript("CREATE TABLE agent_turn (state TEXT); CREATE TABLE agent_task (state TEXT); CREATE TABLE agent_queued_input (state TEXT);")
            connection.executemany("INSERT INTO agent_turn VALUES (?)", [(state,) for state in ("ACCEPTED", "RUNNING", "COMPLETED", "FAILED", "INTERRUPTED")])
            connection.executemany("INSERT INTO agent_task VALUES (?)", [(state,) for state in ("OPEN", "OPEN", "COMPLETED")])
            connection.executemany("INSERT INTO agent_queued_input VALUES (?)", [(state,) for state in ("QUEUED", "PAUSED", "STARTING", "STARTED", "REMOVED")])
        connection.close()
        before = database.stat().st_mtime_ns
        self.assertEqual(monitor.records(database), {"turns": 2, "tasks": 2, "queued": 3})
        self.assertEqual(database.stat().st_mtime_ns, before)

    @unittest.skipUnless(BACKEND.is_dir(), "the application's source is not beside the lab")
    def test_the_states_counted_are_the_ones_the_application_counts_as_running_and_waiting(self):
        def states(source, method):
            body = re.search(rf"boolean {method}\(\)\s*\{{(.*?)\}}", (BACKEND / source).read_text(), re.S)[1]
            return tuple(re.findall(r"this == (\w+)", body))
        self.assertEqual((states("TurnState.java", "active"), states("QueuedAgentInput.java", "live")), (monitor.ACTIVE_TURNS, monitor.LIVE_INPUTS))


class TestStore(Folder):
    def numbers(self, memory, cpu):
        return {part: {"processes": 1, "memory": memory, "cpu": cpu, "threads": 10, "files": 4} for part in monitor.PARTS}

    def test_samples_are_kept_and_those_older_than_a_week_are_folded_into_hours(self):
        connection = monitor.store(self.folder)
        self.addCleanup(connection.close)
        machine = {"memory_used": 4, "memory_total": 8, "cpu": 1.5, "turns": 1, "tasks": 2, "queued": None}
        now = 100 * 86400.0
        old = now - 8 * 86400  # a whole hour that is more than a week old
        hour = old // 3600 * 3600
        for moment, memory, cpu in ((hour + 10, 100, 0.5), (hour + 20, 300, 1.5), (hour + 3610, 50, 0.1), (now - 60, 7, 0.2)):
            monitor.keep(connection, moment, self.numbers(memory, cpu), machine)
        monitor.keep(connection, now - 91 * 86400, self.numbers(1, 0), machine)  # older than anything is kept
        monitor.fold(connection, now)
        rows = connection.execute("SELECT at, seconds, memory, memory_peak, cpu, cpu_peak FROM parts WHERE part = 'app' ORDER BY at").fetchall()
        self.assertEqual(rows, [(hour, 3600, 200, 300, 1.0, 1.5), (hour + 3600, 3600, 50, 50, 0.1, 0.1), (now - 60, 5, 7, 7, 0.2, 0.2)])
        self.assertEqual(connection.execute("SELECT at, seconds, memory_used, cpu, turns, queued FROM machine ORDER BY at").fetchall(),
                         [(hour, 3600, 4, 1.5, 1, None), (hour + 3600, 3600, 4, 1.5, 1, None), (now - 60, 5, 4, 1.5, 1, None)])
        monitor.fold(connection, now)  # folding again changes nothing
        self.assertEqual(connection.execute("SELECT COUNT(*) FROM parts WHERE part = 'app'").fetchone(), (3,))

    def test_sampler_keeps_every_sample_and_counts_the_disk_apart(self):
        watch = mock.Mock(sample=mock.Mock(return_value=(self.numbers(10, 0.25), {"memory_used": 1, "memory_total": 2, "cpu": 0.5, "turns": 0, "tasks": 3, "queued": 0})))
        with mock.patch.object(monitor.threading, "Thread") as counting, mock.patch.object(monitor, "EVERY", 0):
            monitor.run(self.folder, watch, rounds=3)
        connection = monitor.store(self.folder)
        self.addCleanup(connection.close)
        self.assertEqual((connection.execute("SELECT COUNT(*), SUM(memory) FROM parts").fetchone(), connection.execute("SELECT COUNT(*), SUM(tasks) FROM machine").fetchone()),
                         ((3 * len(monitor.PARTS), 30 * len(monitor.PARTS)), (3, 9)))
        self.assertEqual(watch.sample.call_count, 4)  # one look first that is not kept: it reads every process as idle
        # Counted once, at the start, apart from the samples: a count takes minutes.
        self.assertEqual([(call.kwargs["target"], call.kwargs["args"][0]) for call in counting.call_args_list], [(monitor.count_disk, self.folder)])

    def test_disk_is_counted_for_every_top_level_part_of_the_apps_data(self):
        data = self.folder / "data"
        (data / "worktrees" / "one").mkdir(parents=True)
        (data / "worktrees" / "one" / "big.bin").write_bytes(b"x" * 200_000)
        (data / "bytequay.db").write_bytes(b"x" * 50_000)
        sizes = monitor.disk(data)
        self.assertEqual(sorted(sizes), ["bytequay.db", "worktrees"])
        self.assertTrue(sizes["worktrees"] >= 200_000 and sizes["bytequay.db"] >= 50_000)
        self.assertEqual(monitor.disk(self.folder / "missing"), {})
        monitor.count_disk(self.folder, 5.0, data)
        connection = monitor.store(self.folder)
        self.addCleanup(connection.close)
        self.assertEqual(connection.execute("SELECT at, place FROM disk ORDER BY place").fetchall(), [(5.0, "bytequay.db"), (5.0, "worktrees")])


class TestCommandLine(Folder):
    def setUp(self):
        super().setUp()
        tools = self.folder.parent / "tools"
        tools.mkdir()
        (tools / "launchctl").write_text(TOOLS.format(python=sys.executable))
        (tools / "launchctl").chmod(0o755)
        self.calls = self.folder.parent / "calls.jsonl"
        environment = mock.patch.dict(os.environ, {"PATH": f"{tools}{os.pathsep}{os.environ['PATH']}", "TOOL_CALLS": str(self.calls), "HOME": str(self.folder.parent / "home")})
        environment.start()
        self.addCleanup(environment.stop)
        self.agent = self.folder.parent / "home" / "Library" / "LaunchAgents" / "com.bytequay.lab.monitor.plist"

    def command(self, *arguments):
        return subprocess.run([sys.executable, str(SCRIPT), *arguments, "--folder", str(self.folder)], capture_output=True, text=True, stdin=subprocess.DEVNULL)

    def test_start_keeps_the_sampler_running_and_stop_removes_it(self):
        started = self.command("--start")
        self.assertEqual((started.returncode, started.stdout), (0, f"The monitor samples every 5 seconds into {self.folder / 'monitor.db'}, from now on.\n"))
        agent = plistlib.loads(self.agent.read_bytes())
        self.assertEqual((agent["ProgramArguments"][1:], agent["RunAtLoad"], agent["KeepAlive"], agent["ProcessType"], agent["StandardOutPath"]),
                         ([str(SCRIPT), "--sample", "--folder", str(self.folder)], True, True, "Background", str(self.folder / "monitor.log")))
        stopped = self.command("--stop")
        self.assertEqual((stopped.returncode, stopped.stdout, self.agent.exists()), (0, "The monitor is stopped.\n", False))
        domain = f"gui/{os.getuid()}"
        self.assertEqual([line for line in self.calls.read_text().splitlines()],
                         [f'["launchctl", "bootout", "{domain}/com.bytequay.lab.monitor"]', f'["launchctl", "bootstrap", "{domain}", "{self.agent}"]',
                          f'["launchctl", "bootout", "{domain}/com.bytequay.lab.monitor"]'])

    def test_with_no_option_it_prints_how_the_parts_stand_now(self):
        done = self.command()
        lines = done.stdout.splitlines()
        self.assertEqual((done.returncode, lines[0].split(), [line.split()[0] for line in lines[1:len(monitor.PARTS) + 1]]),
                         (0, ["part", "processes", "memory", "MB", "CPU", "cores", "threads", "open", "files"], list(monitor.PARTS)))
        self.assertTrue(lines[-1].startswith("Turns running "))


if __name__ == "__main__":
    unittest.main()
