from array import array
from datetime import datetime, timezone
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
JDK26 = """44545:
garbage-first heap   total reserved 2048000K, committed 1778688K, used 761379K [0x0000000783000000, 0x0000000800000000)
 region size 1M, 280 eden (280M), 58 survivor (58M), 241 old (241M), 173 humongous (173M), 985 free (985M)
 Metaspace       used 180000K, committed 182000K, reserved 1245184K
"""
BACKEND = Path(__file__).resolve().parents[2] / "backend/agent-core/src/main/java/com/bytequay/agent/core"
ENTITIES = Path(__file__).resolve().parents[2] / "backend/persistence-sqlite/src/main/java/com/bytequay/persistence/sqlite/entity"
OPEN_TASK, IDLE_TASK, DONE_TASK, GONE_TASK = (f"{digit * 8}-{digit * 4}-{digit * 4}-{digit * 4}-{digit * 12}" for digit in "1234")
NOW = 1_800_000_000.0
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
        # dev.sh starts the backend as a copy of itself that copies the JAR, then turns into Java with the same pid.
        table = {10: (1, "/bin/sh", ["sh", "dev.sh"]), 11: (10, "/bin/sh", ["sh", "dev.sh"])}
        reads = []

        def program(pid):
            reads.append(pid)
            return table[pid][1:]
        cached = []  # psutil caches a process's executable and creation time until its iterator cache is cleared.

        def listed(attributes):
            if not cached:
                cached.extend(mock.Mock(pid=pid, info={"ppid": parent, "exe": executable, "create_time": 100.0}) for pid, (parent, executable, _) in table.items())
            return cached

        watch = monitor.Watch(database=Path("/nowhere/bytequay.db"))
        with mock.patch.object(monitor.psutil, "process_iter", side_effect=listed) as inventory, mock.patch.object(monitor, "program", program):
            inventory.cache_clear.side_effect = cached.clear
            self.assertNotIn("backend", watch.parts().values())
            table[11] = (10, "/usr/bin/java", ["java", "-jar", "/tmp/bytequay-backend.x/bytequay-backend.jar"])
            self.assertEqual(watch.parts().get(11), "backend")
            watch.parts()
        self.assertEqual(reads, [10, 11, 11])  # when first seen and when its program changed, not on every look

    def test_detached_descendants_stay_visible_until_the_process_identity_exits(self):
        table = {100: (1, "/jdk/bin/java", ["java", "-jar", "bytequay-backend.jar"], 10.0),
                 101: (100, "/bin/sh", ["sh"], 11.0), 102: (101, "/bin/sleep", ["sleep", "30"], 12.0)}
        listed = lambda attributes: [mock.Mock(pid=pid, info={"ppid": parent, "exe": executable, "create_time": created})
                                     for pid, (parent, executable, _, created) in table.items()]
        watch = monitor.Watch(database=Path("/nowhere/bytequay.db"))
        with mock.patch.object(monitor.psutil, "process_iter", side_effect=listed), mock.patch.object(monitor, "program", side_effect=lambda pid: table[pid][1:3]):
            self.assertEqual(watch.parts()[102], "commands")
            session = watch.sessions["backend_session"]
            del table[101]
            table[102] = (1, *table[102][1:])
            self.assertEqual(watch.parts()[102], "orphaned")
            table[103] = (102, "/bin/sleep", ["sleep", "30"], 13.0)
            self.assertEqual(watch.parts()[103], "orphaned")
            watch.counted[102] = object()
            # The OS reuses the PID for the same executable; it is no longer ByteQuay's process.
            table[102] = (*table[102][:3], 20.0)
            del table[103]
            self.assertNotIn(102, watch.parts())
            self.assertNotIn(102, watch.counted)
            self.assertEqual(watch.sessions["backend_session"], session)
            table[100] = (*table[100][:3], 21.0)
            watch.parts()
            self.assertNotEqual(watch.sessions["backend_session"], session)

    def test_processes_with_unknown_identity_do_not_get_a_session_or_survive_by_pid(self):
        table = {100: (1, "/jdk/bin/java", ["java", "-jar", "bytequay-backend.jar"]), 101: (100, "/bin/sleep", ["sleep", "30"])}
        listed = lambda attributes: [mock.Mock(pid=pid, info={"ppid": parent, "exe": executable, "create_time": None})
                                     for pid, (parent, executable, _) in table.items()]
        watch = monitor.Watch(database=Path("/nowhere/bytequay.db"))
        with mock.patch.object(monitor.psutil, "process_iter", side_effect=listed), mock.patch.object(monitor, "program", side_effect=lambda pid: table[pid][1:]):
            self.assertEqual(watch.parts()[101], "commands")
            self.assertIsNone(watch.sessions["backend_session"])
            table[101] = (1, *table[101][1:])
            self.assertNotIn(101, watch.parts())

    def test_a_process_the_monitor_starts_is_its_own_cost(self):
        child = subprocess.Popen(["sleep", "30"])
        self.addCleanup(child.wait)
        self.addCleanup(child.kill)
        self.assertEqual(monitor.Watch(database=Path("/nowhere/bytequay.db")).parts().get(child.pid), "monitor")  # as its disk count runs

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
        self.assertTrue(0 < mine["cpu"] <= 1.5, mine["cpu"])  # competing builds can leave this process only a small share of a core
        self.assertTrue(0 < machine["memory_used"] < machine["memory_total"])
        self.assertEqual((machine["turns"], machine["tasks"], machine["queued"]), (None, None, None))  # no database: not known, and not zero

    def test_cpu_percent_is_reported_as_cores(self):
        watch = monitor.Watch()
        process = mock.MagicMock()
        process.memory_info.return_value.rss = 1024
        process.cpu_percent.return_value = 125.0
        process.num_threads.return_value, process.num_fds.return_value = 10, 4
        with mock.patch.object(watch, "parts", return_value={123: "backend"}), mock.patch.object(monitor.psutil, "Process", return_value=process), \
                mock.patch.object(monitor.psutil, "virtual_memory", return_value=mock.Mock(total=8192, available=2048)), \
                mock.patch.object(monitor.psutil, "cpu_percent", return_value=25.0), mock.patch.object(monitor.psutil, "cpu_count", return_value=8), \
                mock.patch.object(monitor, "records", return_value={"turns": 0, "tasks": 0, "queued": 0}):
            totals, machine = watch.sample()
        self.assertEqual(totals["backend"]["cpu"], 1.25)
        self.assertEqual(machine["cpu"], 2.0)


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
        machine = {"memory_used": 4, "memory_total": 8, "cpu": 1.5, "turns": 1, "tasks": 2, "queued": None, "app_session": "11:12.0", "backend_session": "13:14.0"}
        now = 100 * 86400.0
        old = now - 8 * 86400  # a whole hour that is more than a week old
        hour = old // 3600 * 3600
        for moment, memory, cpu in ((hour + 10, 100, 0.5), (hour + 20, 300, 1.5), (hour + 3610, 50, 0.1), (now - 60, 7, 0.2)):
            monitor.keep(connection, moment, self.numbers(memory, cpu), machine)
        monitor.keep(connection, now - 91 * 86400, self.numbers(1, 0), machine)  # older than anything is kept
        connection.executemany("INSERT INTO heap VALUES (?, ?, ?, ?, ?)", [(now - 91 * 86400, 1, 2, 3, 4), (now - 60, 5, 6, 7, 8)])
        connection.executemany("INSERT INTO space VALUES (?, ?, ?, ?, ?)", [(now - 91 * 86400, monitor.APP, "Cache", 1, 1), (now - 89 * 86400, monitor.APP, "Cache", 2, 1)])
        monitor.fold(connection, now)
        self.assertEqual(connection.execute("SELECT bytes FROM space").fetchall(), [(2,)])  # a disk count is kept as long as an hourly row
        self.assertEqual(connection.execute("SELECT used FROM heap").fetchall(), [(5,)])  # and so is an ask of the heap
        rows = connection.execute("SELECT at, seconds, memory, memory_peak, cpu, cpu_peak FROM parts WHERE part = 'app' ORDER BY at").fetchall()
        self.assertEqual(rows, [(hour, 3600, 200, 300, 1.0, 1.5), (hour + 3600, 3600, 50, 50, 0.1, 0.1), (now - 60, monitor.EVERY, 7, 7, 0.2, 0.2)])
        self.assertEqual(connection.execute("SELECT at, seconds, memory_used, cpu, turns, queued FROM machine ORDER BY at").fetchall(),
                         [(hour, 3600, 4, 1.5, 1, None), (hour + 3600, 3600, 4, 1.5, 1, None), (now - 60, monitor.EVERY, 4, 1.5, 1, None)])
        self.assertEqual(connection.execute("SELECT * FROM sessions").fetchall(), [(now - 60, "11:12.0", "13:14.0")])
        monitor.fold(connection, now)  # folding again changes nothing
        self.assertEqual(connection.execute("SELECT COUNT(*) FROM parts WHERE part = 'app'").fetchone(), (3,))

    def test_sampler_keeps_every_sample_and_counts_the_disk_apart(self):
        watch = mock.Mock(sample=mock.Mock(return_value=(self.numbers(10, 0.25), {"memory_used": 1, "memory_total": 2, "cpu": 0.5, "turns": 0, "tasks": 3, "queued": 0})),
                          programs={})
        with mock.patch.object(monitor.threading, "Thread") as counting, mock.patch.object(monitor, "EVERY", 0):
            monitor.run(self.folder, watch, rounds=3)
        connection = monitor.store(self.folder)
        self.addCleanup(connection.close)
        self.assertEqual((connection.execute("SELECT COUNT(*), SUM(memory) FROM parts").fetchone(), connection.execute("SELECT COUNT(*), SUM(tasks) FROM machine").fetchone()),
                         ((3 * len(monitor.PARTS), 30 * len(monitor.PARTS)), (3, 9)))
        self.assertEqual(watch.sample.call_count, 4)  # one look first that is not kept: it reads every process as idle
        # Counted and saved once, at the start, apart from the samples: a count takes minutes, and a save reads every task log.
        self.assertEqual([(call.kwargs["target"], call.kwargs["args"][0]) for call in counting.call_args_list],
                         [(monitor.lowest, [("monitor.py", "--count-disk", "--folder", str(self.folder))]),
                          (monitor.lowest, [("activity.py", "--save", str(self.folder)), ("tuning.py", "--save", str(self.folder)),
                                            ("context.py", "--save", str(self.folder)), ("memory.py", "--save", str(self.folder)),
                                            ("advisor.py", "--folder", str(self.folder), "--if-due")])])

    def test_the_backends_jvm_is_asked_for_its_heap_once_a_minute_while_it_runs(self):
        backend = (41, ("/jdk/bin/java", ["/jdk/bin/java", "-Xmx2000m", "-jar", "/b/bytequay-backend.jar"]))
        watch = mock.Mock(sample=mock.Mock(return_value=(self.numbers(10, 0.25), {"memory_used": 1, "memory_total": 2, "cpu": 0.5, "turns": 0, "tasks": 3, "queued": 0})),
                          programs=dict([backend, (42, ("/bin/zsh", ["zsh"]))]))
        with mock.patch.object(monitor.threading, "Thread") as started, mock.patch.object(monitor, "EVERY", 0):
            monitor.run(self.folder, watch, rounds=3)
        self.assertEqual([call.kwargs["args"][2:] for call in started.call_args_list if call.kwargs["target"] is monitor.weigh], [(41, "/jdk/bin/java")])

    def test_the_heap_is_read_from_what_jcmd_prints(self):
        self.assertEqual(monitor.read_heap(JDK26), {"used": 761379 * 2**10, "committed": 1778688 * 2**10, "most": 2048000 * 2**10, "old": (241 + 173) * 2**20})
        older = "1:\n garbage-first heap   total 1778688K, used 761379K [0x0, 0x0)\n  region size 1024K, 280 young (286720K), 58 survivors (59392K)\n"
        self.assertEqual(monitor.read_heap(older), {"used": 761379 * 2**10, "committed": 1778688 * 2**10, "most": None, "old": None})
        self.assertIsNone(monitor.read_heap("41: Unable to open socket file: target process not responding or HotSpot VM not loaded"))

    def test_an_ask_of_the_heap_keeps_a_row_and_none_when_the_jvm_does_not_answer(self):
        answers = [subprocess.CompletedProcess([], 0, JDK26, ""), OSError("no jcmd in this runtime"), subprocess.CompletedProcess([], 1, "", "not responding")]
        with mock.patch.object(monitor.subprocess, "run", side_effect=answers) as ran:
            for moment in (100, 160, 220):
                monitor.weigh(self.folder, moment, 41, "/jdk/bin/java")
        self.assertEqual(ran.call_args_list[0].args[0], ["nice", "-n", "20", "/jdk/bin/jcmd", "41", "GC.heap_info"])
        connection = monitor.store(self.folder)
        self.addCleanup(connection.close)
        self.assertEqual(connection.execute("SELECT at, used FROM heap").fetchall(), [(100, 761379 * 2**10)])

    def test_lab_scripts_run_one_after_another_at_the_lowest_priority(self):
        with mock.patch.object(monitor.subprocess, "run") as ran:
            monitor.lowest([("activity.py", "--save", "lab"), ("tuning.py", "--save", "lab")])
        self.assertEqual(ran.call_args_list, [mock.call(["nice", "-n", "20", sys.executable, str(SCRIPT.with_name(name)), "--save", "lab"], stdout=subprocess.DEVNULL)
                                              for name in ("activity.py", "tuning.py")])


class TestSpace(Folder):
    KNOWN = ({OPEN_TASK: monitor.OPEN, IDLE_TASK: monitor.IDLE, DONE_TASK: monitor.FINISHED},
             {"open-tree": monitor.OPEN, "rebase-tree": monitor.APP})

    def test_who_holds_a_path_follows_the_database_and_the_retired_features(self):
        cases = {
            ("agent-runtime",): None, ("agent-runtime", OPEN_TASK): None, ("agent-runtime", OPEN_TASK, "events.jsonl"): monitor.OPEN,
            ("agent-runtime", OPEN_TASK, "codegraph"): monitor.RETIRED, ("agent-runtime", GONE_TASK, "blobs"): monitor.REMOVED,
            ("agent-runtime", "ide"): None, ("agent-runtime", "ide", DONE_TASK): monitor.FINISHED, ("agent-runtime", "ide", IDLE_TASK): monitor.IDLE,
            ("agent-runtime", "codegraph-baselines"): monitor.RETIRED, ("agent-runtime", "_processes"): monitor.APP,
            ("workspaces", "w"): None, ("workspaces", "w", "repository"): monitor.REPOSITORIES, ("workspaces", "w", "worktrees"): None,
            ("workspaces", "w", "worktrees", "open-tree"): monitor.OPEN, ("workspaces", "w", "worktrees", "rebase-tree"): monitor.APP,
            ("workspaces", "w", "worktrees", "gone-tree"): monitor.REMOVED, ("workspaces", "w", "lock"): monitor.APP,
            ("repos",): monitor.RETIRED, ("new-flow-worktrees",): monitor.RETIRED, ("database-backup-20260921T172146",): monitor.COPIES,
            ("backup-before-local-run-20260916T181723",): monitor.COPIES, ("bytequay.db",): monitor.COPIES, ("bytequay-1.0.db",): monitor.APP,
            ("Cache",): monitor.APP,
        }
        self.assertEqual({names: monitor.owner(names, self.KNOWN) for names in cases}, cases)
        # With no database, nothing is said about whose task data is whose; what the path alone tells still holds.
        self.assertEqual([monitor.owner(names, None) for names in [("agent-runtime", OPEN_TASK, "events.jsonl"), ("workspaces", "w", "worktrees", "open-tree"),
                                                                   ("agent-runtime", OPEN_TASK, "codegraph"), ("workspaces", "w", "repository")]],
                         [monitor.NOT_KNOWN, monitor.NOT_KNOWN, monitor.RETIRED, monitor.REPOSITORIES])

    def test_owners_are_read_from_the_database_without_writing(self):
        self.folder.mkdir()
        database = self.folder / "bytequay.db"
        stamp = lambda days: datetime.fromtimestamp(NOW - days * 86400, timezone.utc).isoformat().replace("+00:00", "Z")
        with sqlite3.connect(database) as connection:
            connection.executescript("CREATE TABLE agent_task (id TEXT, state TEXT, updated_at TEXT, worktree TEXT); CREATE TABLE branch_rebase (worktree TEXT);"
                                     "CREATE TABLE cherry_pick_execution (worktree TEXT);")
            connection.executemany("INSERT INTO agent_task VALUES (?, ?, ?, ?)", [(OPEN_TASK, "OPEN", stamp(6.9), "/data/workspaces/w/worktrees/open-tree"),
                                                                                  (IDLE_TASK, "OPEN", stamp(7.1), None),
                                                                                  (DONE_TASK, "COMPLETED", stamp(30), "/data/workspaces/w/worktrees/done-tree")])
            connection.execute("INSERT INTO branch_rebase VALUES ('/data/workspaces/w/worktrees/rebase-tree')")
        connection.close()
        before = database.stat().st_mtime_ns
        self.assertEqual(monitor.holders(database, NOW), ({OPEN_TASK: monitor.OPEN, IDLE_TASK: monitor.IDLE, DONE_TASK: monitor.FINISHED},
                                                          {"open-tree": monitor.OPEN, "done-tree": monitor.FINISHED, "rebase-tree": monitor.APP}))
        self.assertEqual(database.stat().st_mtime_ns, before)
        self.assertIsNone(monitor.holders(self.folder / "missing.db", NOW))

    @unittest.skipUnless(ENTITIES.is_dir(), "the application's source is not beside the lab")
    def test_every_table_whose_rows_name_a_worktree_is_read(self):
        sources = [path.read_text() for path in ENTITIES.glob("*Entity.java")]
        named = {re.search(r'@Table\(name = "(\w+)"', source)[1] for source in sources if "private String worktree;" in source}
        self.assertEqual(named, {"agent_task", *monitor.WORKTREES})
        task = (ENTITIES / "AgentTaskEntity.java").read_text()
        for field in ("id", "state", "updatedAt", "worktree"):
            with self.subTest(field=field):
                self.assertRegex(task, rf"private [\w<>.]+ {field};")

    def test_a_block_is_counted_once_for_the_owner_that_holds_it(self):
        held = monitor.shares(array("q", [0, 50, 200, 250, 400, 450]), array("q", [100, 100, 100, 100, 100, 10]), array("H", [0, 1, 2, 2, 0, 2]),
                              {(monitor.OPEN, "w"): 0, (monitor.REMOVED, "w"): 1, (monitor.RETIRED, "a"): 2})
        self.assertEqual(dict(held), {(monitor.OPEN, "w"): 140, (monitor.REMOVED, "w"): 50, (monitor.RETIRED, "a"): 150,  # its own two overlap: once
                                      (monitor.SHARED, "w"): 50, (monitor.SHARED, "several places"): 10})
        # Two owners' stretches end to end on the disk share no byte, whichever edge the sweep meets first.
        for order in ([0, 1], [1, 0]):
            touching = monitor.shares(array("q", [[0, 100][place] for place in order]), array("q", [100, 100]), array("H", order),
                                      {(monitor.OPEN, "w"): 0, (monitor.RETIRED, "a"): 1})
            self.assertEqual(dict(touching), {(monitor.OPEN, "w"): 100, (monitor.RETIRED, "a"): 100})

    def write(self, path, size):
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "wb") as made:
            made.write(os.urandom(size))
            os.fsync(made.fileno())  # so the file system gives it its blocks now

    def clone(self, source, target):
        target.parent.mkdir(parents=True, exist_ok=True)
        if subprocess.run(["cp", "-c", str(source), str(target)], capture_output=True).returncode:
            self.skipTest("this file system makes no copy-on-write copies")

    def test_the_workspaces_outside_the_app_data_count_as_its_workspaces_folder(self):
        data, workspaces = self.folder / "data", self.folder / "home/.bytequay/workspaces"
        self.write(data / "Cache" / "entry", 100)
        self.write(workspaces / "w" / "worktrees" / "open-tree" / "file", 100)
        with mock.patch.object(monitor, "APP_DATA", data), mock.patch.object(monitor, "WORKSPACES", workspaces):
            rows = {(owner, place): files for owner, place, _, files in monitor.space(data, self.KNOWN)}
            self.assertEqual(monitor.names(workspaces / "w" / "repository", data), ("workspaces", "w", "repository"))
        self.assertEqual(rows, {(monitor.APP, "Cache"): 1, (monitor.OPEN, "workspaces"): 1})
        # Another data folder, as a test makes, keeps its workspaces inside.
        self.assertEqual(monitor.tops(data), [(data, ())])

    def test_disk_is_counted_by_owner_with_each_copied_block_once(self):
        data = self.folder / "data"
        self.write(data / "agent-runtime" / OPEN_TASK / "events.jsonl", 1000)
        self.write(data / "agent-runtime" / OPEN_TASK / "codegraph" / "codegraph.db", 2**20)
        self.clone(data / "agent-runtime" / OPEN_TASK / "codegraph" / "codegraph.db", data / "agent-runtime" / "codegraph-baselines" / "r" / "codegraph.db")
        self.write(data / "workspaces" / "w" / "worktrees" / "open-tree" / "big.bin", 2**19)
        self.clone(data / "workspaces" / "w" / "worktrees" / "open-tree" / "big.bin", data / "workspaces" / "w" / "worktrees" / "gone-tree" / "big.bin")
        self.write(data / "workspaces" / "w" / "repository" / "object", 3000)
        os.link(data / "workspaces" / "w" / "repository" / "object", data / "workspaces" / "w" / "repository" / "same-object")
        self.write(data / "Cache" / "entry", 100)
        rows = {(owner, place): (size, files) for owner, place, size, files in monitor.space(data, self.KNOWN)}
        block = (data / "Cache" / "entry").stat().st_blocks * 512
        self.assertEqual(rows, {
            (monitor.OPEN, "agent-runtime"): (block, 1),
            (monitor.RETIRED, "agent-runtime"): (2**20, 2),  # the index and its copy hold one megabyte between them
            (monitor.OPEN, "workspaces"): (0, 1), (monitor.REMOVED, "workspaces"): (0, 1), (monitor.SHARED, "workspaces"): (2**19, 0),
            (monitor.REPOSITORIES, "workspaces"): (block, 1),  # two names, one file
            (monitor.APP, "Cache"): (block, 1)})
        # Without the database the task data is not known, and the two copies of one task file are then one owner's: counted once.
        monitor.count_disk(self.folder, 5.0, data, self.folder / "missing.db")
        connection = monitor.store(self.folder)
        self.addCleanup(connection.close)
        self.assertEqual(connection.execute("SELECT DISTINCT at FROM space").fetchall(), [(5.0,)])
        self.assertEqual(dict(connection.execute("SELECT owner, SUM(files) FROM space GROUP BY owner")),
                         {monitor.RETIRED: 2, monitor.REPOSITORIES: 1, monitor.APP: 1, monitor.NOT_KNOWN: 3})
        self.assertEqual(connection.execute("SELECT SUM(bytes) FROM space WHERE owner = ? AND place = 'workspaces'", (monitor.NOT_KNOWN,)).fetchone(), (2**19,))


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
        self.assertEqual((started.returncode, started.stdout), (0, f"The monitor samples every 15 seconds into {self.folder / 'monitor.db'}, from now on.\n"))
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
