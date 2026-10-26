import contextlib
from datetime import datetime, timedelta, timezone
import getpass
import io
import json
import os
from pathlib import Path
import plistlib
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

import batch
import saved
from test_run import ANSWER, FAKE

SCRIPT = Path(batch.__file__)
# A stand-in for run.py. Like it, it writes only into a new folder and leaves two source trees behind.
RUN = """import json, os, pathlib, sys, time
given = dict(zip(sys.argv[1::2], sys.argv[2::2]))
output = pathlib.Path(given["--output"])
output.mkdir()
for part in ("input/base", "input/head", "state", "logs"):
    (output / part).mkdir(parents=True)
    (output / part / "kept.bin").write_text("large")
(output / "input" / "change.diff").write_text("the change")
(output / "runtime.json").write_text(json.dumps({"status": "started", "given": given}))
if given["--model"] == "dies":
    sys.exit("the command line is not installed")
time.sleep(float(given["--model"].partition("sleeps-")[2] or 0))
(output / "runtime.json").write_text(json.dumps({"status": "failed" if given["--model"] == "fails" else "completed", "given": given,
                                                  "keyed": os.environ.get("DEEPSEEK_API_KEY") == "a-key"}))
"""
# A stand-in for judge.py: it judges when it is handed a key, and says why when it is not. Its judgement names the case it was given.
JUDGE = """import json, os, pathlib, sys
given = dict(zip(sys.argv[2::2], sys.argv[3::2]))
if os.environ.get("DEEPSEEK_API_KEY") != "a-key":
    sys.exit("Set DEEPSEEK_API_KEY to your DeepSeek API key")
case = json.loads(pathlib.Path(given["--case"]).read_text())
judgement = {"case": case["head"], "leads": 1, "verdicts": [{"commentId": 7, "verdict": "FOUND", "leadIds": ["c1"]}], "errors": [], "usage": {}}
(pathlib.Path(given["--run"]) / "judgement.json").write_text(json.dumps(judgement))
"""
# A stand-in for the Mac's `security` and `launchctl`, so no test reads the real Keychain or loads a real agent. It notes
# every call; `security` finds the judge's key only when KEYCHAIN_KEY holds one.
TOOLS = """#!{python}
import json, os, pathlib, sys
tool = pathlib.Path(sys.argv[0]).name
with open(os.environ["TOOL_CALLS"], "a") as calls:
    calls.write(json.dumps([tool, *sys.argv[1:]]) + "\\n")
if tool == "security" and sys.argv[1] == "find-generic-password":
    if not os.environ.get("KEYCHAIN_KEY"):
        sys.exit(44)  # what security says when the item is not there
    print(os.environ["KEYCHAIN_KEY"] if sys.argv[-1] == "-w" else "attributes, and no key")
if tool == "launchctl" and sys.argv[1] == "bootstrap" and os.environ.get("LAUNCHCTL_FAILS"):
    sys.exit("Bootstrap failed: 5: Input/output error")
"""


class Experiment(unittest.TestCase):
    """A lab folder with two cases of one repository and an experiment over them."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.folder = Path(directory.name).resolve() / "lab"
        tools = self.folder.parent / "tools"
        tools.mkdir()
        for name in ("security", "launchctl"):
            (tools / name).write_text(TOOLS.format(python=sys.executable))
            (tools / name).chmod(0o755)
        self.calls = self.folder.parent / "calls.jsonl"
        stand_ins = mock.patch.dict(os.environ, {"PATH": f"{tools}{os.pathsep}{os.environ['PATH']}", "TOOL_CALLS": str(self.calls), "KEYCHAIN_KEY": "", "DEEPSEEK_API_KEY": ""})
        stand_ins.start()
        self.addCleanup(stand_ins.stop)
        self.clone = self.folder.parent / "clone"
        self.clone.mkdir()
        for case, head in (("pr-1", "h1"), ("pr-2", "h2")):
            kept = self.folder / "cases" / case
            kept.mkdir(parents=True)
            (kept / "case.json").write_text(json.dumps({"repo": "o/n", "number": 1, "base": "b" + head, "head": head, "comments": []}))
            (kept / "context.md").write_text("intent\n")
        self.experiment = self.folder / "experiments" / "first"
        self.experiment.mkdir(parents=True)
        (self.experiment / "rules.md").write_text("the rules\n")
        self.save()

    def save(self, **changed):
        self.plan = {"version": 1, "repeats": 2, "timeout": 30, "policy": "rules.md", "clones": {"o/n": str(self.clone)}, "cases": ["pr-1", "pr-2"],
                     "settings": {"quick": {"provider": "codex", "model": "small", "effort": "low"}, "deep": {"provider": "claude", "model": "large", "effort": "max"}}, **changed}
        (self.experiment / "plan.json").write_text(json.dumps(self.plan))

    def stand_in(self, name, source):
        script = self.folder.parent / name
        script.write_text(source)
        patched = mock.patch.object(batch, name.upper(), script)
        patched.start()
        self.addCleanup(patched.stop)

    def states(self):
        return {f"{run.parent.name} {run.name}": batch.state(run) for _, _, run in batch.runs(batch.load(self.folder, "first"))}

    def tool_calls(self, tool):
        return [call[1:] for call in map(json.loads, self.calls.read_text().splitlines()) if call[0] == tool] if self.calls.exists() else []

    def command(self, *arguments):
        return subprocess.run([sys.executable, str(SCRIPT), *arguments, "--folder", str(self.folder)], capture_output=True, text=True, stdin=subprocess.DEVNULL)


class TestPlan(Experiment):
    def test_plan_is_read_with_whole_paths_and_runs_go_a_case_at_a_time(self):
        plan = batch.load(self.folder, "first")
        self.assertEqual((plan["folder"], plan["repeats"], plan["timeout"], plan["executables"]), (self.experiment, 2, 30, {}))
        self.assertEqual(plan["cases"][0], {"name": "pr-1", "folder": self.folder / "cases" / "pr-1", "base": "bh1", "head": "h1", "clone": self.clone})
        self.assertEqual(plan["settings"], [{"name": "quick", "provider": "codex", "model": "small", "effort": "low", "policy": self.experiment / "rules.md"},
                                            {"name": "deep", "provider": "claude", "model": "large", "effort": "max", "policy": self.experiment / "rules.md"}])
        self.assertEqual([f"{setting['name']} {run.name}" for setting, _, run in batch.runs(plan)],
                         ["quick pr-1_rep1", "deep pr-1_rep1", "quick pr-1_rep2", "deep pr-1_rep2", "quick pr-2_rep1", "deep pr-2_rep1", "quick pr-2_rep2", "deep pr-2_rep2"])
        self.assertEqual({run.parent for _, _, run in batch.runs(plan)}, {self.experiment / "quick", self.experiment / "deep"})
        # A setting may bring rules of its own, and repeats and the time limit have a usual value.
        (self.experiment / "other.md").write_text("other rules\n")
        self.save(settings={"quick": {"provider": "codex", "model": "small", "effort": "low", "policy": "other.md"}}, executables={"codex": "/opt/codex"})
        del self.plan["repeats"], self.plan["timeout"]
        (self.experiment / "plan.json").write_text(json.dumps(self.plan))
        plan = batch.load(self.folder, "first")
        self.assertEqual((plan["settings"][0]["policy"], plan["repeats"], plan["timeout"], plan["executables"]), (self.experiment / "other.md", 1, 900, {"codex": "/opt/codex"}))

    def test_an_experiment_of_unseen_cases_says_so(self):
        self.assertEqual((batch.load(self.folder, "first")["unseen"], batch.unseen(self.experiment)), (False, False))
        self.save(unseen=True)
        self.assertEqual((batch.load(self.folder, "first")["unseen"], batch.unseen(self.experiment)), (True, True))
        self.save(unseen="yes")  # only true counts, and anything else that is not false cannot run
        self.assertFalse(batch.unseen(self.experiment))
        with self.assertRaisesRegex(ValueError, "unseen must be true or false"):
            batch.load(self.folder, "first")
        (self.experiment / "plan.json").write_text("{")
        self.assertFalse(batch.unseen(self.experiment))
        self.assertFalse(batch.unseen(self.folder / "experiments" / "scores-only"))

    def test_plan_that_cannot_run_is_refused_with_what_is_wrong(self):
        quick = self.plan["settings"]["quick"]
        for changed, problem in [({"version": 2}, "not a plan this version reads"), ({"repeats": 0}, "repeats must be"), ({"repeats": 1.5}, "repeats must be"), ({"timeout": "long"}, "timeout must be"),
                                 ({"cases": []}, "needs cases and settings"), ({"settings": {}}, "needs cases and settings"), ({"cases": ["../pr-1"]}, "cannot name a case"),
                                 ({"cases": ["pr-3"]}, "pr-3/case.json is not a case"), ({"clones": {}}, "clones names no folder for o/n"),
                                 ({"clones": {"o/n": "/not/there"}}, "clones names no folder for o/n"), ({"settings": {"a/b": quick}}, "cannot name a setting"),
                                 ({"settings": {"quick": {**quick, "provider": "another"}}}, "quick: provider must be one of codex, claude, agent"),
                                 ({"settings": {"quick": {**quick, "effort": ""}}}, "quick: model and effort must be named"),
                                 ({"policy": "missing.md"}, "quick: its rules file 'missing.md' is not there"), ({"executables": {"gemini": "/x"}}, "must each map a name to a path")]:
            with self.subTest(changed=changed):
                self.save(**changed)
                with self.assertRaisesRegex(ValueError, problem):
                    batch.load(self.folder, "first")
        (self.experiment / "plan.json").write_text("{")
        with self.assertRaisesRegex(ValueError, "it is not JSON"):
            batch.load(self.folder, "first")
        (self.folder / "cases" / "pr-1" / "context.md").unlink()
        self.save()
        with self.assertRaisesRegex(ValueError, "pr-1/context.md is not there"):
            batch.load(self.folder, "first")
        with self.assertRaisesRegex(ValueError, "experiments/second/plan.json is not there"):
            batch.load(self.folder, "second")


class TestExecute(Experiment):
    def setUp(self):
        super().setUp()
        self.stand_in("run", RUN)
        self.said = []

    def execute(self, parallel=1):
        return batch.execute(batch.load(self.folder, "first"), parallel, self.said.append)

    def test_only_what_is_missing_runs_and_every_run_is_kept(self):
        self.save(repeats=1)
        self.assertEqual(self.execute(), ["completed"] * 4)
        self.assertEqual(self.said, ["quick pr-1_rep1: completed", "deep pr-1_rep1: completed", "quick pr-2_rep1: completed", "deep pr-2_rep1: completed"])
        run = self.experiment / "deep" / "pr-2_rep1"
        self.assertEqual(json.loads((run / "runtime.json").read_text())["given"], {
            "--repo": str(self.clone), "--base": "bh2", "--head": "h2", "--policy": str(self.experiment / "rules.md"), "--context": str(self.folder / "cases" / "pr-2" / "context.md"),
            "--output": str(run), "--provider": "claude", "--model": "large", "--reasoning": "max", "--timeout": "30"})
        self.assertEqual(sorted(path.name for path in (run / "input").iterdir()), ["change.diff"])  # the two source trees are not kept, the change is
        self.assertEqual(sorted(path.name for path in run.iterdir()), ["input", "runtime.json"])  # nor the command line's own working state
        self.assertEqual(self.execute(), [])  # nothing is missing now
        self.save(repeats=2, executables={"claude": "/opt/claude"})  # more is asked for: only the new runs run
        self.assertEqual(self.execute(parallel=3), ["completed"] * 4)
        self.assertEqual(sorted(self.said[4:]), ["deep pr-1_rep2: completed", "deep pr-2_rep2: completed", "quick pr-1_rep2: completed", "quick pr-2_rep2: completed"])
        given = json.loads((self.experiment / "quick" / "pr-1_rep2" / "runtime.json").read_text())["given"]
        self.assertEqual((given["--claude"], "--codex" in given), ("/opt/claude", False))

    def test_the_labs_own_agent_is_a_setting_and_only_its_runs_are_handed_the_key(self):
        self.save(repeats=1, cases=["pr-1"], settings={"agent": {"provider": "agent", "model": "deepseek-v4-pro", "effort": "high"},
                                                       "quick": {"provider": "codex", "model": "small", "effort": "low"}})
        with mock.patch.dict(os.environ), mock.patch.object(batch, "stored_key", return_value=None):
            os.environ.pop("DEEPSEEK_API_KEY", None)
            self.assertEqual(self.execute(), ["completed"])  # without a key the agent's run waits
            self.assertEqual(self.said, ["1 run of the lab's own agent waits for a key: store it once with `uv run batch.py --store-key`.", "quick pr-1_rep1: completed"])
            with mock.patch.object(batch, "stored_key", return_value="a-key"):
                self.assertEqual(self.execute(), ["completed"])
        keyed = lambda setting: json.loads((self.experiment / setting / "pr-1_rep1" / "runtime.json").read_text())["keyed"]
        self.assertEqual((keyed("agent"), keyed("quick")), (True, False))

    def test_failed_run_is_kept_and_an_interrupted_one_runs_again(self):
        self.save(repeats=1, cases=["pr-1"], settings={"bad": {"provider": "codex", "model": "fails", "effort": "low"}, "gone": {"provider": "codex", "model": "dies", "effort": "low"}})
        self.assertEqual(self.execute(), ["failed", "interrupted"])
        self.assertEqual(self.said, ["bad pr-1_rep1: failed", "gone pr-1_rep1: interrupted (the command line is not installed)"])
        self.assertEqual(self.execute(), ["interrupted"])  # the failed run is a result and stays; the one that never ended is tried again
        self.assertEqual(self.states(), {"bad pr-1_rep1": "failed", "gone pr-1_rep1": "interrupted"})
        # Once its command line is there, the interrupted run starts over in a folder of its own and completes; the failed one is still not touched.
        self.save(repeats=1, cases=["pr-1"], settings={"bad": {"provider": "codex", "model": "fails", "effort": "low"}, "gone": {"provider": "codex", "model": "small", "effort": "low"}})
        self.assertEqual((self.execute(), self.states()), (["completed"], {"bad pr-1_rep1": "failed", "gone pr-1_rep1": "completed"}))
        (self.experiment / "gone" / "pr-1_rep1" / "runtime.json").write_text("{")  # and so is a folder whose record cannot be read
        self.assertEqual(batch.state(self.experiment / "gone" / "pr-1_rep1"), "interrupted")
        self.assertEqual(batch.state(self.experiment / "gone" / "pr-9_rep1"), "missing")

    def test_judge_scores_completed_runs_once_and_needs_a_key(self):
        self.stand_in("judge", JUDGE)
        self.save(repeats=1, settings={"good": {"provider": "codex", "model": "small", "effort": "low"}, "bad": {"provider": "codex", "model": "fails", "effort": "low"}})
        self.execute()
        plan, said = batch.load(self.folder, "first"), []
        with mock.patch.dict(os.environ, {"DEEPSEEK_API_KEY": ""}), mock.patch.object(sys, "stdin", io.StringIO()):
            batch.judge(plan, said.append)
        # No key anywhere and nobody there to ask; a failed run is never judged.
        self.assertEqual(said, ["2 runs are not judged: there is no key. Store it once with `uv run batch.py --store-key`."])
        with mock.patch.dict(os.environ, {"DEEPSEEK_API_KEY": "another-key"}):
            batch.judge(plan, said.append)
        self.assertEqual(said[1:3], ["good pr-1_rep1: not judged (Set DEEPSEEK_API_KEY to your DeepSeek API key)", "good pr-2_rep1: not judged (Set DEEPSEEK_API_KEY to your DeepSeek API key)"])
        said[3:] = []  # the scores are saved after every judging, also when none was judged
        with mock.patch.dict(os.environ, {"DEEPSEEK_API_KEY": "a-key"}):
            batch.judge(plan, said.append)
            batch.judge(plan, said.append)  # judged once: the second time there is nothing to do
        scores = self.experiment / "scores.json"
        self.assertEqual(said[3:], ["good pr-1_rep1: judged", "good pr-2_rep1: judged", f"Saved to {scores}", f"Saved to {scores}"])
        # Each run was judged against its own case, and the saved scores carry each comment's verdict.
        self.assertEqual([json.loads((self.experiment / "good" / run / "judgement.json").read_text())["case"] for run in ("pr-1_rep1", "pr-2_rep1")], ["h1", "h2"])
        saved_scores = json.loads(scores.read_text())
        self.assertEqual([(setting["name"], [(run["run"], run["case"], run["verdicts"]) for run in setting["runs"]], setting["notScored"]) for setting in saved_scores["settings"]],
                         [("bad", [], [{"run": "pr-1_rep1", "status": "failed"}, {"run": "pr-2_rep1", "status": "failed"}]),
                          ("good", [("pr-1_rep1", "h1", {"7": "found"}), ("pr-2_rep1", "h2", {"7": "found"})], [])])
        self.assertEqual(batch.listing(plan), ["good pr-1_rep1: completed, judged", "bad pr-1_rep1: failed", "good pr-2_rep1: completed, judged", "bad pr-2_rep1: failed",
                                               "2 completed, 2 failed"])
        asked = mock.Mock(return_value="a-key")
        (self.experiment / "good" / "pr-1_rep1" / "judgement.json").unlink()
        with mock.patch.dict(os.environ, {"DEEPSEEK_API_KEY": ""}), mock.patch.object(sys, "stdin", mock.Mock(isatty=lambda: True)), mock.patch.object(batch.getpass, "getpass", asked):
            batch.judge(plan, said.append)
            kept = os.environ["DEEPSEEK_API_KEY"]
        self.assertEqual((asked.call_count, said[7:], kept), (1, ["good pr-1_rep1: judged", f"Saved to {scores}"], ""))  # asked once, handed to the judge, and not kept by this program
        # A key in the Keychain judges without asking, also when someone is at the terminal.
        (self.experiment / "good" / "pr-2_rep1" / "judgement.json").unlink()
        with (mock.patch.dict(os.environ, {"DEEPSEEK_API_KEY": "", "KEYCHAIN_KEY": "a-key"}), mock.patch.object(sys, "stdin", mock.Mock(isatty=lambda: True)),
              mock.patch.object(batch.getpass, "getpass", asked)):
            batch.judge(plan, said.append)
        self.assertEqual((said[9:], asked.call_count), (["good pr-2_rep1: judged", f"Saved to {scores}"], 1))
        self.assertEqual(self.tool_calls("security"), [["find-generic-password", "-s", "ByteQuay Lab DeepSeek", "-w"]] * 3)  # each time no key was in the environment

    def test_a_round_runs_every_case_under_every_setting_once_more(self):
        self.save(repeats=1, cases=["pr-1"])
        plan = batch.load(self.folder, "first")
        self.assertEqual((self.execute(), batch.rounds(plan)), (["completed"] * 2, 1))
        self.assertEqual(batch.execute(plan, 1, self.said.append, total=batch.rounds(plan) + 1), ["completed"] * 2)
        self.assertEqual((batch.rounds(plan), self.said[2:], batch.listing(plan)[-1]), (2, ["quick pr-1_rep2: completed", "deep pr-1_rep2: completed"], "4 completed"))
        (self.experiment / "quick" / "pr-1_rep5").mkdir()  # a round cut short: its other runs are missing, and the rounds between are owed too
        (self.experiment / "gone" / "pr-1_rep9").mkdir(parents=True)  # a setting no longer in the plan does not count
        self.assertEqual((batch.rounds(plan), batch.listing(plan)[-1]), (5, "4 completed, 1 interrupted, 5 missing"))


class TestLock(Experiment):
    def test_only_the_process_that_holds_the_lock_counts_as_running(self):
        plan = batch.load(self.folder, "first")
        (self.experiment / "running.pid").write_text(str(os.getppid()))  # a live process that holds no lock: a number left behind and given to another process
        self.assertIsNone(batch.runner(plan))
        held = batch.claim(plan)
        self.addCleanup(held.close)
        self.assertEqual(((self.experiment / "running.pid").read_text(), batch.runner(plan)), (str(os.getpid()), os.getpid()))
        with self.assertRaisesRegex(ValueError, f"^first is already running, as process {os.getpid()}$"):
            batch.claim(plan)
        held.close()
        self.assertIsNone(batch.runner(plan))  # the lock goes with the process, however it ends

    def test_second_stop_cannot_cut_short_the_first(self):
        kept = signal.getsignal(signal.SIGTERM)
        self.addCleanup(signal.signal, signal.SIGTERM, kept)
        with self.assertRaises(SystemExit) as stopping:
            batch.stopped()
        self.assertEqual((stopping.exception.code, signal.getsignal(signal.SIGTERM)), (143, signal.SIG_IGN))


class TestSchedule(Experiment):
    def setUp(self):
        super().setUp()
        home = mock.patch.dict(os.environ, {"HOME": str(self.folder.parent / "home")})  # where the LaunchAgent goes
        home.start()
        self.addCleanup(home.stop)
        self.agent = self.folder.parent / "home" / "Library" / "LaunchAgents" / "com.bytequay.lab.plist"

    def test_schedule_keeps_one_agent_that_starts_the_due_rounds_every_night(self):
        done = self.command("first", "--every", "week")
        chosen = batch.schedule(self.folder)["first"]
        self.assertEqual((done.returncode, done.stderr, done.stdout), (0, "", f"first gets a new round every week, the first on "
                                                                            f"{batch.next_round(chosen).astimezone():%Y-%m-%d}, judged with the key in the Keychain.\n"))
        agent = plistlib.loads(self.agent.read_bytes())
        self.assertEqual((agent["Label"], agent["ProgramArguments"][1:], agent["StartCalendarInterval"], agent["StandardOutPath"]),
                         ("com.bytequay.lab", [str(SCRIPT.resolve()), "--due", "--folder", str(self.folder)], {"Hour": 3, "Minute": 0}, str(self.folder / "schedule.log")))
        self.assertTrue(agent["EnvironmentVariables"]["PATH"].startswith(str(self.folder.parent / "tools")))  # the night finds the command lines where the day did
        domain = f"gui/{os.getuid()}"
        self.assertEqual(self.tool_calls("launchctl"), [["bootout", f"{domain}/com.bytequay.lab"], ["bootstrap", domain, str(self.agent)]])
        done = self.command("first", "--every", "never")
        self.assertEqual((done.returncode, done.stdout, self.agent.exists(), batch.schedule(self.folder)), (0, "first is not scheduled.\n", False, {}))
        self.assertEqual(self.tool_calls("launchctl")[2:], [["bootout", f"{domain}/com.bytequay.lab"]])  # and nothing is loaded again
        refused = self.command("nothing", "--every", "week")
        self.assertEqual((refused.returncode, self.agent.exists(), batch.schedule(self.folder)), (2, False, {}))  # an experiment that cannot run is not scheduled
        with mock.patch.dict(os.environ, {"LAUNCHCTL_FAILS": "1"}):
            failed = self.command("first", "--every", "week")
        self.assertEqual((failed.returncode, failed.stderr), (1, f"launchctl could not load {self.agent}: Bootstrap failed: 5: Input/output error\n"))

    def test_the_page_sets_how_often_an_experiment_gets_a_round_as_the_command_line_does(self):
        moment = datetime(2026, 10, 27, 9, 0, tzinfo=timezone.utc)
        chosen = {"every": "day", "last": "2026-10-27T09:00:00+00:00"}
        self.assertEqual(batch.every(self.folder, "first", "day", moment), chosen)
        self.assertEqual((batch.schedule(self.folder), self.agent.exists()), ({"first": chosen}, True))
        self.assertIsNone(batch.every(self.folder, "first", "never"))
        self.assertEqual((batch.schedule(self.folder), self.agent.exists()), ({}, False))
        (self.folder / "schedule.json").write_text("{")
        batch.every(self.folder, "first", "week", moment)  # a schedule that cannot be read is replaced, not kept broken
        self.assertEqual(batch.schedule(self.folder), {"first": {**chosen, "every": "week"}})

    def test_due_rounds_start_one_after_another_and_are_marked_as_started(self):
        now = datetime(2026, 10, 21, 3, 0, 5, tzinfo=timezone.utc)
        ago = lambda length: (now - length).isoformat(timespec="seconds")
        # Last week's look started half a minute later in the night, and its round is still due. Three days ago is not.
        kept = {"first": {"every": "week", "last": ago(timedelta(days=7, seconds=-30))}, "gone": {"every": "day", "last": ago(timedelta(days=1))},
                "later": {"every": "week", "last": ago(timedelta(days=3))}}
        saved.write(self.folder, "schedule.json", {"version": 1, "experiments": kept})
        said = []
        with mock.patch.object(subprocess, "run") as started:
            batch.run_due(self.folder, now, said.append)
        self.assertEqual(said, ["2026-10-21 03:00 UTC first: a new round starts",
                                f"2026-10-21 03:00 UTC gone: not started: {self.folder / 'experiments' / 'gone' / 'plan.json'} is not there"])
        self.assertEqual([call.args[0][1:] for call in started.call_args_list], [[str(SCRIPT.resolve()), "first", "--folder", str(self.folder), "--round", "--judge"]])
        self.assertEqual({name: chosen["last"] for name, chosen in batch.schedule(self.folder).items()},
                         {"first": now.isoformat(timespec="seconds"), "gone": kept["gone"]["last"], "later": kept["later"]["last"]})
        held = batch.claim(batch.load(self.folder, "first"))
        self.addCleanup(held.close)
        saved.write(self.folder, "schedule.json", {"version": 1, "experiments": kept})
        said.clear()
        with mock.patch.object(subprocess, "run") as started:
            batch.run_due(self.folder, now, said.append)
        self.assertEqual((said[0], started.called), ("2026-10-21 03:00 UTC first: not started: it is running", False))

    def test_key_is_stored_by_the_macs_own_tool_and_the_page_only_asks_whether_it_is_there(self):
        stored = self.command("--store-key")
        self.assertEqual((stored.returncode, stored.stdout), (0, "Paste your DeepSeek API key (not your Mac password) when asked for password data, then once more. "
                                                                  "Nothing shows as you type.\n"))
        # -w comes last, so `security` asks for the key itself and the key never passes through the lab.
        self.assertEqual(self.tool_calls("security"), [["add-generic-password", "-U", "-a", getpass.getuser(), "-s", "ByteQuay Lab DeepSeek", "-w"]])
        with mock.patch.object(subprocess, "run", side_effect=KeyboardInterrupt), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(batch.store_key(), 130)  # Ctrl+C at the prompt: nothing is stored, and no traceback
        self.assertFalse(batch.has_key())
        with mock.patch.dict(os.environ, {"KEYCHAIN_KEY": "a-key"}):
            self.assertTrue(batch.has_key())
        with mock.patch.dict(os.environ, {"DEEPSEEK_API_KEY": "a-key"}):
            self.assertTrue(batch.has_key())
        self.assertEqual(self.tool_calls("security")[1:], [["find-generic-password", "-s", "ByteQuay Lab DeepSeek"]] * 2)  # asked whether one is there, never for the key


class TestCommandLine(Experiment):
    def script(self, *arguments):
        return subprocess.run([sys.executable, str(SCRIPT), "first", "--folder", str(self.folder), *arguments], capture_output=True, text=True, stdin=subprocess.DEVNULL)

    def real(self, model):
        """Make the experiment one that the real run.py can run: a clone with two commits, and a stand-in for both command lines."""
        git = lambda *args: subprocess.check_output(["git", "-C", str(self.clone), *args], stderr=subprocess.DEVNULL).decode().strip()
        git("init")
        git("config", "user.email", "fixture@example.invalid")
        git("config", "user.name", "Fixture")
        for content in ("base\n", "head\n"):
            (self.clone / "rows.py").write_text(content)
            git("add", ".")
            git("commit", "-m", content.strip())
        (self.folder / "cases" / "pr-1" / "case.json").write_text(json.dumps({"repo": "o/n", "number": 1, "base": git("rev-parse", "HEAD~1"), "head": git("rev-parse", "HEAD"), "comments": []}))
        fake = self.folder.parent / "stand-in"
        fake.write_text(FAKE.format(python=sys.executable, answer=repr(ANSWER)))
        fake.chmod(0o755)
        self.save(repeats=1, cases=["pr-1"], executables={"codex": str(fake), "claude": str(fake)},
                  settings={"codex-arm": {"provider": "codex", "model": model, "effort": "low"}, "claude-arm": {"provider": "claude", "model": model, "effort": "max"}})

    def test_experiment_runs_through_the_real_runner_and_lists_how_it_stands(self):
        self.real("reviewer")
        before = self.script("--list")
        self.assertEqual((before.returncode, before.stdout, before.stderr), (0, "codex-arm pr-1_rep1: missing\nclaude-arm pr-1_rep1: missing\n2 missing\n", ""))
        ran = self.script("--parallel", "2")
        self.assertEqual((ran.returncode, ran.stderr, sorted(ran.stdout.splitlines())), (0, "", ["2 completed", "claude-arm pr-1_rep1: completed", "codex-arm pr-1_rep1: completed"]))
        claude = self.experiment / "claude-arm" / "pr-1_rep1"
        self.assertEqual(json.loads((claude / "response.json").read_text()), ANSWER)
        self.assertEqual((json.loads((claude / "runtime.json").read_text())["provider"], sorted(path.name for path in (claude / "input").iterdir())),
                         ("claude", ["change.diff", "context.md", "policy.md"]))
        self.assertEqual(self.script().stdout, "2 completed\n")
        judged = self.script("--judge")  # no key and nobody to ask: said, and not an error
        self.assertEqual((judged.returncode, judged.stdout), (0, "2 runs are not judged: there is no key. Store it once with `uv run batch.py --store-key`.\n2 completed\n"))
        self.assertFalse((self.experiment / "scores.json").exists())  # nothing was judged, so there is nothing to score
        again = self.script("--round")
        self.assertEqual((again.returncode, again.stdout), (0, "codex-arm pr-1_rep2: completed\nclaude-arm pr-1_rep2: completed\n4 completed\n"))

    def test_stopped_experiment_stops_its_command_lines_and_runs_them_again_next_time(self):
        self.real("slow")
        self.save(**{**self.plan, "settings": {"claude-arm": self.plan["settings"]["claude-arm"]}})
        running = subprocess.Popen([sys.executable, str(SCRIPT), "first", "--folder", str(self.folder)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.addCleanup(running.kill)
        events = self.experiment / "claude-arm" / "pr-1_rep1" / "events.jsonl"
        for _ in range(150):  # until the stand-in command line has said who it is
            if events.exists() and events.read_text().strip():
                break
            time.sleep(0.1)
        stand_in = json.loads(events.read_text().splitlines()[0])["pid"]
        running.send_signal(signal.SIGTERM)
        self.assertEqual((running.communicate(timeout=15)[0], running.returncode), ("claude-arm pr-1_rep1: interrupted\n", 143))  # it says what it left unfinished
        for _ in range(50):
            try:
                os.kill(stand_in, 0)
            except ProcessLookupError:
                break
            time.sleep(0.1)
        else:
            os.kill(stand_in, signal.SIGKILL)
            self.fail("a command line went on running after the experiment was stopped")
        self.assertEqual(self.script("--list").stdout, "claude-arm pr-1_rep1: interrupted\n1 interrupted\n")
        self.assertFalse((self.experiment / "running.pid").exists())  # a stopped experiment no longer names a process

    def test_experiment_runs_once_at_a_time_and_says_so_while_it_runs(self):
        self.real("slow")
        self.save(**{**self.plan, "settings": {"claude-arm": self.plan["settings"]["claude-arm"]}})
        running = subprocess.Popen([sys.executable, str(SCRIPT), "first", "--folder", str(self.folder)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.addCleanup(running.kill)
        events = self.experiment / "claude-arm" / "pr-1_rep1" / "events.jsonl"
        for _ in range(150):
            if events.exists() and events.read_text().strip():
                break
            time.sleep(0.1)
        self.assertEqual(self.script("--list").stdout, "claude-arm pr-1_rep1: running\n1 running\n")
        second = self.script()
        self.assertEqual((second.returncode, second.stdout, second.stderr), (2, "", f"first is already running, as process {running.pid}\n"))
        self.assertTrue(events.exists())  # the second start did not touch the run of the first
        running.send_signal(signal.SIGTERM)
        running.communicate(timeout=15)
        (self.experiment / "running.pid").write_text(str(running.pid))  # a file left behind by a process that is gone does not block the next start
        self.assertEqual(self.script("--list").stdout, "claude-arm pr-1_rep1: interrupted\n1 interrupted\n")

    def test_page_starts_an_experiment_apart_from_itself_and_stops_it(self):
        self.real("slow")
        self.save(**{**self.plan, "settings": {"claude-arm": self.plan["settings"]["claude-arm"]}})
        plan = batch.load(self.folder, "first")
        started = batch.start(plan)
        self.addCleanup(started.wait)
        self.addCleanup(started.kill)
        self.assertEqual(batch.runner(plan), started.pid)  # it has claimed the experiment by the time start returns
        self.assertEqual((started.args[2:], os.getsid(started.pid)), (["first", "--folder", str(self.folder), "--judge"], started.pid))  # in a session of its own: closing the page does not stop it
        events = self.experiment / "claude-arm" / "pr-1_rep1" / "events.jsonl"
        for _ in range(150):
            if events.exists() and events.read_text().strip():
                break
            time.sleep(0.1)
        batch.stop(plan)
        self.assertEqual((batch.runner(plan), started.wait(timeout=5)), (None, 143))  # it has ended by the time stop returns
        self.assertEqual((self.experiment / "batch.log").read_text(), "claude-arm pr-1_rep1: interrupted\n")

    def test_wrong_plan_or_arguments_are_refused_before_anything_runs(self):
        self.save(repeats=0)
        refused = self.script()
        self.assertEqual((refused.returncode, refused.stdout), (2, ""))
        self.assertIn("plan.json: repeats must be a whole number of 1 or more", refused.stderr)
        self.save()
        refused = self.script("--parallel", "0")
        self.assertEqual(refused.returncode, 2)
        self.assertIn("--parallel must be 1 or more", refused.stderr)
        self.assertEqual(sorted(path.name for path in self.experiment.iterdir()), ["plan.json", "rules.md"])  # nothing ran, and no process is named


if __name__ == "__main__":
    unittest.main()
