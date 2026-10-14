import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

import batch
from test_run import ANSWER, FAKE

SCRIPT = Path(batch.__file__)
# A stand-in for run.py. Like it, it writes only into a new folder and leaves two source trees behind.
RUN = """import json, pathlib, sys, time
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
(output / "runtime.json").write_text(json.dumps({"status": "failed" if given["--model"] == "fails" else "completed", "given": given}))
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


class Experiment(unittest.TestCase):
    """A lab folder with two cases of one repository and an experiment over them."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.folder = Path(directory.name).resolve() / "lab"
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

    def test_plan_that_cannot_run_is_refused_with_what_is_wrong(self):
        quick = self.plan["settings"]["quick"]
        for changed, problem in [({"version": 2}, "not a plan this version reads"), ({"repeats": 0}, "repeats must be"), ({"repeats": 1.5}, "repeats must be"), ({"timeout": "long"}, "timeout must be"),
                                 ({"cases": []}, "needs cases and settings"), ({"settings": {}}, "needs cases and settings"), ({"cases": ["../pr-1"]}, "cannot name a case"),
                                 ({"cases": ["pr-3"]}, "pr-3/case.json is not a case"), ({"clones": {}}, "clones names no folder for o/n"),
                                 ({"clones": {"o/n": "/not/there"}}, "clones names no folder for o/n"), ({"settings": {"a/b": quick}}, "cannot name a setting"),
                                 ({"settings": {"quick": {**quick, "provider": "another"}}}, "quick: provider must be one of codex, claude"),
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
        self.assertEqual(said, ["2 runs are not judged: DEEPSEEK_API_KEY is not set."])  # nobody is there to ask, and a failed run is never judged
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
        self.assertEqual((judged.returncode, judged.stdout), (0, "2 runs are not judged: DEEPSEEK_API_KEY is not set.\n2 completed\n"))
        self.assertFalse((self.experiment / "scores.json").exists())  # nothing was judged, so there is nothing to score

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
