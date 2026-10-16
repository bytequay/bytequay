import http.server
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

import advisor
import agent
import cleanup
import monitor
import resources
from test_agent import Script, Stand, call, completion, said
from test_batch import TOOLS

SCRIPT = Path(advisor.__file__).resolve()
GB, MB = resources.GB, resources.MB
START = 1_800_000_000.0  # on the hour
ANSWER = {"summary": "The disk is nearly full, and 3 GB is data nothing uses.", "suggestions": [
    {"title": "Give the backend more room", "risk": "low", "seen": "It peaked at 1,900 MB of a 2,000 MB heap limit.", "do": "Raise -Xmx.",
     "where": "dev.sh JVM_ARGS", "watch": "The backend's peak memory", "action": "none"},
    {"title": "Clean up what nothing uses", "risk": "high", "seen": "Retired features hold 3.0 GB.", "do": "Delete it.", "where": "The Clean up section",
     "watch": "Could be freed", "action": "clean_up"}]}


def numbers(backend=1000, commands=0):
    """One sample of every part: the backend at some MB, and the commands an agent runs when there are any."""
    made = {part: {"processes": 1, "memory": 100 * MB, "cpu": 0.1, "threads": 10, "files": 5} for part in monitor.PARTS}
    made["backend"] |= {"memory": backend * MB, "cpu": 0.5, "threads": 100}
    made["commands"] |= {"processes": 3, "memory": commands * MB, "cpu": 6.0, "threads": 500, "files": 900} if commands else dict.fromkeys(monitor.AMOUNTS, 0)
    return made


def machine(cpu=2.0, used=40, turns=0):
    return {"memory_used": used * GB, "memory_total": 64 * GB, "cpu": cpu, "turns": turns, "tasks": 5, "queued": 0}


class Kept(unittest.TestCase):
    """Two hours of samples, a build in the first and a turn near the end of the second, two disk counts, and the retired
    code index left in the app's data."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name).resolve()
        self.folder, self.data = root / "lab", root / "data"
        (self.data / "agent-runtime" / "codegraph-baselines").mkdir(parents=True)
        os.utime(self.data / "agent-runtime" / "codegraph-baselines", (time.time() - 2 * 86400,) * 2)  # nothing changed it in the last day
        connection = monitor.store(self.folder)
        for moment, made, whole in ((START + 60, numbers(1000), machine()), (START + 120, numbers(1500, commands=4000), machine(cpu=12.0, turns=2)),
                                    (START + 180, numbers(1000), machine(cpu=11.0)), (START + 3660, numbers(1100), machine()),
                                    (START + 5400, numbers(1900), machine(cpu=15.5, used=60, turns=1)), (START + 7260, numbers(1200), machine())):
            monitor.keep(connection, moment, made, whole)
        connection.executemany("INSERT INTO space VALUES (?, ?, ?, ?, ?)", [(START, monitor.OPEN, "workspaces", 5 * GB, 10), (START, monitor.RETIRED, "agent-runtime", 2 * GB, 3),
                                                                            (START + 7200, monitor.OPEN, "workspaces", 6 * GB, 12), (START + 7200, monitor.RETIRED, "agent-runtime", 3 * GB, 3)])
        connection.commit()
        connection.close()

    def sight(self, **changed):
        made = resources.read(self.folder, 0)
        going, _ = cleanup.plan(self.data, None, time.time())
        return advisor.Seen(**{"parts": made["parts"], "machine": made["machine"], "space": made["space"], "heap": 2000.0, "exits": True,
                               "disk": (50 * GB, 500 * GB), "cores": 16, "going": going, "data": self.data} | changed)


class TestTools(Kept):
    def test_the_overview_gives_every_part_the_mac_and_the_limits(self):
        said_ = advisor.overview(self.sight(), {})
        for line in ("This Mac: 64 GB of memory and 16 CPU cores. Memory in use: typically 40.0 GB, at most 60.0 GB. CPU: typically 6.5 cores busy, "
                     "14.6 or fewer 95% of the time, at most 15.5.",
                     "Agent turns running: at most 2; one or more in 33% of the samples.",
                     "  Java backend: memory 1,150 / 1,900 / 1,200; CPU 0.50 / 0.50; threads 100 / 100; open files 5; processes 1",
                     "  commands they run: memory 4,000 / 4,000 / not running now; CPU 6.00 / 6.00; threads 500 / 500; open files 900; processes 3",
                     "Limits: the backend's heap may grow to 2,000 MB, and the backend stops when it runs out; the disk the app's data is on has 50 GB free "
                     "of 500 GB (90% used)."):
            with self.subTest(line=line[:40]):
                self.assertIn(line, said_)
        self.assertIn("the backend's heap limit is not known", advisor.overview(self.sight(heap=None, exits=False), {}))
        self.assertTrue(said_.startswith(f"Samples from {advisor.local(START + 60)} to {advisor.local(START + 7260)}, 2.0 hours.\n"))  # no gap of an hour

    def keep_later(self, moment, made, whole, seconds=monitor.EVERY):
        connection = monitor.store(self.folder)
        connection.executemany("INSERT INTO parts VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", [(moment, seconds, part, one["processes"], one["memory"], one["memory"] * (2 if seconds == 3600 else 1),
                                                                                          one["cpu"], one["cpu"], one["threads"], one["files"]) for part, one in made.items()])
        connection.execute("INSERT INTO machine VALUES (?, ?, ?, ?, ?, ?, ?, ?)", (moment, seconds, *whole.values()))
        connection.commit()
        connection.close()

    def test_a_gap_of_more_than_an_hour_is_said(self):
        self.keep_later(START + 7260 + 2 * 3600, numbers(1200), machine())
        self.assertIn(", with 1 gap of more than an hour (the Mac asleep or the monitor stopped).", advisor.overview(self.sight(), {}))

    def test_an_hour_folded_into_one_row_keeps_its_peak(self):
        self.keep_later(START + 3 * 3600, numbers(1000), machine(), seconds=3600)  # its peak is twice its average
        self.assertEqual(advisor.series(self.sight(), {"part": "backend", "amount": "memory", "hours": 4}).splitlines()[-1],
                         f"  {advisor.local(START + 3 * 3600)}  typical 1,000 MB  peak 2,000 MB")

    def test_a_series_is_typical_and_peak_per_hour_and_can_leave_out_the_busy_samples(self):
        hours = [advisor.local(START + hour * 3600) for hour in range(3)]
        self.assertEqual(advisor.series(self.sight(), {"part": "backend", "amount": "memory", "hours": 3}).splitlines()[1:],
                         [f"  {hours[0]}  typical 1,000 MB  peak 1,500 MB", f"  {hours[1]}  typical 1,500 MB  peak 1,900 MB", f"  {hours[2]}  typical 1,200 MB  peak 1,200 MB"])
        self.assertEqual(advisor.series(self.sight(), {"part": "backend", "amount": "memory", "hours": 3, "idle_only": True}).splitlines()[1:],
                         [f"  {hours[0]}  typical 1,000 MB  peak 1,000 MB", f"  {hours[1]}  typical 1,100 MB  peak 1,100 MB", f"  {hours[2]}  typical 1,200 MB  peak 1,200 MB"])
        self.assertEqual(advisor.series(self.sight(), {"part": "machine", "amount": "cpu", "hours": 3}).splitlines()[1],
                         f"  {hours[0]}  typical 11.0 cores  peak 12.0 cores")
        self.assertEqual(advisor.series(self.sight(), {"part": "commands", "amount": "memory", "hours": 3, "idle_only": True}),
                         "No samples of the memory of commands they run while no agent turn ran in the last 3 hours.")
        for arguments, wrong in (({"part": "disk", "amount": "memory"}, "part must be machine or one of"), ({"part": "machine", "amount": "threads"}, "amount of the machine"),
                                 ({"part": "backend", "amount": "turns"}, "amount of a part"), ({"part": "backend", "amount": "memory", "hours": 0}, "hours must be"),
                                 ({"part": "backend", "amount": "memory", "idle_only": "yes"}, "idle_only must be")):
            with self.subTest(arguments=arguments), self.assertRaisesRegex(agent.ToolError, wrong):
                advisor.series(self.sight(), arguments)

    def test_the_busiest_moments_are_apart_and_say_what_bytequay_took(self):
        lines = advisor.peaks(self.sight(), {"amount": "cpu", "top": 3}).splitlines()
        self.assertEqual([line.split(": the Mac ")[0].strip() for line in lines[1:]], [advisor.local(START + moment) for moment in (5400, 120, 3660)])  # 11 cores came a minute after 12
        self.assertEqual(lines[1], f"  {advisor.local(START + 5400)}: the Mac 15.5 cores; ByteQuay 1.10 cores, most of it Java backend 0.50 cores, "
                                   "app, main process 0.10 cores, app windows 0.10 cores; 1 agent turns running")
        self.assertIn("ByteQuay 7.10 cores, most of it commands they run 6.00 cores", lines[2])
        for arguments, wrong in (({"amount": "disk"}, "amount must be"), ({"amount": "cpu", "top": 11}, "top must be")):
            with self.subTest(arguments=arguments), self.assertRaisesRegex(agent.ToolError, wrong):
                advisor.peaks(self.sight(), arguments)

    def test_the_disk_says_who_holds_what_how_it_changed_and_what_a_clean_up_would_delete(self):
        lines = advisor.disk(self.sight(), {}).splitlines()
        self.assertEqual(lines, ["The disk the app's data is on: 50 GB free of 500 GB (90% used).",
                                 f"Who holds the app's data in GB, counted {advisor.local(START + 7200)}, and the change since {advisor.local(START)}:",
                                 "  open tasks: 6.0 (+1.0)", "  retired features: 3.0 (+1.0)",
                                 "The Clean up section would delete 1 folder or file now: retired features in agent-runtime: 1."])
        bare = advisor.disk(self.sight(going=[], space=self.sight().space.iloc[:0]), {}).splitlines()
        self.assertEqual(bare[1:], ["The app's data has not been counted yet.", "The Clean up section has nothing to delete now."])
        copies = advisor.disk(self.sight(copies=(cleanup.Item(monitor.COPIES, self.data / "database-backup-1"),)), {}).splitlines()
        self.assertEqual(copies[-1], "Old database copies: 1 folder or file, 0.0 GB at the last count; Clean up deletes them too when its box is ticked.")


class TestAnswer(unittest.TestCase):
    def test_a_handed_in_answer_is_checked_before_it_counts(self):
        self.assertIsNone(advisor.problem_of(ANSWER, cleanable=True))
        first = ANSWER["suggestions"][0]
        for answer, wrong in (({"summary": "x"}, "exactly summary and suggestions"), ({**ANSWER, "summary": " "}, "summary must be"),
                              ({**ANSWER, "suggestions": [first] * 9}, "at most 8"), ({**ANSWER, "suggestions": [{**first, "risk": "grave"}]}, "must have a risk of"),
                              ({**ANSWER, "suggestions": [{**first, "seen": "It is high."}]}, "must give the numbers"),
                              ({**ANSWER, "suggestions": [{**first, "do": 3}]}, "must be text"), ({**ANSWER, "suggestions": [{**first, "extra": "x"}]}, "must have exactly title")):
            with self.subTest(wrong=wrong):
                self.assertIn(wrong, advisor.problem_of(answer, cleanable=True))
        self.assertIn("nothing to delete now", advisor.problem_of(ANSWER, cleanable=False))


class TestLook(Kept):
    def look(self, script, clock=time.time):
        with mock.patch.object(advisor, "heap", return_value=(2000.0, True)):
            return advisor.look(self.folder, script, "deepseek-v4-pro", self.data, self.data / "missing.db", clock)

    def test_a_look_reads_with_tools_and_keeps_its_answer_the_gravest_first(self):
        script = Script(said(call("c1", "overview", {})), said(content="The backend looks fine."), said(call("c2", "submit_advice", {**ANSWER, "summary": ""})),
                        said(call("c3", "submit_advice", ANSWER)))
        made = self.look(script)
        self.assertEqual((made["state"], [one["risk"] for one in made["suggestions"]], made["steps"], made["usage"]["inputTokens"]), ("done", ["high", "low"], 4, 4000))
        self.assertEqual(script.sent[2][-1], {"role": "user", "content": advisor.NUDGE})  # a reply with no tool call is asked to go on
        del script.sent[2]
        self.assertTrue(made["overview"].startswith("Samples from "))
        self.assertEqual(json.loads((self.folder / "advice" / advisor.STATE).read_text()), made)
        self.assertEqual(script.sent[2][-1]["content"], "error: summary must be one or two sentences. Call submit_advice again with the whole advice.")
        self.assertIn("Java backend: memory 1,150 / 1,900", script.sent[1][-1]["content"])  # what the overview handed the model
        events = [json.loads(line)["type"] for line in (self.folder / "advice" / "events.jsonl").read_text().splitlines()]
        self.assertEqual(events, ["model", "tool", "model", "model", "tool", "model", "tool", "result"])

    def test_every_finished_look_is_kept_and_the_next_is_told_what_the_last_one_found(self):
        now = time.time()
        first = self.look(Script(said(call("c1", "submit_advice", ANSWER))), lambda: now)
        self.look(Script(RuntimeError("the API is down")), lambda: now + 1)  # a look that failed is not kept
        script = Script(said(call("c1", "submit_advice", ANSWER)))
        second = self.look(script, lambda: now + 2)
        self.assertEqual(advisor.looks(self.folder), [first, second])
        self.assertEqual(script.sent[0][-1]["content"], advisor.asked(first))
        self.assertTrue(advisor.asked(first).startswith(advisor.PROMPT + "\n\nThe last look, on "))
        self.assertTrue(advisor.asked(first).endswith("".join(f"\n- {one['title']}" for one in first["suggestions"])))  # the gravest first
        self.assertEqual((advisor.asked(None), advisor.asked(first | {"suggestions": []})), (advisor.PROMPT, advisor.PROMPT))

    def test_old_database_copies_alone_are_enough_for_a_clean_up_suggestion(self):
        cleanup.remove(self.data / "agent-runtime")
        (self.data / "database-backup-1").mkdir()
        os.utime(self.data / "database-backup-1", (time.time() - 2 * 86400,) * 2)
        made = self.look(Script(said(call("c1", "submit_advice", ANSWER))))
        self.assertEqual((made["state"], made["suggestions"][0]["action"]), ("done", "clean_up"))

    def test_the_tools_offered_are_the_four_readers_and_the_hand_in(self):
        self.assertEqual([one["function"]["name"] for one in advisor.offered()], ["overview", "series", "peaks", "disk", "submit_advice"])

    def test_a_look_that_cannot_finish_says_why(self):
        made = self.look(Script(RuntimeError("the API is down")))
        self.assertEqual((made["state"], made["problem"]), ("failed", "RuntimeError: the API is down"))
        def stopped(*_):
            raise KeyboardInterrupt  # as Ctrl+C, or the Stop of the Processes page, lands in the middle of a model call
        with self.assertRaises(KeyboardInterrupt):
            self.look(stopped)
        self.assertEqual(json.loads((self.folder / "advice" / advisor.STATE).read_text())["state"], "stopped")
        connection = monitor.store(self.folder)
        connection.executescript("DELETE FROM parts; DELETE FROM machine;")  # the monitor's file is there, with no sample in it
        connection.close()
        for _ in range(2):
            with self.assertRaises(ValueError):
                self.look(Script())
            kept = json.loads((self.folder / "advice" / advisor.STATE).read_text())
            self.assertEqual((kept["state"], kept["problem"]), ("failed", "ValueError: the monitor has kept nothing yet: start it with `uv run monitor.py --start`"))
            (self.folder / "monitor.db").unlink(missing_ok=True)  # and then not even the file


class TestCommandLine(Kept):
    def command(self, environment):
        return subprocess.run([sys.executable, str(SCRIPT), "--folder", str(self.folder), "--data", str(self.data)], capture_output=True, text=True,
                              stdin=subprocess.DEVNULL, env=environment)

    def test_without_a_key_it_says_how_to_store_one(self):
        tools = self.folder.parent / "tools"
        tools.mkdir()
        (tools / "security").write_text(TOOLS.format(python=sys.executable))
        (tools / "security").chmod(0o755)
        environment = {name: value for name, value in os.environ.items() if name not in ("DEEPSEEK_API_KEY", "KEYCHAIN_KEY")}
        environment |= {"PATH": f"{tools}{os.pathsep}{os.environ['PATH']}", "TOOL_CALLS": str(self.folder.parent / "calls.jsonl"), "HOME": str(self.folder.parent)}
        done = self.command(environment)
        self.assertEqual((done.returncode, done.stderr), (1, "The agent needs a DeepSeek key. Store it once with `uv run batch.py --store-key`\n"))

    def test_a_look_through_the_model_api_keeps_what_it_found(self):
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Stand)
        server.requests, server.replies = [], [completion(said(call("c1", "overview", {}))), completion(said(call("c2", "submit_advice", ANSWER)))]
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        done = self.command(dict(os.environ, DEEPSEEK_API_KEY="a-test-key", DEEPSEEK_BASE_URL=f"http://127.0.0.1:{server.server_address[1]}"))
        self.assertEqual((done.returncode, done.stdout.splitlines()[0]), (0, ANSWER["summary"]), done.stderr)
        self.assertEqual([one["function"]["name"] for one in server.requests[0]["tools"]], ["overview", "series", "peaks", "disk", "submit_advice"])
        self.assertEqual(json.loads((self.folder / "advice" / advisor.STATE).read_text())["state"], "done")
        self.assertFalse((self.folder / "advice" / "running.pid").exists())


if __name__ == "__main__":
    unittest.main()
