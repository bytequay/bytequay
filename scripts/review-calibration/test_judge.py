import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

import judge

CASE = {"repo": "fixture", "number": 0, "head": None, "comments": [
    {"id": 11, "path": "rows.py", "line": 20, "hunk": "@@ -5 +17 @@\n+        yield LazyRows(source)", "body": "The file is closed before the rows are read."},
    {"id": 12, "path": "rows.py", "line": 5, "hunk": "@@ -1 +5 @@\n+class LazyRows:", "body": "Give this class a docstring."}]}
LEADS = [{"id": "c1", "text": "Iteration after the context exits reads a closed file", "where": "rows.py:20"},
         {"id": "c2", "text": "The reader is consumed only once", "where": "rows.py:11"},
         {"id": "c3", "text": "Rows are cached without a size limit", "where": "rows.py:12"}]
TOKENS = {"prompt_tokens": 100, "completion_tokens": 10, "prompt_cache_hit_tokens": 60, "prompt_cache_miss_tokens": 40, "reasoning_tokens": 5}
MISSED = {"verdict": "MISSED", "leads": [], "reason": "no lead asks for this"}


def shown(user, lead):
    """The number the judge saw in front of this lead."""
    return int(re.findall(r"\[(\d+)\]", user[:user.index(lead["text"])])[-1])


def found(lead):
    return lambda user: {"verdict": "FOUND", "leads": [shown(user, lead)], "reason": "the same request"}


class Asked:
    """A fake model: each call gets the next scripted answer, and the call is remembered."""

    def __init__(self, *script):
        self.script = list(script)
        self.calls = []

    def __call__(self, system, user):
        self.calls.append((system, user))
        answer = self.script.pop(0)
        if callable(answer):
            answer = answer(user)
        if isinstance(answer, Exception):
            raise answer
        return judge.Reply(answer if isinstance(answer, str) else json.dumps(answer), dict(TOKENS))


class TestJudgeRun(unittest.TestCase):
    def test_found_names_the_lead_by_its_own_id_whatever_number_it_was_shown_as(self):
        for seed in range(4):
            with self.subTest(seed=seed):
                judgement = judge.judge_run(CASE, LEADS, Asked(found(LEADS[0]), MISSED), seed)
                self.assertEqual(judgement["verdicts"], [{"commentId": 11, "verdict": "FOUND", "leadIds": ["c1"], "reason": "the same request"},
                                                         {"commentId": 12, "verdict": "MISSED", "leadIds": [], "reason": "no lead asks for this"}])
                self.assertEqual(judgement["errors"], [])
                self.assertEqual(judgement["usage"], {"calls": 2, **{count: 2 * tokens for count, tokens in TOKENS.items()}})

    def test_every_call_shares_one_prefix_and_ends_with_its_comment(self):
        asked = Asked(MISSED, MISSED)
        judgement = judge.judge_run(CASE, LEADS, asked)
        (first_system, first), (second_system, second) = asked.calls
        self.assertEqual(first_system, second_system)
        self.assertIn("json", first_system.lower())
        self.assertEqual(judgement["system"], first_system)
        self.assertEqual(judgement["promptSha256"], hashlib.sha256(first_system.encode()).hexdigest())
        last_lead = max(first.index(lead["text"]) + len(lead["text"]) for lead in LEADS)
        self.assertEqual(first[:last_lead], second[:last_lead])
        for user, comment in zip((first, second), CASE["comments"]):
            self.assertGreater(user.index(comment["body"]), last_lead)
            for lead in LEADS:
                self.assertIn(lead["where"], user)

    def test_leads_are_numbered_from_one_in_an_order_the_seed_decides(self):
        orders = set()
        for seed in range(10):
            asked = Asked(MISSED, MISSED)
            self.assertEqual(judge.judge_run(CASE, LEADS, asked, seed)["seed"], seed)
            user = asked.calls[0][1]
            self.assertEqual(sorted(shown(user, lead) for lead in LEADS), [1, 2, 3])
            again = Asked(MISSED, MISSED)
            judge.judge_run(CASE, LEADS, again, seed)
            self.assertEqual(again.calls[0][1], user)
            orders.add(tuple(shown(user, lead) for lead in LEADS))
        self.assertGreater(len(orders), 1)

    def test_bad_answer_is_asked_again_and_a_second_one_is_an_error_not_a_miss(self):
        one = {**CASE, "comments": CASE["comments"][:1]}
        bad = ["not json", "", "[]", '"FOUND"', {"verdict": "MAYBE", "leads": [1], "reason": "x"}, {"verdict": "FOUND", "leads": [], "reason": "x"},
               {"verdict": "PARTLY", "leads": [], "reason": "x"}, {"verdict": "MISSED", "leads": [1], "reason": "x"}, {"verdict": "FOUND", "leads": [0], "reason": "x"},
               {"verdict": "FOUND", "leads": [4], "reason": "x"}, {"verdict": "FOUND", "leads": [1, 1], "reason": "x"}, {"verdict": "FOUND", "leads": ["1"], "reason": "x"},
               {"verdict": "FOUND", "leads": [True], "reason": "x"}, {"verdict": "FOUND", "leads": [1.5], "reason": "x"}, {"verdict": "FOUND", "leads": 1, "reason": "x"},
               {"verdict": "FOUND", "leads": [1]}, {"verdict": "FOUND", "leads": [1], "reason": 7}, {"leads": [], "reason": "x"}]
        for answer in bad:
            with self.subTest(answer=answer):
                recovered = judge.judge_run(one, LEADS, Asked(answer, found(LEADS[1])))
                self.assertEqual([(verdict["verdict"], verdict["leadIds"]) for verdict in recovered["verdicts"]], [("FOUND", ["c2"])])
                self.assertEqual((recovered["errors"], recovered["usage"]["calls"]), ([], 2))
                failed = judge.judge_run(one, LEADS, Asked(answer, answer))
                self.assertEqual((failed["verdicts"], [error["commentId"] for error in failed["errors"]], failed["usage"]["calls"]), ([], [11], 2))
                self.assertTrue(failed["errors"][0]["reason"])

    def test_cut_off_answer_from_the_model_is_asked_again_and_its_tokens_still_count(self):
        one = {**CASE, "comments": CASE["comments"][:1]}
        cut = judge.JudgeError("finish_reason length", {"prompt_tokens": 7, "completion_tokens": 3})
        recovered = judge.judge_run(one, LEADS, Asked(cut, MISSED))
        self.assertEqual(([verdict["verdict"] for verdict in recovered["verdicts"]], recovered["errors"]), (["MISSED"], []))
        self.assertEqual((recovered["usage"]["calls"], recovered["usage"]["prompt_tokens"], recovered["usage"]["completion_tokens"]), (2, 107, 13))
        failed = judge.judge_run(one, LEADS, Asked(cut, cut))
        self.assertEqual((failed["verdicts"], failed["errors"]), ([], [{"commentId": 11, "reason": "finish_reason length"}]))
        self.assertEqual((failed["usage"]["calls"], failed["usage"]["prompt_tokens"]), (2, 14))

    def test_any_other_failure_stops_the_run(self):
        with self.assertRaisesRegex(RuntimeError, "served by another model"):
            judge.judge_run(CASE, LEADS, Asked(RuntimeError("served by another model")))

    def test_no_leads_is_all_missed_without_asking(self):
        asked = Asked()
        judgement = judge.judge_run(CASE, [], asked)
        self.assertEqual([(verdict["commentId"], verdict["verdict"], verdict["leadIds"]) for verdict in judgement["verdicts"]], [(11, "MISSED", []), (12, "MISSED", [])])
        self.assertEqual((asked.calls, judgement["errors"], judgement["usage"]["calls"], judgement["calls"]), ([], [], 0, []))

    def test_every_call_is_kept_for_reading(self):
        asked = Asked("not json", MISSED, judge.JudgeError("empty"), judge.JudgeError("empty"))
        calls = judge.judge_run(CASE, LEADS, asked)["calls"]
        self.assertEqual([(call["commentId"], call["answer"], call["error"] is None) for call in calls],
                         [(11, "not json", False), (11, json.dumps(MISSED), True), (12, None, False), (12, None, False)])
        self.assertEqual([call["user"] for call in calls], [user for _, user in asked.calls])


class TestAskModel(unittest.TestCase):
    def client(self, content='{"verdict": "MISSED"}', finish="stop", model="deepseek-v4-pro", reasoning=5):
        detail = None if reasoning is None else SimpleNamespace(reasoning_tokens=reasoning)
        usage = SimpleNamespace(prompt_tokens=100, completion_tokens=10, prompt_cache_hit_tokens=60, prompt_cache_miss_tokens=40, completion_tokens_details=detail)
        response = SimpleNamespace(model=model, usage=usage, choices=[SimpleNamespace(finish_reason=finish, message=SimpleNamespace(content=content))])
        self.requests = []
        return SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=lambda **request: self.requests.append(request) or response)))

    def test_one_json_mode_request_with_both_messages(self):
        reply = judge.ask_model(self.client(), "the rules", "the leads and the comment", "deepseek-v4-pro")
        self.assertEqual((reply.text, reply.usage), ('{"verdict": "MISSED"}', TOKENS))
        (request,) = self.requests
        self.assertEqual((request["model"], request["response_format"]), ("deepseek-v4-pro", {"type": "json_object"}))
        self.assertEqual([(message["role"], message["content"]) for message in request["messages"]], [("system", "the rules"), ("user", "the leads and the comment")])

    def test_missing_reasoning_detail_counts_as_zero(self):
        self.assertEqual(judge.ask_model(self.client(reasoning=None), "s", "u", "deepseek-v4-pro").usage, {**TOKENS, "reasoning_tokens": 0})

    def test_cut_off_or_empty_answer_is_a_judge_error_that_keeps_its_tokens(self):
        for change in [{"finish": "length"}, {"finish": "content_filter"}, {"finish": "insufficient_system_resource"}, {"content": ""}, {"content": None}]:
            with self.subTest(change=change), self.assertRaises(judge.JudgeError) as refused:
                judge.ask_model(self.client(**change), "s", "u", "deepseek-v4-pro")
            self.assertEqual(refused.exception.usage, TOKENS)

    def test_answer_from_another_model_stops_the_run(self):
        self.assertTrue(judge.ask_model(self.client(model="deepseek-v4-pro-0813"), "s", "u", "deepseek-v4-pro").text)
        with self.assertRaises(RuntimeError):
            judge.ask_model(self.client(model="deepseek-flash"), "s", "u", "deepseek-v4-pro")


@unittest.skipUnless(importlib.util.find_spec("openai"), "needs the OpenAI SDK; run through uv")
class TestAskModelOverHttp(unittest.TestCase):
    """The real SDK against a local stand-in for DeepSeek, for what a fake client cannot show."""

    def client(self, finish="stop", content='{"verdict": "MISSED"}'):
        import httpx2
        import openai
        usage = {"prompt_tokens": 100, "completion_tokens": 10, "total_tokens": 110, "prompt_cache_hit_tokens": 60, "prompt_cache_miss_tokens": 40,
                 "completion_tokens_details": {"reasoning_tokens": 5}}
        body = {"id": "x", "object": "chat.completion", "created": 1, "model": "deepseek-v4-pro", "usage": usage,
                "choices": [{"index": 0, "finish_reason": finish, "message": {"role": "assistant", "content": content}}]}

        def answer(request):
            self.sent = json.loads(request.content)
            # DeepSeek sends blank lines while a request waits, before the JSON body.
            return httpx2.Response(200, content=("\n\n" + json.dumps(body)).encode(), headers={"content-type": "application/json"})
        return openai.OpenAI(api_key="not-a-real-key", base_url=judge.BASE_URL, max_retries=0, http_client=httpx2.Client(transport=httpx2.MockTransport(answer)))

    def test_sdk_keeps_the_token_counts_only_deepseek_sends_and_skips_its_keep_alive_lines(self):
        reply = judge.ask_model(self.client(), "the rules", "the leads and the comment", "deepseek-v4-pro")
        self.assertEqual((reply.text, reply.usage), ('{"verdict": "MISSED"}', TOKENS))
        self.assertEqual((sorted(self.sent), self.sent["response_format"]), (["messages", "model", "response_format"], {"type": "json_object"}))

    def test_finish_reason_only_deepseek_sends_is_a_judge_error_not_a_crash(self):
        with self.assertRaisesRegex(judge.JudgeError, "insufficient_system_resource"):
            judge.ask_model(self.client(finish="insufficient_system_resource", content=None), "s", "u", "deepseek-v4-pro")


@unittest.skipUnless(importlib.util.find_spec("openai"), "needs the OpenAI SDK; run through uv")
class TestMakeClient(unittest.TestCase):
    def test_missing_key_stops_with_the_variable_name(self):
        for environment in [{}, {"DEEPSEEK_API_KEY": ""}]:
            with self.subTest(environment=environment), mock.patch.dict(os.environ, environment, clear=True), self.assertRaises(SystemExit) as stopped:
                judge.make_client()
            self.assertIn("DEEPSEEK_API_KEY", str(stopped.exception))

    def test_client_talks_to_deepseek_with_the_key_from_the_environment(self):
        with mock.patch.dict(os.environ, {"DEEPSEEK_API_KEY": "not-a-real-key"}, clear=True):
            client = judge.make_client()
        self.assertTrue(str(client.base_url).startswith(judge.BASE_URL))
        self.assertEqual(client.api_key, "not-a-real-key")


class TestAgreement(unittest.TestCase):
    JUDGEMENT = {"verdicts": [{"commentId": 11, "verdict": "FOUND"}, {"commentId": 12, "verdict": "MISSED"}, {"commentId": 13, "verdict": "PARTLY"}],
                 "errors": [{"commentId": 14, "reason": "empty"}]}

    def test_only_comments_with_a_verdict_and_a_label_are_compared(self):
        self.assertEqual(judge.agreement(self.JUDGEMENT, {"11": "FOUND", "12": "PARTLY", "14": "FOUND", "15": "MISSED"}),
                         {"compared": 2, "agree": 1, "rate": 0.5, "disagreements": [{"commentId": 12, "judge": "MISSED", "label": "PARTLY"}],
                          "unjudged": ["14", "15"], "unlabelled": [13]})

    def test_nothing_to_compare_has_no_rate(self):
        self.assertEqual(judge.agreement(self.JUDGEMENT, {}),
                         {"compared": 0, "agree": 0, "rate": None, "disagreements": [], "unjudged": [], "unlabelled": [11, 12, 13]})

    def test_mistyped_label_is_refused_instead_of_counted_as_a_disagreement(self):
        with self.assertRaisesRegex(ValueError, "comment 12 is 'FOUNDD'"):
            judge.agreement(self.JUDGEMENT, {"11": "FOUND", "12": "FOUNDD"})


class TestLeads(unittest.TestCase):
    def test_run_candidates_become_numbered_leads_and_a_list_is_taken_as_it_is(self):
        response = {"candidates": [{"responsibility": "r", "hypothesis": "May read a closed file", "evidence": "rows.py:20", "nextCheck": "n"}], "coverage": "", "limitations": ""}
        self.assertEqual(judge.leads_of(response), [{"id": "c1", "text": "May read a closed file", "where": "rows.py:20"}])
        self.assertEqual(judge.leads_of(LEADS), LEADS)

    def test_oracle_turns_the_answer_key_into_leads(self):
        self.assertEqual(judge.oracle(CASE)[0], {"id": "o11", "text": "The file is closed before the rows are read.", "where": "rows.py:20"})


class TestCommandLine(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        (self.root / "case.json").write_text(json.dumps({**CASE, "head": "aaa"}))
        self.run_directory = self.root / "run"
        self.run_directory.mkdir()

    def write_run(self, status="completed", head="aaa", candidates=()):
        (self.run_directory / "runtime.json").write_text(json.dumps({"status": status}))
        (self.run_directory / "manifest.json").write_text(json.dumps({"head": head}))
        (self.run_directory / "response.json").write_text(json.dumps({"candidates": list(candidates), "coverage": "", "limitations": ""}))

    def judge(self, *arguments):
        return subprocess.run([sys.executable, judge.__file__, *arguments], capture_output=True, text=True, env={"PATH": ""})

    def test_failed_run_other_commit_and_judged_run_are_refused_before_any_call(self):
        for change, message in [({"status": "timed_out"}, "not judged"), ({"head": "bbb"}, "another commit")]:
            with self.subTest(change=change):
                self.write_run(**change)
                result = self.judge("run", "--case", str(self.root / "case.json"), "--run", str(self.run_directory))
                self.assertEqual(result.returncode, 2)
                self.assertIn(message, result.stderr)
        self.write_run()
        (self.run_directory / "judgement.json").write_text("{}")
        result = self.judge("run", "--case", str(self.root / "case.json"), "--run", str(self.run_directory))
        self.assertIn("already judged", result.stderr)
        result = self.judge("run", "--case", str(self.root / "case.json"), "--oracle")
        self.assertIn("--output is required", result.stderr)

    def test_completed_run_without_leads_is_judged_all_missed_and_needs_no_key(self):
        self.write_run()
        result = self.judge("run", "--case", str(self.root / "case.json"), "--run", str(self.run_directory))
        self.assertEqual(result.returncode, 0, result.stderr)
        judgement = json.loads((self.run_directory / "judgement.json").read_text())
        self.assertEqual((judgement["case"], judgement["judgeModel"], judgement["leads"]), ("fixture#0", judge.MODEL, 0))
        self.assertEqual([verdict["verdict"] for verdict in judgement["verdicts"]], ["MISSED", "MISSED"])
        self.assertEqual(json.loads((self.run_directory / "judge-calls.json").read_text())["calls"], [])
        self.assertEqual(json.loads(result.stdout)["MISSED"], 2)

    def test_two_judgements_of_one_run_are_compared_to_count_flips(self):
        first, second = self.root / "first.json", self.root / "second.json"
        first.write_text(json.dumps({"verdicts": [{"commentId": 11, "verdict": "FOUND"}, {"commentId": 12, "verdict": "MISSED"}], "errors": []}))
        second.write_text(json.dumps({"verdicts": [{"commentId": 11, "verdict": "PARTLY"}, {"commentId": 12, "verdict": "MISSED"}], "errors": []}))
        result = self.judge("agree", "--judgement", str(first), "--labels", str(second))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((json.loads(result.stdout)["compared"], json.loads(result.stdout)["agree"]), (2, 1))


if __name__ == "__main__":
    unittest.main()
