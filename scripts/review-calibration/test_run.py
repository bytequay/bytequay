import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

spec = importlib.util.spec_from_file_location("calibration", Path(__file__).with_name("run.py"))
calibration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(calibration)


class TestFrozenInput(unittest.TestCase):
    def test_export_excludes_dirty_files_and_future_history(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = root / "repo"
            repo.mkdir()
            def git(*args):
                return subprocess.check_output(["git", "-C", str(repo), *args], stderr=subprocess.DEVNULL).decode().strip()
            git("init")
            git("config", "user.email", "fixture@example.invalid")
            git("config", "user.name", "Fixture")
            source = repo / "rows.py"
            source.write_text("base\n")
            (repo / ".gitattributes").write_text("rows.py export-ignore\n")
            git("add", ".")
            git("commit", "-m", "base")
            base = git("rev-parse", "HEAD")
            source.write_text("head\n")
            git("commit", "-am", "head")
            head = git("rev-parse", "HEAD")
            source.write_text("future answer\n")
            git("commit", "-am", "future")
            source.write_text("dirty answer\n")
            (repo / "review-comments.txt").write_text("untracked answer\n")
            policy = root / "policy.md"
            policy.write_text("assigned policy\n")
            context = root / "context.md"
            context.write_text("frozen intent\n")
            bundle, manifest = calibration.freeze(repo, base, head, policy, context, root / "output")
            self.assertEqual((bundle / "base/rows.py").read_text(), "base\n")
            self.assertEqual((bundle / "head/rows.py").read_text(), "head\n")
            self.assertFalse(list(bundle.rglob(".git")))
            self.assertFalse(list(bundle.rglob("review-comments.txt")))
            self.assertNotIn("answer", (bundle / "change.diff").read_text())
            self.assertEqual(manifest["mergeBase"], base)
            self.assertIn("head/rows.py", manifest["files"])
            with self.assertRaises(FileExistsError):
                calibration.freeze(repo, base, head, policy, context, root / "output")

    def test_export_copies_blobs_byte_for_byte_and_rejects_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = root / "repo"
            repo.mkdir()
            def git(*args):
                return subprocess.check_output(["git", "-C", str(repo), *args], stderr=subprocess.DEVNULL).decode().strip()
            git("init")
            git("config", "user.email", "fixture@example.invalid")
            git("config", "user.name", "Fixture")
            files = {"empty": b"", "no-newline": b"last line", "tool.sh": b"#!/bin/sh\n",
                     "nested/odd name\t\u00df.bin": bytes(range(256)) * 1024}
            for name, content in files.items():
                (repo / name).parent.mkdir(exist_ok=True)
                (repo / name).write_bytes(content)
            (repo / "tool.sh").chmod(0o755)
            git("add", ".")
            git("commit", "-m", "files")
            calibration.export_tree(repo, "HEAD", root / "export")
            self.assertEqual({str(path.relative_to(root / "export")): path.read_bytes()
                              for path in (root / "export").rglob("*") if path.is_file()}, files)
            (repo / "link").symlink_to("empty")
            git("add", ".")
            git("commit", "-m", "link")
            with self.assertRaisesRegex(ValueError, "link"):
                calibration.export_tree(repo, "HEAD", root / "rejected")


class Output(unittest.TestCase):
    """A run's output folder, and what a command line would have left in it."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.output = Path(directory.name)

    def events(self, *types):
        (self.output / "events.jsonl").write_text("".join(json.dumps({"type": event}) + "\n" for event in types))

    def response(self, candidates):
        (self.output / "response.json").write_text(json.dumps({"candidates": candidates, "coverage": "Inspected rows.py", "limitations": ""}))


class TestCompletion(Output):
    def test_interrupted_zero_exit_is_not_completed(self):
        self.events("thread.started", "turn.started", "item.completed")
        self.assertEqual(calibration.completion_result(self.output, 0), {
            "status": "incomplete_output", "reason": "No successful turn.completed event was recorded"})
        self.response([])
        self.assertEqual(calibration.completion_result(self.output, 0)["status"], "incomplete_output")

    def test_completed_turn_requires_valid_response_including_empty_candidates(self):
        self.events("thread.started", "turn.started", "item.completed", "turn.completed")
        self.assertEqual(calibration.completion_result(self.output, 0)["status"], "incomplete_output")
        for candidates in [[], [{"responsibility": "Ownership", "hypothesis": "Reader may already be closed", "evidence": "rows.py:8", "nextCheck": "Trace caller"}]]:
            with self.subTest(candidates=candidates):
                self.response(candidates)
                self.assertEqual(calibration.completion_result(self.output, 0), {"status": "completed"})

    def test_malformed_response_is_not_completed(self):
        self.events("turn.started", "turn.completed")
        for response in ['{"candidates":', 'null', '{}',
                         '{"candidates": ["claim"], "coverage": "source", "limitations": ""}',
                         '{"candidates": [{"hypothesis": "claim"}], "coverage": "source", "limitations": ""}',
                         '{"candidates": [], "coverage": [], "limitations": ""}']:
            with self.subTest(response=response):
                (self.output / "response.json").write_text(response)
                self.assertEqual(calibration.completion_result(self.output, 0)["status"], "incomplete_output")

    def test_explicit_errors_and_nonzero_exit_fail_even_with_valid_response(self):
        self.response([])
        for event in ["turn.failed", "error"]:
            with self.subTest(event=event):
                self.events("turn.started", event, "turn.completed")
                self.assertEqual(calibration.completion_result(self.output, 0), {
                    "status": "failed", "reason": f"CLI recorded {event}"})
        self.events("turn.started", "turn.completed")
        self.assertEqual(calibration.completion_result(self.output, 1), {
            "status": "failed", "reason": "CLI exited with status 1"})

    def test_truncated_events_or_unfinished_later_turn_are_incomplete(self):
        self.response([])
        self.events("turn.started", "turn.completed", "turn.started")
        self.assertEqual(calibration.completion_result(self.output, 0)["status"], "incomplete_output")
        self.events("turn.started", "turn.completed")
        with (self.output / "events.jsonl").open("a") as events:
            events.write('{"type":')
        self.assertEqual(calibration.completion_result(self.output, 0)["status"], "incomplete_output")


class TestClaude(Output):
    RESULT = {"type": "result", "subtype": "success", "is_error": False}

    def lines(self, *events):
        (self.output / "events.jsonl").write_text("".join(json.dumps(event) + "\n" for event in events))

    def test_claude_run_is_completed_only_by_a_successful_result_and_a_valid_answer(self):
        self.lines({"type": "system", "subtype": "init"}, {"type": "assistant"}, self.RESULT)
        self.assertEqual(calibration.completion_result(self.output, 0, "claude")["status"], "incomplete_output")  # no answer was kept
        self.response([])
        self.assertEqual(calibration.completion_result(self.output, 0, "claude"), {"status": "completed"})
        self.assertEqual(calibration.completion_result(self.output, 1, "claude"), {"status": "failed", "reason": "CLI exited with status 1"})
        self.lines({"type": "system", "subtype": "init"}, {"type": "assistant"})
        self.assertEqual(calibration.completion_result(self.output, 0, "claude"), {"status": "incomplete_output", "reason": "No successful result event was recorded"})
        for unsuccessful, named in [({**self.RESULT, "subtype": "error_max_turns"}, "error_max_turns"), ({**self.RESULT, "is_error": True}, "success"),
                                    ({"type": "result", "subtype": "success"}, "success")]:
            self.lines(unsuccessful)
            self.assertEqual(calibration.completion_result(self.output, 0, "claude"), {"status": "failed", "reason": f"CLI recorded an unsuccessful result ({named})"})
        # What completes a Codex run says nothing about a Claude run, and the other way round.
        self.events("turn.started", "turn.completed")
        self.assertEqual(calibration.completion_result(self.output, 0, "claude")["status"], "incomplete_output")
        self.lines(self.RESULT)
        self.assertEqual(calibration.completion_result(self.output, 0)["status"], "incomplete_output")

    def test_claude_answer_is_taken_from_its_last_result(self):
        calibration.keep_claude_answer(self.output)  # no events at all
        self.lines({"type": "assistant"}, {**self.RESULT, "structured_output": {"candidates": ["early"]}}, {**self.RESULT, "structured_output": {"candidates": []}})
        calibration.keep_claude_answer(self.output)
        self.assertEqual(json.loads((self.output / "response.json").read_text()), {"candidates": []})
        (self.output / "response.json").unlink()
        self.lines(self.RESULT)  # a result without an answer leaves nothing to judge
        calibration.keep_claude_answer(self.output)
        self.assertFalse((self.output / "response.json").exists())

    def test_usage_adds_up_what_each_command_line_reports(self):
        nothing = {"inputTokens": 0, "cachedInputTokens": 0, "outputTokens": 0, "cost": None}
        self.assertEqual(calibration.usage(self.output, "codex"), nothing)
        turn = lambda read, cached, written: {"type": "turn.completed", "usage": {"input_tokens": read, "cached_input_tokens": cached, "output_tokens": written, "reasoning_output_tokens": 7}}
        (self.output / "events.jsonl").write_text(json.dumps({"type": "turn.started"}) + "\n" + json.dumps(turn(1000, 400, 50)) + "\n" + json.dumps(turn(200, 100, 10))
                                                  + '\n[1, 2]\n{"type": "turn.comp')  # the last line of a killed run is cut off
        self.assertEqual(calibration.usage(self.output, "codex"), {"inputTokens": 1200, "cachedInputTokens": 500, "outputTokens": 60, "cost": None})
        self.assertEqual(calibration.usage(self.output, "claude"), nothing)  # these are not Claude's events
        self.lines({"type": "assistant", "usage": {"input_tokens": 999}},
                   {**self.RESULT, "total_cost_usd": 0.25, "usage": {"input_tokens": 4, "cache_creation_input_tokens": 4982, "cache_read_input_tokens": 4811, "output_tokens": 183}})
        # Claude counts cached input apart, so tokens in is the three kinds of input together.
        self.assertEqual(calibration.usage(self.output, "claude"), {"inputTokens": 9797, "cachedInputTokens": 4811, "outputTokens": 183, "cost": 0.25})


ANSWER = {"candidates": [{"responsibility": "Ownership", "hypothesis": "Reader may be closed twice", "evidence": "rows.py:1", "nextCheck": "Trace the caller"}],
          "coverage": "Read rows.py", "limitations": ""}
# A stand-in for either command line. It says what it was given inside its own events, so a test can read it back.
FAKE = """#!{python}
import json, os, sys, time
if sys.argv[1:] == ["--version"]:
    print("9.9.9 (stand-in)")
    raise SystemExit
given = dict(argv=sys.argv[1:], cwd=os.getcwd(), pid=os.getpid(), prompt=sys.stdin.read(), kept=sorted(name for name in os.environ if name.startswith(("CLAUDE", "ANTHROPIC_"))))
answer = {answer}
if "slow" in sys.argv:
    print(json.dumps(dict(type="system", **given)), flush=True)
    time.sleep(60)
if "exec" in sys.argv:
    open(sys.argv[sys.argv.index("--output-last-message") + 1], "w").write(json.dumps(answer))
    events = [dict(type="turn.started", **given), dict(type="turn.completed", usage=dict(input_tokens=1000, cached_input_tokens=400, output_tokens=50))]
else:
    events = [dict(type="system", subtype="init", **given),
              dict(type="result", subtype="success", is_error=False, structured_output=answer, total_cost_usd=0.5,
                   usage=dict(input_tokens=10, cache_creation_input_tokens=20, cache_read_input_tokens=70, output_tokens=5))]
for event in events:
    print(json.dumps(event))
"""


class TestArm(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name).resolve()
        self.repo = self.root / "repo"
        self.repo.mkdir()
        git = lambda *args: subprocess.check_output(["git", "-C", str(self.repo), *args], stderr=subprocess.DEVNULL).decode().strip()
        git("init")
        git("config", "user.email", "fixture@example.invalid")
        git("config", "user.name", "Fixture")
        (self.repo / "rows.py").write_text("base\n")
        git("add", ".")
        git("commit", "-m", "base")
        (self.repo / "rows.py").write_text("head\n")
        git("commit", "-am", "head")
        (self.root / "policy.md").write_text("assigned policy\n")
        (self.root / "context.md").write_text("frozen intent\n")
        self.fake = self.root / "stand-in"
        self.fake.write_text(FAKE.format(python=sys.executable, answer=repr(ANSWER)))
        self.fake.chmod(0o755)

    def command(self, provider, model, effort, output, timeout):
        return [sys.executable, calibration.__file__, "--repo", str(self.repo), "--base", "HEAD~1", "--head", "HEAD", "--policy", str(self.root / "policy.md"),
                "--context", str(self.root / "context.md"), "--output", str(output), "--provider", provider, "--model", model, "--reasoning", effort,
                "--timeout", str(timeout), "--codex", str(self.fake), "--claude", str(self.fake)]

    def arm(self, provider, model, effort, name="run", timeout=30):
        output = self.root / name
        done = subprocess.run(self.command(provider, model, effort, output, timeout), capture_output=True, text=True,
                              env={**os.environ, "CLAUDE_EFFORT": "low", "ANTHROPIC_BASE_URL": "http://127.0.0.1:1"})
        runtime = json.loads((output / "runtime.json").read_text())
        given = json.loads((output / "events.jsonl").read_text().splitlines()[0]) if (output / "events.jsonl").read_text() else None
        return done, output, runtime, given

    def test_claude_arm_reads_the_frozen_input_with_read_only_tools_and_records_what_it_used(self):
        done, output, runtime, given = self.arm("claude", "opus", "max")
        self.assertEqual((done.returncode, done.stderr), (0, ""))
        self.assertEqual({name: runtime[name] for name in ("provider", "model", "effort", "status", "cliVersion", "usage")},
                         {"provider": "claude", "model": "opus", "effort": "max", "status": "completed", "cliVersion": "9.9.9 (stand-in)",
                          "usage": {"inputTokens": 100, "cachedInputTokens": 70, "outputTokens": 5, "cost": 0.5}})
        self.assertEqual(json.loads((output / "response.json").read_text()), ANSWER)
        self.assertEqual((given["cwd"], given["prompt"]), (str(output / "input"), (output / "prompt.txt").read_text()))  # it starts inside the frozen input
        self.assertEqual(given["kept"], [])  # nothing of a parent Claude session reaches the arm
        argv = given["argv"]
        for flag in ("--print", "--safe-mode", "--setting-sources=", "--strict-mcp-config", "--no-session-persistence", "--restricted"):
            self.assertIn(flag, argv)
        options = dict(zip(argv, argv[1:]))
        self.assertEqual((options["--tools"], options["--model"], options["--effort"], options["--output-format"], options["--mcp-config"]),
                         ("Read,Grep,Glob", "opus", "max", "stream-json", '{"mcpServers":{}}'))
        self.assertEqual(json.loads(options["--json-schema"]), json.loads((output / "schema.json").read_text()))
        self.assertEqual(runtime["command"], [str(self.fake), *argv])

    def test_codex_arm_keeps_its_own_options_and_records_what_it_used(self):
        done, output, runtime, given = self.arm("codex", "gpt-reviewer", "medium")
        self.assertEqual((done.returncode, done.stderr), (0, ""))
        self.assertEqual({name: runtime[name] for name in ("provider", "model", "effort", "status", "usage")},
                         {"provider": "codex", "model": "gpt-reviewer", "effort": "medium", "status": "completed",
                          "usage": {"inputTokens": 1000, "cachedInputTokens": 400, "outputTokens": 50, "cost": None}})
        argv = given["argv"]
        self.assertEqual((argv[0], argv[-1], argv[argv.index("--cd") + 1], argv[argv.index("--model") + 1]), ("exec", "-", str(output / "input"), "gpt-reviewer"))
        self.assertIn('model_reasoning_effort="medium"', argv)
        self.assertIn("--ignore-user-config", argv)
        self.assertEqual(given["prompt"], (output / "prompt.txt").read_text())

    def test_arm_that_runs_out_of_time_is_stopped_and_named_as_such(self):
        done, output, runtime, given = self.arm("claude", "slow", "max", timeout=1)
        self.assertEqual((done.returncode, runtime["status"], runtime["usage"]), (1, "timed_out", {"inputTokens": 0, "cachedInputTokens": 0, "outputTokens": 0, "cost": None}))
        self.assertLess(runtime["elapsedSeconds"], 10)
        self.assertFalse((output / "response.json").exists())

    def test_arm_stopped_from_outside_takes_its_command_line_down_with_it(self):
        output = self.root / "stopped"
        harness = subprocess.Popen(self.command("claude", "slow", "max", output, 60), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.addCleanup(harness.kill)
        events = output / "events.jsonl"
        for _ in range(100):  # until the stand-in has said who it is
            if events.exists() and events.read_text().strip():
                break
            time.sleep(0.1)
        stand_in = json.loads(events.read_text().splitlines()[0])["pid"]
        harness.send_signal(signal.SIGTERM)
        self.assertEqual(harness.wait(timeout=10), 143)
        for _ in range(50):
            try:
                os.kill(stand_in, 0)
            except ProcessLookupError:
                break
            time.sleep(0.1)
        else:
            os.kill(stand_in, signal.SIGKILL)
            self.fail("the command line went on running after its arm was stopped")
        self.assertEqual(json.loads((output / "runtime.json").read_text())["status"], "started")  # so the batch runs it again


class TestArguments(unittest.TestCase):
    def test_reasoning_must_be_explicit_before_any_provider_call(self):
        result = subprocess.run([sys.executable, calibration.__file__, "--repo", "unused", "--base", "base", "--head", "head",
                                 "--policy", "policy", "--context", "context", "--output", "output", "--model", "configured",
                                 "--codex", "/must-not-run"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn("required: --reasoning", result.stderr)

    def test_only_the_two_command_lines_and_the_labs_own_agent_can_run_an_arm(self):
        result = subprocess.run([sys.executable, calibration.__file__, "--repo", "unused", "--base", "base", "--head", "head", "--policy", "policy", "--context", "context",
                                 "--output", "output", "--model", "configured", "--reasoning", "high", "--provider", "another"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn("invalid choice: 'another'", result.stderr)


if __name__ == "__main__":
    unittest.main()
