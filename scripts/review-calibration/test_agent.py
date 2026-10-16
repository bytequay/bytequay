import copy
import http.server
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest

from judge import Client

import agent
import run

ANSWER = {"candidates": [{"responsibility": "Reading rows", "hypothesis": "The reader may be closed twice", "evidence": "head/src/rows.py:3",
                          "nextCheck": "Trace who closes it"}], "coverage": "Read rows.py and the diff", "limitations": ""}
USED = {"prompt_tokens": 1000, "completion_tokens": 50, "prompt_cache_hit_tokens": 600, "prompt_cache_miss_tokens": 400, "reasoning_tokens": 10}


def call(identity, name, arguments):
    """A tool call as the model API gives it: the arguments are a JSON string, which the model may get wrong."""
    return {"id": identity, "type": "function", "function": {"name": name, "arguments": arguments if isinstance(arguments, str) else json.dumps(arguments)}}


def said(*calls, content=None, reasoning=None):
    """A model message: some text, some thinking, and the tool calls it makes."""
    return {"role": "assistant", **({"content": content} if content else {}), **({"reasoning_content": reasoning} if reasoning else {}),
            **({"tool_calls": list(calls)} if calls else {})}


class Script:
    """A stand-in for the model: it gives its messages in order and keeps a copy of every conversation it was sent."""

    def __init__(self, *messages, used=USED):
        self.messages, self.used, self.sent, self.timeouts = list(messages), used, [], []

    def __call__(self, messages, offered, timeout):
        self.sent.append(copy.deepcopy(messages))
        self.timeouts.append(timeout)
        message = self.messages.pop(0)
        if isinstance(message, Exception):
            raise message
        return message, dict(self.used), "tool_calls" if message.get("tool_calls") else "stop"


class Folder(unittest.TestCase):
    """A frozen input folder, and a file beside it that no tool may reach."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name).resolve() / "input"
        (self.root / "head" / "src").mkdir(parents=True)
        (self.root / "head" / "src" / "rows.py").write_text("".join(f"line {number}\n" for number in range(1, 701)))
        (self.root / "head" / "src" / "Reader.java").write_text("class Reader {\n    void close() {}\n}\n")
        (self.root / "head" / "logo.png").write_bytes(b"\x89PNG\0\0close")
        (self.root / "change.diff").write_text("+    void close() {}\n")
        (self.root.parent / "secret.txt").write_text("close the vault\n")

    def tool(self, name, **arguments):
        return agent.call_tool(self.root, call("c1", name, arguments), run.problem_of)[0]


class TestTools(Folder):
    def test_listing_names_folders_with_a_slash_and_a_pattern_goes_down_the_tree(self):
        self.assertEqual(self.tool("list_files", path="."), "change.diff\nhead/")
        self.assertEqual(self.tool("list_files", path="head", pattern="**/*.java"), "head/src/Reader.java")
        self.assertEqual(self.tool("list_files", path="head", pattern="../../*"), "(no names)")  # what lies outside is never listed
        self.assertEqual(self.tool("list_files", path="head/src/rows.py"), "error: head/src/rows.py is not a folder")

    def test_reading_numbers_the_lines_and_says_what_is_left(self):
        self.assertEqual(self.tool("read_file", path="head/src/Reader.java", start=2, end=2), "     2:     void close() {}\n(3 lines in all)")
        shown = self.tool("read_file", path="head/src/rows.py").splitlines()
        self.assertEqual((len(shown), shown[0], shown[-3:]), (agent.LINES + 2, "     1: line 1",
                                                              ["   300: line 300", "(lines 301 to 700 not shown: read them with start 301)", "(700 lines in all)"]))
        self.assertTrue(self.tool("read_file", path="head/src/rows.py", start=690, end=900).endswith("   700: line 700\n(700 lines in all)"))
        for arguments, error in (({"path": "head/logo.png"}, "head/logo.png is not text"), ({"path": "head"}, "head is not a file"),
                                 ({"path": "head/src/rows.py", "start": 701}, "the file has 700 lines"),
                                 ({"path": "head/src/rows.py", "start": 0}, "start must be 1 or more, and end no less than start"),
                                 ({"path": "head/src/rows.py", "start": True}, "start must be a whole number"), ({}, "path must be a string")):
            self.assertEqual(self.tool("read_file", **arguments), f"error: {error}")

    def test_search_gives_path_line_and_text_and_skips_what_is_not_text(self):
        self.assertEqual(self.tool("search", pattern=r"close\(\)"), "change.diff:1: +    void close() {}\nhead/src/Reader.java:2: void close() {}")
        self.assertEqual(self.tool("search", pattern="close", glob="*.java"), "head/src/Reader.java:2: void close() {}")
        self.assertEqual(self.tool("search", pattern="close", glob="Reader.*"), "head/src/Reader.java:2: void close() {}")  # a pattern may name the file alone
        self.assertEqual(self.tool("search", pattern="close", path="head"), "head/src/Reader.java:2: void close() {}")  # the picture holds the word too
        many = self.tool("search", pattern="line", path="head/src").splitlines()
        self.assertEqual((len(many), many[-1]), (agent.MATCHES + 1, "(1 more matches not shown: narrow the request)"))  # it stops one past the limit
        self.assertEqual(self.tool("search", pattern="vault"), "(no matches)")
        self.assertTrue(self.tool("search", pattern="(").startswith("error: the pattern is not a regular expression"))

    def test_no_tool_reaches_outside_the_input_folder(self):
        for name, arguments in (("read_file", {"path": "../secret.txt"}), ("read_file", {"path": str(self.root.parent / "secret.txt")}),
                                ("list_files", {"path": ".."}), ("search", {"pattern": "vault", "path": "../secret.txt"})):
            with self.subTest(name=name, arguments=arguments):
                self.assertEqual(self.tool(name, **arguments), f"error: {arguments['path']} is outside the input folder")

    def test_a_wrong_call_comes_back_as_an_error_the_model_can_act_on(self):
        self.assertEqual(agent.call_tool(self.root, call("c1", "read_file", '{"path": "head'), run.problem_of), ("error: the arguments are not a JSON object", None))
        self.assertEqual(agent.call_tool(self.root, call("c1", "read_file", "[1]"), run.problem_of), ("error: the arguments are not a JSON object", None))
        self.assertEqual(self.tool("run_shell", command="ls"), "error: there is no tool named run_shell")
        self.assertEqual(agent.call_tool(self.root, call("c1", "submit_review", {"candidates": []}), run.problem_of),
                         ("error: the review must have exactly candidates, coverage and limitations. Call submit_review again with the whole review.", None))
        self.assertEqual(agent.call_tool(self.root, call("c1", "submit_review", ANSWER), run.problem_of), ("The review is handed in.", ANSWER))


class TestLoop(Folder):
    def review(self, script, deadline=float("inf"), **more):
        events = []
        done = agent.review(self.root, "Review the change.", run.SCHEMA, run.problem_of, script, deadline, events.append, **more)
        return done, events

    def test_a_run_reads_with_tools_then_hands_in_a_whole_review(self):
        script = Script(said(call("a", "list_files", {"path": "."}), reasoning="First see what is here."),
                        said(call("b", "read_file", {"path": "head/src/Reader.java"}), call("c", "search", {"pattern": "close"}), content="Two looks at once."),
                        said(call("d", "submit_review", {"candidates": "none", "coverage": "rows.py", "limitations": ""})),
                        said(call("e", "submit_review", ANSWER)))
        done, events = self.review(script)
        self.assertEqual((done.status, done.reason, done.answer, done.steps), ("completed", None, ANSWER, 4))
        self.assertEqual(done.usage, {"inputTokens": 4000, "cachedInputTokens": 2400, "outputTokens": 200, "cost": None})
        first, second, _, fourth = script.sent
        self.assertEqual([message["role"] for message in first], ["system", "user"])
        self.assertEqual((first[0]["content"], first[1]["content"]), (agent.SYSTEM, "Review the change."))
        # The model's own message goes back as it came, its thinking included, then the result of each call under the call's id.
        self.assertEqual(second[2], said(call("a", "list_files", {"path": "."}), reasoning="First see what is here."))
        self.assertEqual(second[3], {"role": "tool", "tool_call_id": "a", "content": "change.diff\nhead/"})
        self.assertEqual([message["tool_call_id"] for message in fourth if message["role"] == "tool"], ["a", "b", "c", "d"])
        self.assertEqual(fourth[-1]["content"], "error: candidates must be a list. Call submit_review again with the whole review.")
        self.assertEqual([(event["type"], event.get("name")) for event in events],
                         [("model", None), ("tool", "list_files"), ("model", None), ("tool", "read_file"), ("tool", "search"), ("model", None), ("tool", "submit_review"),
                          ("model", None), ("tool", "submit_review"), ("result", None)])
        self.assertEqual(events[2]["text"], "Two looks at once.")
        self.assertEqual(events[6]["error"], "candidates must be a list. Call submit_review again with the whole review.")
        self.assertEqual(events[-1], {"type": "result", "status": "completed", "reason": None, "steps": 4, "usage": done.usage})

    def test_a_reply_without_a_tool_call_is_asked_to_go_on(self):
        script = Script(said(content="I think it is fine."), said(call("a", "submit_review", ANSWER)))
        done, _ = self.review(script)
        self.assertEqual((done.status, script.sent[1][-2:]), ("completed", [said(content="I think it is fine."), {"role": "user", "content": agent.NUDGE}]))

    def test_the_last_step_asks_for_the_review_and_a_run_without_one_is_incomplete(self):
        script = Script(*(said(call(f"c{step}", "list_files", {"path": "."})) for step in range(3)))
        done, events = self.review(script, max_steps=3)
        self.assertEqual((done.status, done.reason, done.steps, done.answer), ("incomplete_output", "no whole review after 3 model calls", 3, None))
        self.assertEqual([conversation[-1] for conversation in script.sent][2], {"role": "user", "content": agent.WRAP_UP})
        self.assertNotIn({"role": "user", "content": agent.WRAP_UP}, script.sent[1])
        self.assertEqual(events[-1]["status"], "incomplete_output")

    def test_a_conversation_near_the_limit_gets_one_more_step_to_hand_the_review_in(self):
        script = Script(said(call("a", "list_files", {"path": "."})), said(call("b", "list_files", {"path": "."})), said(call("c", "list_files", {"path": "."})),
                        used={**USED, "prompt_tokens": agent.CONTEXT_LIMIT})
        done, _ = self.review(script)
        self.assertEqual((done.status, done.steps, script.sent[1][-1]), ("incomplete_output", 2, {"role": "user", "content": agent.WRAP_UP}))

    def test_the_deadline_ends_a_run_and_each_call_gets_the_time_that_is_left(self):
        times = iter([0.0, 0.0, 1.0, 1.0, 1.0, 100.0])
        script = Script(said(call("a", "list_files", {"path": "."})), said(call("b", "list_files", {"path": "."})))
        done, _ = self.review(script, deadline=50.0, clock=lambda: next(times))
        self.assertEqual((done.status, done.reason, done.steps, script.timeouts), ("timed_out", "the deadline passed after 1 model calls", 1, [50.0]))
        self.assertEqual(self.review(Script(), deadline=0.0)[0].status, "timed_out")

    def test_a_model_api_that_breaks_off_fails_the_run_and_keeps_what_was_spent(self):
        script = Script(said(call("a", "list_files", {"path": "."})), ConnectionError("the connection was reset"))
        done, events = self.review(script)
        self.assertEqual((done.status, done.reason, done.usage["inputTokens"]), ("failed", "ConnectionError: the connection was reset", 1000))
        self.assertEqual(events[-1]["status"], "failed")


class Stand(http.server.BaseHTTPRequestHandler):
    """A stand-in for DeepSeek's chat API on this machine: it answers in order and keeps every request it was sent."""

    def do_POST(self):
        self.server.requests.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
        body = json.dumps(self.server.replies.pop(0)).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *arguments):
        pass


def completion(message, model="deepseek-v4-pro"):
    return {"id": "x", "object": "chat.completion", "created": 0, "model": model,
            "choices": [{"index": 0, "finish_reason": "tool_calls" if "tool_calls" in message else "stop", "message": message}],
            "usage": {"prompt_tokens": 1000, "completion_tokens": 50, "total_tokens": 1050, "prompt_cache_hit_tokens": 600, "prompt_cache_miss_tokens": 400,
                      "completion_tokens_details": {"reasoning_tokens": 10}}}


class Served(Folder):
    def serve(self, *replies):
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Stand)
        server.requests, server.replies = [], list(replies)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return server, f"http://127.0.0.1:{server.server_address[1]}"


class TestModelApi(Served):
    def test_the_sdk_sends_the_tools_and_hands_the_models_message_back_whole(self):
        server, address = self.serve(completion(said(call("a", "list_files", {"path": "."}), reasoning="Look first.")), completion(said(call("b", "submit_review", ANSWER))))
        ask = agent.asker(Client(api_key="stand-in", base_url=address), "deepseek-v4-pro")
        done = agent.review(self.root, "Review the change.", run.SCHEMA, run.problem_of, ask, float("inf"))
        self.assertEqual((done.status, done.answer, done.usage["cachedInputTokens"]), ("completed", ANSWER, 1200))
        first, second = server.requests
        self.assertEqual(([tool["function"]["name"] for tool in first["tools"]], first["model"]), (["list_files", "read_file", "search", "submit_review"], "deepseek-v4-pro"))
        self.assertEqual(first["tools"][3]["function"]["parameters"], run.SCHEMA)
        self.assertEqual(second["messages"][2], said(call("a", "list_files", {"path": "."}), reasoning="Look first."))
        self.assertEqual(second["messages"][3], {"role": "tool", "tool_call_id": "a", "content": "change.diff\nhead/"})

    def test_an_answer_from_another_model_fails_the_run(self):
        _, address = self.serve(completion(said(call("a", "list_files", {"path": "."})), model="deepseek-flash"))
        done = agent.review(self.root, "Review.", run.SCHEMA, run.problem_of, agent.asker(Client(api_key="stand-in", base_url=address), "deepseek-v4-pro"),
                            float("inf"))
        self.assertEqual((done.status, done.reason), ("failed", "RuntimeError: Asked deepseek-v4-pro, but deepseek-flash answered"))


class TestArm(Served):
    def setUp(self):
        super().setUp()
        self.repo = self.root.parent / "repo"
        self.repo.mkdir()
        git = lambda *args: subprocess.check_output(["git", "-C", str(self.repo), *args], stderr=subprocess.DEVNULL)
        git("init")
        git("config", "user.email", "fixture@example.invalid")
        git("config", "user.name", "Fixture")
        (self.repo / "rows.py").write_text("base\n")
        git("add", ".")
        git("commit", "-m", "base")
        (self.repo / "rows.py").write_text("head\n")
        git("commit", "-am", "head")
        (self.root.parent / "policy.md").write_text("assigned policy\n")
        (self.root.parent / "context.md").write_text("frozen intent\n")

    def arm(self, output, environment):
        return subprocess.run([sys.executable, run.__file__, "--repo", str(self.repo), "--base", "HEAD~1", "--head", "HEAD", "--policy", str(self.root.parent / "policy.md"),
                               "--context", str(self.root.parent / "context.md"), "--output", str(output), "--provider", "agent", "--model", "deepseek-v4-pro",
                               "--reasoning", "default"], capture_output=True, text=True, env=environment)

    def test_agent_arm_reviews_the_frozen_input_and_records_what_it_used(self):
        server, address = self.serve(completion(said(call("a", "read_file", {"path": "head/rows.py"}))), completion(said(call("b", "submit_review", ANSWER))))
        output = self.root.parent / "run"
        done = self.arm(output, {**os.environ, "DEEPSEEK_API_KEY": "stand-in", "DEEPSEEK_BASE_URL": address})
        self.assertEqual((done.returncode, done.stderr), (0, ""))
        runtime = json.loads((output / "runtime.json").read_text())
        self.assertEqual({name: runtime[name] for name in ("provider", "model", "effort", "command", "cliVersion", "status", "steps", "usage")},
                         {"provider": "agent", "model": "deepseek-v4-pro", "effort": "default", "command": None, "cliVersion": f"agent.py {agent.VERSION}",
                          "status": "completed", "steps": 2, "usage": {"inputTokens": 2000, "cachedInputTokens": 1200, "outputTokens": 100, "cost": None}})
        self.assertEqual(json.loads((output / "response.json").read_text()), ANSWER)
        first = server.requests[0]
        self.assertEqual(first["messages"][1]["content"], (output / "prompt.txt").read_text())  # the same prompt the command lines get
        self.assertEqual(server.requests[1]["messages"][3], {"role": "tool", "tool_call_id": "a", "content": "     1: head\n(1 lines in all)"})
        events = [json.loads(line) for line in (output / "events.jsonl").read_text().splitlines()]
        self.assertEqual([event["type"] for event in events], ["model", "tool", "model", "tool", "result"])

    def test_agent_arm_without_a_key_stops_before_freezing_anything(self):
        output = self.root.parent / "unkeyed"
        done = self.arm(output, {name: value for name, value in os.environ.items() if name != "DEEPSEEK_API_KEY"})
        self.assertEqual((done.returncode, "--provider agent needs DEEPSEEK_API_KEY" in done.stderr, output.exists()), (2, True, False))


if __name__ == "__main__":
    unittest.main()
