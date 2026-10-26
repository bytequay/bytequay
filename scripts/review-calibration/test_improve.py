import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import batch
import improve
from test_agent import Script, call, said

SCRIPT = Path(improve.__file__).resolve()
RULES = "# Review rules\n\nRead the change first.\n"
MERGED = RULES + "Open the tests next to each changed file.\nMerged rule.\n"
# A stand-in for run.py: it leaves what the writer reads, and notes every run it made.
RUN = """import hashlib, json, pathlib, shutil, sys
given = dict(zip(sys.argv[1::2], sys.argv[2::2]))
output = pathlib.Path(given["--output"])
(output / "input").mkdir(parents=True)
policy = pathlib.Path(given["--policy"]).read_text()
(output / "input" / "policy.md").write_text(policy)
shutil.copyfile(given["--context"], output / "input" / "context.md")
(output / "input" / "change.diff").write_text("+ a line\\n")
(output / "prompt.txt").write_text("Only investigate and return hypotheses.\\n")
(output / "schema.json").write_text("{}")
(output / "response.json").write_text(json.dumps({"candidates": [], "coverage": "all", "limitations": "none"}))
(output / "events.jsonl").write_text(json.dumps({"type": "item.completed", "item": {"type": "agent_message", "text": "Looked at the change."}}) + "\\n")
(output / "runtime.json").write_text(json.dumps({"status": "completed", "provider": given["--provider"], "model": given["--model"], "effort": given["--reasoning"],
                                                  "policySha256": hashlib.sha256(policy.encode()).hexdigest(), "elapsedSeconds": 60,
                                                  "usage": {"inputTokens": 10, "cachedInputTokens": 5, "outputTokens": 1, "cost": None}}))
with open(pathlib.Path(sys.argv[0]).with_name("runs.log"), "a") as log:
    log.write(str(output) + "\\n")
"""
# A stand-in for judge.py: under the merged rules every comment is found, under any other the first one alone.
JUDGE = """import json, pathlib, sys
given = dict(zip(sys.argv[2::2], sys.argv[3::2]))
case = json.loads(pathlib.Path(given["--case"]).read_text())
run = pathlib.Path(given["--run"])
merged = "Merged rule." in (run / "input" / "policy.md").read_text()
verdicts = [{"commentId": one["id"], "verdict": "FOUND" if merged or place == 0 else "MISSED", "leadIds": []} for place, one in enumerate(case["comments"])]
(run / "judgement.json").write_text(json.dumps({"case": case["head"], "leads": 1, "verdicts": verdicts, "errors": [], "usage": {"prompt_tokens": 10, "completion_tokens": 5}}))
"""
TUNING, UNSEEN = ("pr-1", "pr-2"), ("pr-3", "pr-4", "pr-5")


def answer(added, runs):
    """Edits that add lines after the rules' last one, and why."""
    last = "Read the change first.\n"
    return {"edits": [{"find": last, "replace": last + added}], "changes": [{"what": "Open the tests first", "why": "No run opened a test.", "runs": runs}],
            "lesson": "Tests come first."}


WORK = answer("Open the tests next to each changed file.\n", ["pr-1_rep1"])
EXPERT = answer("In this repository, tests avoid mocks.\n", ["pr-1_rep1", "pr-2_rep1"])


def writings(merge="work-1"):
    """The writer's three answers in turn: how to work, what an expert knows, and the merge of the two."""
    return [said(call("w", "submit_rules", WORK)), said(call("e", "submit_rules", EXPERT)), said(call("m", "submit_rules", answer(MERGED[len(RULES):], [f"{merge}/pr-1_rep1"])))]


class Lab(unittest.TestCase):
    """A lab folder with two tuning cases and three unseen ones of one repository, and an experiment over each."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.folder = Path(directory.name).resolve() / "lab"
        clone = self.folder.parent / "clone"
        clone.mkdir()
        for number, case in enumerate((*TUNING, *UNSEEN), 1):
            kept = self.folder / "cases" / case
            kept.mkdir(parents=True)
            comments = [{"id": number * 10 + place, "body": f"Reviewer comment {place} on pull request {number}, long enough to be searched for."} for place in range(2)]
            (kept / "case.json").write_text(json.dumps({"repo": "o/n", "number": number, "base": f"b{number}", "head": f"h{number}", "comments": comments}))
            (kept / "context.md").write_text(f"Intent of {case}\n")
        setting = {"base": {"provider": "codex", "model": "small", "effort": "low"}}
        for name, cases, more in (("tune", TUNING, {}), ("hold", UNSEEN, {"repeats": 2, "unseen": True})):
            (self.folder / "experiments" / name).mkdir(parents=True)
            (self.folder / "experiments" / name / "rules.md").write_text(RULES)
            plan = {"version": 1, "policy": "rules.md", "clones": {"o/n": str(clone)}, "cases": list(cases), "settings": setting, **more}
            (self.folder / "experiments" / name / "plan.json").write_text(json.dumps(plan))
        for name, source in (("RUN", RUN), ("JUDGE", JUDGE)):
            script = self.folder.parent / f"{name.lower()}.py"
            script.write_text(source)
            patched = mock.patch.object(batch, name, script)
            patched.start()
            self.addCleanup(patched.stop)
        keyed = mock.patch.dict(os.environ, {"DEEPSEEK_API_KEY": "a-key"})
        keyed.start()
        self.addCleanup(keyed.stop)
        self.said = []

    def improve(self, ask, made=None):
        made = made or improve.begun(self.folder, "tune", "hold", "base")
        return improve.improve(self.folder, made, ask, "deepseek-v4-pro", 2, self.said.append)

    def experiment(self, name):
        return self.folder / "experiments" / name

    def runs(self):
        log = self.folder.parent / "runs.log"
        return log.read_text().splitlines() if log.exists() else []


class TestImprove(Lab):
    def test_an_improvement_writes_tries_merges_and_checks_on_unseen_cases(self):
        made = self.improve(Script(*writings()))
        self.assertEqual((made["state"], made["done"], made["labels"]), ("done", list(improve.STAGES), {"work": "work-1", "expert": "expert-1", "merge": "merge-1"}))
        tune, hold = (json.loads((self.experiment(name) / "plan.json").read_text())["settings"] for name in ("tune", "hold"))
        self.assertEqual(list(tune), ["base", "base-work-1", "base-expert-1"])
        self.assertEqual((list(hold), hold["base-merge-1"]["policy"]), (["base", "base-merge-1"], "rules/merge-1.md"))
        self.assertEqual((self.experiment("hold") / "rules" / "merge-1.md").read_text(), MERGED)
        self.assertEqual((list(self.experiment("tune").rglob("judgement.json")), len(list(self.experiment("hold").rglob("judgement.json")))), ([], 12))
        self.assertEqual(len(self.runs()), 2 + 6 + 4 + 6)  # the base runs, both versions on the tuning cases, the merge on the unseen ones
        result = made["compared"]
        self.assertEqual([(side["setting"], side["comments"], side["foundOrPartly"], side["share"]) for side in result["sides"]],
                         [("base", 12, 6, 50.0), ("base-merge-1", 12, 12, 100.0)])
        self.assertEqual((result["cases"], result["change"], result["verdict"]), (3, (50.0, 50.0), "better"))
        self.assertEqual(self.said[-3:], ["base: 6 of 12 comments found or partly, 50% (range 50 to 50)", "base-merge-1: 12 of 12 comments found or partly, 100% (range 100 to 100)",
                                          "Change on the 3 unseen cases both were judged on: 50 to 50 points, so better."])
        self.assertFalse([line for line in self.said if "found" in line and any(case in line for case in UNSEEN)])  # no case of the unseen ones on its own
        self.assertEqual(json.loads((self.folder / "improvements" / "1" / improve.STATE).read_text()), json.loads(json.dumps(made)))

    def test_a_stopped_improvement_carries_on_without_running_anything_twice(self):
        answers = iter(writings())

        def ask(messages, offered, timeout):
            reply = next(answers)
            if reply["tool_calls"][0]["id"] == "m":
                raise KeyboardInterrupt  # stopped while the merge is written
            return reply, {"prompt_tokens": 10, "completion_tokens": 1, "prompt_cache_hit_tokens": 0}, "tool_calls"

        with self.assertRaises(KeyboardInterrupt):
            self.improve(ask)
        kept = json.loads((self.folder / "improvements" / "1" / improve.STATE).read_text())
        self.assertEqual((kept["state"], kept["done"]), ("stopped", ["base", "work", "expert", "tried"]))
        before = self.runs()
        made = self.improve(Script(writings()[2]), kept)
        self.assertEqual((made["state"], made["labels"]["merge"]), ("done", "merge-2"))  # the stopped writing keeps its number
        self.assertEqual(len(set(self.runs())), len(self.runs()))
        self.assertEqual(len(self.runs()) - len(before), 6)  # only the merge's runs on the unseen cases were left

    def test_a_writing_that_hands_nothing_in_ends_the_improvement_and_is_written_again_when_carried_on(self):
        made = self.improve(Script(RuntimeError("the API is down")))
        self.assertEqual((made["state"], made["done"], made["problem"]), ("failed", ["base"], "the work writing did not finish: RuntimeError: the API is down"))
        made = self.improve(Script(*writings(merge="work-2")), made)
        self.assertEqual((made["state"], made["labels"]["work"]), ("done", "work-2"))

    def test_plans_that_cannot_make_an_improvement_are_refused_before_anything_runs(self):
        hold = json.loads((self.experiment("hold") / "plan.json").read_text())
        for changed, problem in (({"unseen": False}, 'tune must hold tuning cases and hold unseen ones, marked with "unseen": true'),
                                 ({"settings": {"other": hold["settings"]["base"]}}, "base must be a setting of both tune and hold"),
                                 ({"settings": {"base": {**hold["settings"]["base"], "effort": "high"}}}, "base must run the same rules, model and effort in tune and hold")):
            (self.experiment("hold") / "plan.json").write_text(json.dumps({**hold, **changed}))
            with self.assertRaisesRegex(ValueError, f"^{problem}$"):
                improve.begun(self.folder, "tune", "hold", "base")
        self.assertEqual((self.runs(), (self.folder / "improvements").exists()), ([], False))

    def test_a_setting_is_named_once_and_never_changed(self):
        chosen = {"provider": "codex", "model": "small", "effort": "low", "policy": "rules/work-1.md"}
        improve.add_setting(self.experiment("tune"), "base-work-1", chosen)
        improve.add_setting(self.experiment("tune"), "base-work-1", chosen)
        with self.assertRaisesRegex(ValueError, "^tune already has a setting base-work-1 that runs something else$"):
            improve.add_setting(self.experiment("tune"), "base-work-1", {**chosen, "effort": "high"})


# A stand-in for improve.py started from the page: it holds the tuning experiment as an improvement does, until it is stopped.
IMPROVING = """import pathlib, signal, sys, time
sys.path.insert(0, {lab!r})
import batch
given = dict(zip(sys.argv[1::2], sys.argv[2::2]))
plan = batch.load(pathlib.Path(given["--folder"]), given["--tuning"])
held = batch.claim(plan)
signal.signal(signal.SIGTERM, batch.stopped)
print("improving", flush=True)
try:
    time.sleep(60)
finally:
    (plan["folder"] / "running.pid").unlink(missing_ok=True)
    held.close()
"""


class TestStart(Lab):
    def test_the_page_starts_an_improvement_apart_from_itself_and_stops_it(self):
        script = self.folder.parent / "improving.py"
        script.write_text(IMPROVING.format(lab=str(SCRIPT.parent)))
        arguments = ["--tuning", "tune", "--unseen", "hold", "--setting", "base"]
        with mock.patch.object(improve, "SCRIPT", script):
            started = improve.start(self.folder, arguments, "tune")
        self.addCleanup(started.wait)
        self.addCleanup(started.kill)
        plan = batch.load(self.folder, "tune")
        self.assertEqual((batch.runner(plan), os.getsid(started.pid)), (started.pid, started.pid))  # held when start returns, in a session of its own
        self.assertEqual(started.args[2:], [*arguments, "--folder", str(self.folder)])
        batch.stop(plan)
        self.assertEqual((batch.runner(plan), started.wait(timeout=5)), (None, 143))
        self.assertEqual((self.folder / "improvements" / "improve.log").read_text(), "improving\n")


class TestCommandLine(Lab):
    def test_a_second_improvement_waits_for_none_and_is_refused_while_one_runs(self):
        held = batch.claim(batch.load(self.folder, "tune"))
        self.addCleanup(held.close)
        done = subprocess.run([sys.executable, str(SCRIPT), "--tuning", "tune", "--unseen", "hold", "--setting", "base", "--folder", str(self.folder)],
                              capture_output=True, text=True, stdin=subprocess.DEVNULL, env={**os.environ, "DEEPSEEK_API_KEY": "a-key"})
        self.assertEqual((done.returncode, done.stderr), (2, f"tune is already running, as process {os.getpid()}\n"))
        self.assertEqual(self.runs(), [])

    def test_a_new_improvement_or_carrying_one_on_is_asked_for_plainly(self):
        for arguments in ([], ["--continue", "--setting", "base"], ["--tuning", "tune"]):
            done = subprocess.run([sys.executable, str(SCRIPT), *arguments, "--folder", str(self.folder)], capture_output=True, text=True, stdin=subprocess.DEVNULL)
            self.assertEqual(done.returncode, 2)
            self.assertIn("name --tuning, --unseen and --setting for a new improvement, or --continue alone", done.stderr)
        done = subprocess.run([sys.executable, str(SCRIPT), "--continue", "--folder", str(self.folder)], capture_output=True, text=True, stdin=subprocess.DEVNULL,
                              env={**os.environ, "DEEPSEEK_API_KEY": "a-key"})
        self.assertEqual((done.returncode, done.stderr), (1, "No improvement is left to carry on.\n"))


if __name__ == "__main__":
    unittest.main()
