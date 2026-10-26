import http.server
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest import mock

import agent
import writer
from test_agent import Script, Stand, call, completion, said
from test_batch import TOOLS

SCRIPT = Path(writer.__file__).resolve()
RULES = "# Review rules\n\nRead the change first.\nCheck each lead before you hand it in.\n"
TASK = "Only investigate and return hypotheses.\n"
COMMENTS = {"pr-1": "Please reuse the existing literal here instead of adding another constant.",
            "pr-2": "This test mocks the reader; the project prefers a real one built in the test."}
# What a Codex run writes: HERE stands for the run's input folder, which it reads by its full path.
CODEX = [{"type": "thread.started", "thread_id": "t"}, {"type": "turn.started"},
         {"type": "item.completed", "item": {"id": "i0", "type": "agent_message", "text": "I will read the diff first."}},
         {"type": "item.started", "item": {"id": "i1", "type": "command_execution", "command": "/bin/zsh -lc pwd", "aggregated_output": "", "exit_code": None}},
         {"type": "item.completed", "item": {"id": "i1", "type": "command_execution", "command": "/bin/zsh -lc pwd", "aggregated_output": "HERE\n", "exit_code": 0}},
         {"type": "item.completed", "item": {"id": "i2", "type": "command_execution", "command": "/bin/zsh -lc 'cat change.diff'",
                                             "aggregated_output": "\n".join(f"line {n}" for n in range(1, 21)), "exit_code": 0}},
         {"type": "turn.completed", "usage": {"input_tokens": 100, "cached_input_tokens": 50, "output_tokens": 10}}]
CLAUDE = [{"type": "system", "subtype": "init", "tools": ["Read"]},
          {"type": "assistant", "message": {"content": [{"type": "thinking", "thinking": "", "signature": "kept apart"}]}},
          {"type": "assistant", "message": {"content": [{"type": "tool_use", "id": "u1", "name": "Read", "input": {"file_path": "HERE/change.diff"}}]}},
          {"type": "user", "message": {"content": [{"type": "tool_result", "tool_use_id": "u1", "content": [{"type": "text", "text": "a\nb"}]}]}},
          {"type": "assistant", "message": {"content": [{"type": "thinking", "thinking": "The change is small."}, {"type": "text", "text": "Done."}]}},
          {"type": "result", "structured_output": {"candidates": []}, "usage": {}}]
LAST = "Check each lead before you hand it in.\n"


def adding(text):
    """Edits that add `text` after the rules' last line."""
    return [{"find": LAST, "replace": LAST + text}]


NEW_RULES = RULES + "Open the tests next to each changed file before you list leads.\n"
ANSWER = {"edits": adding("Open the tests next to each changed file before you list leads.\n"),
          "changes": [{"what": "Open the tests next to each changed file", "why": "Neither run opened a test file.", "runs": ["pr-1_rep1", "pr-2_rep1"]}],
          "lesson": "Runs that skip tests miss what reviewers ask about tests."}


class Lab(unittest.TestCase):
    """A lab folder with two cases of owner/widget and an experiment whose codex and claude settings ran on both."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.folder = Path(directory.name).resolve() / "lab"
        self.experiment = self.folder / "experiments" / "first"
        self.experiment.mkdir(parents=True)
        (self.experiment / "rules.md").write_text(RULES)
        for case, body in COMMENTS.items():
            kept = self.folder / "cases" / case
            kept.mkdir(parents=True)
            (kept / "case.json").write_text(json.dumps({"repo": "owner/widget", "number": 1, "base": "b", "head": "h", "comments": [{"id": 7, "body": body}]}))
            (kept / "context.md").write_text(f"Intent of {case}\n")
        settings = {"quick": {"provider": "codex", "model": "small", "effort": "low"}, "deep": {"provider": "claude", "model": "large", "effort": "max"},
                    "own": {"provider": "agent", "model": "deepseek-v4-pro", "effort": "default"}}
        (self.experiment / "plan.json").write_text(json.dumps({"version": 1, "policy": "rules.md", "clones": {"owner/widget": str(self.folder.parent)},
                                                               "cases": list(COMMENTS), "settings": settings}))
        for case in COMMENTS:
            self.ran("quick", case, "codex", CODEX)
            self.ran("deep", case, "claude", CLAUDE)
        (self.experiment / "scores.json").write_text(json.dumps({"version": 1, "settings": [{"verdicts": COMMENTS["pr-1"]}]}))
        (self.experiment / "labels.json").write_text(json.dumps({"7": "found"}))

    def ran(self, setting, case, provider, events, status="completed", policy="hash-of-the-rules", rep=1, rules=RULES):
        """One run as run.py and the judge leave it, the judge's verdict and the reviewer's comment among its files."""
        folder = self.experiment / setting / f"{case}_rep{rep}"
        (folder / "input").mkdir(parents=True)
        (folder / "input" / "policy.md").write_text(rules)
        (folder / "input" / "context.md").write_text(f"Intent of {case}\n")
        (folder / "input" / "change.diff").write_text("+ added line\n")
        (folder / "prompt.txt").write_text(TASK)
        (folder / "schema.json").write_text(json.dumps(writer.run.SCHEMA))
        (folder / "response.json").write_text(json.dumps({"candidates": [{"hypothesis": "A lead"}], "coverage": "all", "limitations": "none"}))
        (folder / "runtime.json").write_text(json.dumps({"provider": provider, "model": "m", "effort": "e", "status": status, "policySha256": policy,
                                                         "elapsedSeconds": 12.5, "usage": {"inputTokens": 100, "cachedInputTokens": 50, "outputTokens": 10}}))
        (folder / "events.jsonl").write_text("".join(json.dumps(event).replace("HERE", str(folder / "input")) + "\n" for event in events))
        (folder / "judgement.json").write_text(json.dumps({"verdicts": [{"commentId": 7, "verdict": "found", "reason": COMMENTS[case]}]}))
        (folder / "judge-calls.json").write_text(json.dumps({"calls": [{"user": COMMENTS[case]}]}))
        (folder / "manifest.json").write_text("{}")
        return folder

    def plan(self):
        return writer.batch.load(self.folder, "first")

    def tried(self, label="work-1", changes=None):
        """A version a writer kept, with its record, and a setting that ran it on both cases."""
        (self.experiment / "rules").mkdir(exist_ok=True)
        (self.experiment / "rules" / f"{label}.md").write_text(NEW_RULES)
        (self.experiment / "rules" / f"{label}.json").write_text(json.dumps({"version": 1, "changes": ANSWER["changes"] if changes is None else changes,
                                                                             "lesson": ANSWER["lesson"], "state": "done"}))
        plan = json.loads((self.experiment / "plan.json").read_text())
        plan["settings"][f"quick-{label}"] = {"provider": "codex", "model": "small", "effort": "low", "policy": f"rules/{label}.md"}
        (self.experiment / "plan.json").write_text(json.dumps(plan))
        for case in COMMENTS:
            self.ran(f"quick-{label}", case, "codex", CODEX, policy=f"hash-of-{label}", rules=NEW_RULES)
        return f"quick-{label}"


class TestEvidence(Lab):
    def test_the_evidence_holds_what_the_runs_did_and_never_a_verdict_a_score_or_a_comment(self):
        root = self.folder / "evidence"
        current, policy, runs, repositories = writer.evidence(self.plan(), "quick", root)
        self.assertEqual((current, policy, runs, repositories), (RULES, "hash-of-the-rules", ["pr-1_rep1", "pr-2_rep1"], ["owner/widget"]))
        kept = sorted(path.relative_to(root).as_posix() for path in root.rglob("*") if path.is_file())
        self.assertEqual(kept, ["answer-shape.json", "rules.md", *(f"runs/{run}/{name}" for run in runs for name in ("answer.json", "change.diff", "context.md", "run.md", "steps.md")),
                                "task.md"])
        self.assertEqual(((root / "task.md").read_text(), json.loads((root / "answer-shape.json").read_text())), (TASK, writer.run.SCHEMA))  # what the rules must fit
        steps = (root / "runs" / "pr-1_rep1" / "steps.md").read_text()
        self.assertEqual(steps.splitlines()[:4], ["said: I will read the diff first.", "$ /bin/zsh -lc pwd  (exit 0)", "  .", "$ /bin/zsh -lc 'cat change.diff'  (exit 0)"])
        self.assertTrue(steps.endswith("  line 15\n  …(5 more lines)\n"))
        self.assertNotIn(str(self.folder), "".join(path.read_text() for path in root.rglob("*") if path.is_file()))  # no path names this Mac's user
        self.assertEqual((root / "runs" / "pr-2_rep1" / "run.md").read_text().splitlines()[0], "Status: completed")

    def test_every_run_shares_one_budget_of_steps_so_all_of_them_fit_the_conversation(self):
        with mock.patch.object(writer, "TRANSCRIPTS", 160), mock.patch.object(writer, "FEWEST", 60):
            writer.evidence(self.plan(), "quick", self.folder / "evidence")  # two runs: 80 characters of steps each
        steps = (self.folder / "evidence" / "runs" / "pr-1_rep1" / "steps.md").read_text()
        self.assertIn("steps not shown", steps)
        self.assertLess(len(steps), 80 + 30)

    def test_a_reviewers_comment_anywhere_in_the_evidence_stops_it_and_nothing_is_kept(self):
        spaced = "  Please reuse the existing literal\nhere instead of adding another constant."  # as a model might quote it, wrapped
        leaky = [*CODEX, {"type": "item.completed", "item": {"type": "agent_message", "text": spaced}}]
        (self.experiment / "quick" / "pr-1_rep1" / "events.jsonl").write_text("".join(json.dumps(event) + "\n" for event in leaky))
        root = self.folder / "evidence"
        with self.assertRaisesRegex(ValueError, "^runs/pr-1_rep1/steps.md holds a reviewer's comment, which the writer must never read$"):
            writer.evidence(self.plan(), "quick", root)
        self.assertFalse(root.exists())

    def test_only_completed_runs_of_one_rules_file_and_a_provider_it_reads_can_be_learned_from(self):
        self.ran("quick", "pr-1", "codex", CODEX, status="timed_out", rep=2)
        self.assertEqual(writer.evidence(self.plan(), "quick", self.folder / "a")[2], ["pr-1_rep1", "pr-2_rep1"])
        self.ran("quick", "pr-2", "codex", CODEX, policy="hash-of-other-rules", rep=2)
        for setting, problem in (("quick", "the runs of quick used different rules; write from one rules file at a time"),
                                 ("own", "own runs on agent, whose transcripts the writer cannot read"),
                                 ("missing", "the experiment has no setting missing")):
            with self.assertRaisesRegex(ValueError, f"^{problem}$"):
                writer.evidence(self.plan(), setting, self.folder / "b")
        plan = self.plan()
        plan["settings"].append({"name": "idle", "provider": "codex"})
        with self.assertRaisesRegex(ValueError, "^idle has no completed run to learn from$"):
            writer.evidence(plan, "idle", self.folder / "c")


class TestMerge(Lab):
    def test_a_merge_reads_each_version_its_changes_and_its_runs_and_never_how_it_scored(self):
        version = self.tried()
        root = self.folder / "evidence"
        current, policy, cited, _ = writer.evidence(self.plan(), "quick", root, [version])
        self.assertEqual((current, policy, cited), (RULES, "hash-of-the-rules", ["pr-1_rep1", "pr-2_rep1", "work-1/pr-1_rep1", "work-1/pr-2_rep1"]))
        kept = sorted(path.relative_to(root / "versions").as_posix() for path in (root / "versions").rglob("*") if path.is_file())
        self.assertEqual(kept, ["work-1/changes.json", "work-1/rules.md",
                                *(f"work-1/runs/{run}/{name}" for run in ("pr-1_rep1", "pr-2_rep1") for name in ("answer.json", "run.md", "steps.md"))])  # the description and the change are those of runs/
        self.assertEqual(((root / "versions" / "work-1" / "rules.md").read_text(), json.loads((root / "versions" / "work-1" / "changes.json").read_text())),
                         (NEW_RULES, {"changes": ANSWER["changes"], "lesson": ANSWER["lesson"]}))
        names = {path.name for path in root.rglob("*")}
        self.assertFalse(names & {"judgement.json", "judge-calls.json", "scores.json", "labels.json", "case.json"})

    def test_only_settings_that_tried_a_version_with_runs_can_be_merged(self):
        version = self.tried()
        plan = json.loads((self.experiment / "plan.json").read_text())
        plan["settings"]["quick-expert-1"] = {"provider": "codex", "model": "small", "effort": "low", "policy": "rules.md"}
        (self.experiment / "plan.json").write_text(json.dumps(plan))
        for versions, problem in (([version, "quick-expert-1"], "quick-expert-1 has no completed run to learn from"),
                                  (["quick"], "quick runs the rules as they stand, so it is no version of them")):
            with self.assertRaisesRegex(ValueError, f"^{problem}$"):
                writer.evidence(self.plan(), "quick", self.folder / "evidence", versions)
        for kind, versions in (("merge", ()), ("work", (version,))):
            with self.assertRaisesRegex(ValueError, "^a merge needs the settings that tried versions, and only a merge takes them$"):
                writer.write(self.folder, "first", "quick", kind, Script(), "deepseek-v4-pro", versions=versions)

    def test_a_merge_may_cite_the_runs_of_a_version_and_keeps_what_it_merged(self):
        version = self.tried()
        cited = ["pr-1_rep1", "pr-2_rep1", "work-1/pr-1_rep1", "work-1/pr-2_rep1"]
        merged = {**ANSWER, "edits": adding("Open the tests next to each changed file before you list leads.\nIn Widget, close every reader.\n"),
                  "changes": [{**ANSWER["changes"][0], "runs": ["work-1/pr-1_rep1"]}]}  # one pull request, naming the repository: a merge may
        self.assertIsNone(writer.problem_of(merged, RULES, cited, ["owner/widget"], "merge"))
        unknown = {**merged, "changes": [{**ANSWER["changes"][0], "runs": ["work-9/pr-1_rep1"]}]}
        self.assertEqual(writer.problem_of(unknown, RULES, cited, ["owner/widget"], "merge"), f"runs of change 1 must name runs the evidence holds: {', '.join(cited)}")
        script = Script(said(call("c1", "read_file", {"path": "versions/work-1/changes.json"})), said(call("c2", "submit_rules", merged)))
        made = writer.write(self.folder, "first", "quick", "merge", script, "deepseek-v4-pro", versions=[version])
        self.assertEqual((made["state"], made["rules"], made["versions"], made["runs"]), ("done", "rules/merge-1.md", [version], cited))
        self.assertIn("Neither run opened a test file.", script.sent[1][-1]["content"])
        self.assertIn("versions/<name>/ holds each one", script.sent[0][1]["content"])


class TestSteps(unittest.TestCase):
    def test_a_claude_run_shows_its_tools_what_they_returned_what_it_said_and_thinking_it_shows(self):
        self.assertEqual(list(writer.claude_steps(CLAUDE)), ['Read {"file_path": "HERE/change.diff"}', "  a\n  b", "thought: The change is small.", "said: Done."])

    def test_long_parts_are_clipped(self):
        long = [{"type": "item.completed", "item": {"type": "command_execution", "command": "x" * 400, "aggregated_output": "y" * 500, "exit_code": 1}}]
        self.assertEqual(list(writer.codex_steps(long)), [f"$ {'x' * 300} …(100 more characters)  (exit 1)\n  {'y' * agent.WIDTH}"])

    def test_a_long_transcript_keeps_its_start_and_its_end(self):
        steps = [f"step {n} " + "x" * 1000 for n in range(100)]
        text = writer.transcript(steps, 40_000)
        self.assertLessEqual(len(text), 40_000 + 30)
        self.assertTrue(text.startswith("step 0 ") and text.rstrip().split("\n")[-1].startswith("step 99 "))
        self.assertIn("\n…(61 steps not shown)\n", text)
        self.assertEqual(writer.transcript(["one", "two"], 40_000), "one\ntwo\n")


class TestAnswer(unittest.TestCase):
    def test_edits_land_where_their_text_is_and_nowhere_else(self):
        rules, problem = writer.applied(RULES, [{"find": LAST, "replace": ""}, {"find": "Read the change first.", "replace": "Read the description, then the change."}])
        self.assertEqual((rules, problem), ("# Review rules\n\nRead the description, then the change.\n", None))  # in the order they stand, whatever order they came in
        for edits, problem in (([], "edits must list from 1 to 20 edits"), ([{"find": "x"}], "edit 1 must have exactly find and replace, both text"),
                               ([{"find": "Nowhere.", "replace": "a"}], "the find text of edit 1 must be in rules.md exactly once, quoted as it stands; it is there 0 times"),
                               ([{"find": "Read the change first.\nAnd more.", "replace": "a"}],
                                "the find text of edit 1 must be in rules.md exactly once, quoted as it stands; it is there 0 times "
                                "(its first 23 characters are, the rest is not: read that part of rules.md again)"),
                               ([{"find": "", "replace": "a"}], "the find text of edit 1 must be in rules.md exactly once, quoted as it stands; it is there 0 times"),
                               ([{"find": "Re", "replace": "a"}], "the find text of edit 1 must be in rules.md exactly once, quoted as it stands; it is there 2 times"),
                               ([{"find": "Read the change first.", "replace": "a"}, {"find": "first.\nCheck", "replace": "b"}], "edits 1 and 2 find overlapping text; make them one edit")):
            self.assertEqual(writer.applied(RULES, edits), (None, problem))

    def test_handed_in_edits_are_checked_before_they_count(self):
        runs, repositories = ["pr-1_rep1", "pr-2_rep1"], ["owner/widget"]
        change = ANSWER["changes"][0]
        self.assertEqual(writer.problem_of({"edits": ANSWER["edits"], "changes": ANSWER["changes"]}, RULES, runs, repositories, "work"),
                         "the answer must have exactly edits, changes and lesson")
        long = "x" * writer.most(RULES)
        for wrong, problem in (({"edits": []}, "edits must list from 1 to 20 edits"),
                               ({"edits": [{"find": LAST, "replace": LAST}]}, "the rules are the same as now: change what the runs give a reason for"),
                               ({"edits": adding(long)}, f"the rules may hold at most {writer.most(RULES):,} characters, as they go with every review; "
                                                         f"these hold {len(RULES) + len(long):,}"),
                               ({"changes": []}, "changes must list from 1 to 8 changes"),
                               ({"changes": [{"what": "a", "why": "b"}]}, "change 1 must have exactly what, why and runs"),
                               ({"changes": [{**change, "why": ""}]}, "what and why of change 1 must be text"),
                               ({"changes": [{**change, "runs": ["pr-9_rep1"]}]}, "runs of change 1 must name runs the evidence holds: pr-1_rep1, pr-2_rep1"),
                               ({"lesson": ""}, "lesson must be one thing learned, for the next writer"),
                               ({"edits": adding("In Widget, close every reader.\n")}, "these rules serve every repository, so an added line must not name widget")):
            self.assertEqual(writer.problem_of({**ANSWER, **wrong}, RULES, runs, repositories, "work"), problem)
        self.assertIsNone(writer.problem_of(ANSWER, RULES, runs, repositories, "work"))
        self.assertIsNone(writer.problem_of({**ANSWER, "edits": adding("In Widget, close every reader.\n")}, RULES, runs, repositories, "expert"))
        self.assertIsNone(writer.problem_of({**ANSWER, "edits": adding("Widgets in a test need no mocks.\n")}, RULES, runs, repositories, "work"))

    def test_what_an_expert_change_adds_must_have_been_needed_by_two_pull_requests(self):
        one = {**ANSWER, "changes": [{**ANSWER["changes"][0], "runs": ["pr-1_rep1", "pr-1_rep2"]}]}  # two runs, one pull request
        runs, repositories = ["pr-1_rep1", "pr-1_rep2", "pr-2_rep1"], ["owner/widget"]
        self.assertEqual(writer.problem_of(one, RULES, runs, repositories, "expert"), "runs of change 1 must come from at least two pull requests: what only one "
                                                                                      "needed is knowledge of that change, not of the repository")
        self.assertIsNone(writer.problem_of(one, RULES, runs, repositories, "work"))  # how a reviewer works may show in one pull request
        self.assertIsNone(writer.problem_of(ANSWER, RULES, ["pr-1_rep1", "pr-2_rep1"], repositories, "expert"))
        self.assertIsNone(writer.problem_of(one, RULES, ["pr-1_rep1", "pr-1_rep2"], repositories, "expert"))  # with one pull request read, one is all there is


class TestWrite(Lab):
    def test_a_writing_reads_with_tools_and_keeps_the_rules_why_and_what_it_read(self):
        script = Script(said(call("c1", "read_file", {"path": "runs/pr-1_rep1/steps.md"})), said(call("c2", "submit_rules", {**ANSWER, "lesson": ""})),
                        said(call("c3", "submit_rules", ANSWER)))
        made = writer.write(self.folder, "first", "quick", "work", script, "deepseek-v4-pro")
        own = self.experiment / "rules"
        self.assertEqual((made["state"], made["rules"], made["runs"], made["current"], made["steps"]), ("done", "rules/work-1.md", ["pr-1_rep1", "pr-2_rep1"], "hash-of-the-rules", 3))
        self.assertEqual((own / "work-1.md").read_text(), NEW_RULES)
        self.assertEqual(json.loads((own / "work-1.json").read_text()), made)
        self.assertEqual((made["changes"], made["lesson"]), (ANSWER["changes"], ANSWER["lesson"]))
        self.assertTrue(made["diff"].endswith("+Open the tests next to each changed file before you list leads.\n"))
        self.assertEqual(script.sent[0][1]["content"], writer.asked("work", RULES, made["runs"], ["owner/widget"]))
        self.assertIn("     1: said: I will read the diff first.", script.sent[1][-1]["content"])  # the writer read the evidence folder
        self.assertEqual(script.sent[2][-1]["content"], "error: lesson must be one thing learned, for the next writer. Call submit_rules again with the whole rules.")
        self.assertTrue((own / "work-1.evidence" / "rules.md").is_file())
        events = [json.loads(line)["type"] for line in (own / "work-1.events.jsonl").read_text().splitlines()]
        self.assertEqual(events, ["model", "tool", "model", "tool", "model", "tool", "result"])
        failed = writer.write(self.folder, "first", "quick", "work", Script(RuntimeError("the API is down")), "deepseek-v4-pro")
        self.assertEqual((failed["state"], failed["problem"], (own / "work-2.md").exists()), ("failed", "RuntimeError: the API is down", False))
        self.assertEqual(writer.write(self.folder, "first", "deep", "work", Script(said(call("c1", "submit_rules", ANSWER))), "deepseek-v4-pro")["rules"], "rules/work-3.md")
        self.assertEqual(writer.write(self.folder, "first", "deep", "expert", Script(said(call("c1", "submit_rules", ANSWER))), "deepseek-v4-pro")["rules"], "rules/expert-1.md")

    def test_the_tools_offered_are_the_three_readers_and_the_hand_in(self):
        self.assertEqual([one["function"]["name"] for one in writer.offered()], ["list_files", "read_file", "search", "submit_rules"])

    def test_a_writing_that_cannot_start_keeps_why(self):
        with self.assertRaisesRegex(ValueError, "^own runs on agent"):
            writer.write(self.folder, "first", "own", "work", Script(), "deepseek-v4-pro")
        kept = json.loads((self.experiment / "rules" / "work-1.json").read_text())
        self.assertEqual((kept["state"], kept["problem"]), ("failed", "ValueError: own runs on agent, whose transcripts the writer cannot read"))

    def test_an_experiment_of_unseen_cases_is_never_read(self):
        plan = json.loads((self.experiment / "plan.json").read_text())
        (self.experiment / "plan.json").write_text(json.dumps({**plan, "unseen": True}))
        script = Script(said(call("c1", "submit_rules", ANSWER)))
        with self.assertRaisesRegex(ValueError, "^first holds unseen cases; the writer learns from tuning cases only$"):
            writer.write(self.folder, "first", "quick", "work", script, "deepseek-v4-pro")
        self.assertEqual((script.sent, (self.experiment / "rules").exists()), ([], False))


class TestCommandLine(Lab):
    def command(self, environment):
        return subprocess.run([sys.executable, str(SCRIPT), "first", "--setting", "quick", "--kind", "work", "--folder", str(self.folder)],
                              capture_output=True, text=True, stdin=subprocess.DEVNULL, env=environment)

    def test_without_a_key_it_says_how_to_store_one(self):
        tools = self.folder.parent / "tools"
        tools.mkdir()
        (tools / "security").write_text(TOOLS.format(python=sys.executable))
        (tools / "security").chmod(0o755)
        environment = {name: value for name, value in os.environ.items() if name not in ("DEEPSEEK_API_KEY", "KEYCHAIN_KEY")}
        environment |= {"PATH": f"{tools}{os.pathsep}{os.environ['PATH']}", "TOOL_CALLS": str(self.folder.parent / "calls.jsonl"), "HOME": str(self.folder.parent)}
        done = self.command(environment)
        self.assertEqual((done.returncode, done.stderr), (1, "The agent needs a DeepSeek key. Store it once with `uv run batch.py --store-key`\n"))

    def test_it_writes_through_the_model_api_and_says_how_to_try_the_new_rules(self):
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Stand)
        server.requests, server.replies = [], [completion(said(call("c1", "submit_rules", ANSWER)))]
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        done = self.command({**os.environ, "DEEPSEEK_API_KEY": "stand-in", "DEEPSEEK_BASE_URL": f"http://127.0.0.1:{server.server_address[1]}"})
        self.assertEqual((done.returncode, done.stderr), (0, ""))
        self.assertEqual(done.stdout.splitlines(), ["Kept rules/work-1.md; why each change was made is in rules/work-1.json:", "- Open the tests next to each changed file",
                                                    "To try it, add this setting to plan.json and run `uv run batch.py first`:",
                                                    '"quick-work-1": {"provider": "codex", "model": "small", "effort": "low", "policy": "rules/work-1.md"}'])
        self.assertEqual([tool["function"]["name"] for tool in server.requests[0]["tools"]], ["list_files", "read_file", "search", "submit_rules"])


if __name__ == "__main__":
    unittest.main()
