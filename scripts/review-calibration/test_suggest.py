from datetime import date, datetime, timezone
from pathlib import Path
import unittest

import activity
import approvals
import charts
import suggest

UTC = timezone.utc
FIRST, LAST = date(2026, 1, 5), date(2026, 1, 11)  # one week, so a week's minutes are the minutes seen
ROOT = Path(__file__).resolve().parents[2]


def row(start="2026-01-05T10:00:00Z", seconds=600.0, provider="codex", task="task-1", after=None, calls=(), hops=(), long_runs=(), logged=True):
    """One saved turn with only what the suggestions read; the rest is what every saved row carries."""
    return {"task": task, "start": start, "seconds": seconds, "logged": logged, "outcome": "completed", "provider": provider, "model": "m", "effort": "high",
            "repository": "o/r", "inputTokens": 1, "cachedInputTokens": 0, "outputTokens": 1, "cost": None, "toolCalls": 1, "compactions": 0,
            "groups": {activity.SILENT: seconds}, "programs": {}, "after": after or {}, "parallel": 0.0, "mostAtOnce": 0, "unfinished": 0,
            "longRuns": [{"program": name, "seconds": length} for name, length in long_runs], "approvals": [], "notices": {}, "calls": list(calls), "hops": list(hops),
            "repeats": []}


def table(*rows):
    return {"version": 1, "savedAt": "2026-01-12T00:00:00Z", "since": None, "task": None, "database": {"turns": len(rows), "matched": len(rows)},
            "leftOut": {"skippedLines": 0, "unreadLogs": 0}, "turns": list(rows)}


def maven(runs, failed, seconds, reasons):
    return {"kind": "command", "name": "maven", "runs": runs, "failed": failed, "noMatch": 0, "seconds": seconds, "characters": 10, "reasons": reasons}


def review(start, seconds, repaired):
    return {"review": "r", "repository": "o/r", "start": start, "seconds": seconds, "state": "accepted", "repaired": repaired, "turns": 1, "provider": "codex",
            "model": "m", "effort": "high", "inputTokens": 1, "cachedInputTokens": 0, "outputTokens": 1, "cost": None}


def ask(line, provider="codex", day=5, seconds=60.0, outcome="allowed", stopped=False, what="command"):
    return approvals.Ask("task-1", datetime(2026, 1, day, 12, tzinfo=UTC).timestamp(), provider, what, line, outcome, seconds, stopped)


def data(rows=(row(),), asks=(), reviews=None, first=FIRST, last=LAST):
    saved = table(*rows)
    made = charts.frames(saved, UTC)
    found = None if reviews is None else charts.review_frames({"version": 1, "savedAt": saved["savedAt"], "since": None, "task": None, "runs": reviews, "findings": []}, UTC)
    return suggest.window(made, charts.call_frames(saved), found, asks, first, last, UTC)


def titled(found):
    return [(one.title, round(one.saves, 1)) for one in found]


class TestWindow(unittest.TestCase):
    def test_a_window_keeps_the_turns_and_asks_of_its_days_only(self):
        made = data([row("2026-01-04T10:00:00Z"), row("2026-01-05T10:00:00Z"), row("2026-01-11T23:00:00Z"), row("2026-01-12T00:30:00Z")],
                    [ask("ls", day=4), ask("ls", day=5), ask("ls", day=12)])
        self.assertEqual((len(made.made["turns"]), len(made.asks), made.days), (2, 1, 7))
        self.assertEqual(made.hours(), 20 / 60)


class TestRules(unittest.TestCase):
    def test_a_rule_that_answers_asks_by_itself_is_suggested_with_the_risks_it_lets_through(self):
        asks = [ask("./mvnw -o -pl core test > /tmp/a.log", seconds=120, stopped=True), ask("./mvnw -o -pl web test", seconds=60),
                ask("gh api user", seconds=600), ask("cd x && ./mvnw -o -pl core test", provider="claude"), ask("cd y && ./mvnw -o -pl web test", provider="claude")]
        (found,) = suggest.allow_rules(data(asks=asks))
        self.assertEqual((found.title, found.saves), ("Let Codex run maven without asking", 3.0))
        self.assertEqual(found.seen, "2 of 3 Codex command asks match 1 rule: Bash(./mvnw -o -pl:*). You waited 3.0 minutes for them, and 1 ask ran out and ended the turn. "
                                     "The last was on 5 Jan. Risks among them: writes a file 1.")
        self.assertEqual(found.where, ((suggest.CODEX_DRIVER, "registerApproval"),))
        self.assertEqual(suggest.value(found.measure(data(asks=asks))), 3 / (10 / 60), "Codex command asks in an agent hour")

    def test_a_rule_that_answers_only_one_ask_is_not_suggested(self):
        self.assertEqual(suggest.allow_rules(data(asks=[ask("./mvnw -o test"), ask("gh api user"), ask("curl x")])), [])

    def test_asks_that_ended_their_turn_ask_to_be_seen_in_time(self):
        asks = [ask("curl x", seconds=300, outcome="expired", stopped=True), ask(None, provider="claude", what="question", seconds=300, outcome="expired", stopped=True),
                ask("make", provider="claude", seconds=300, outcome="expired"), ask("ls")]
        (found,) = suggest.ran_out(data(asks=asks))
        self.assertEqual((found.title, found.saves), ("Make sure an ask is seen before it runs out", 10.0))
        self.assertEqual(found.seen, "2 asks ran out after 5 minutes with no answer and ended their turn: 1 Codex command, 1 Claude question. The last was on 5 Jan.")
        self.assertEqual(suggest.value(found.measure(data(asks=asks))), 50.0)
        self.assertEqual(suggest.ran_out(data(asks=[ask("make", outcome="expired")])), [])

    def test_each_avoidable_failure_reason_is_its_own_suggestion(self):
        rows = [row(calls=[maven(10, 8, 600.0, {"no test matched": 4, activity.NOTHING_KEPT: 3, "test failure": 1})]),
                row(provider="claude", task="task-2", calls=[maven(10, 0, 600.0, {})])]
        found = suggest.failing(data(rows))
        self.assertEqual(titled(found), [("Fix how agents run maven: no output kept", 3.0), ("Fix how agents run maven: no test matched", 4.0)])
        self.assertEqual(found[1].seen, "maven ran 20 times and failed 8 times; 4 of the failures were \"no test matched\". It failed in 80% of runs under codex and 0% under claude, "
                                        "so how it is run matters more than the code.")
        self.assertEqual(found[1].advice, suggest.MAVEN_NO_TEST)
        self.assertEqual(found[0].advice, suggest.FIXES[activity.NOTHING_KEPT])
        self.assertEqual(suggest.value(found[1].measure(data(rows))), 40.0)
        close = [row(calls=[maven(10, 6, 600.0, {"no test matched": 6})]), row(provider="claude", task="task-2", calls=[maven(10, 4, 600.0, {"no test matched": 4})])]
        self.assertEqual(suggest.failing(data(close))[0].seen, "maven ran 20 times and failed 10 times; 10 of the failures were \"no test matched\".",
                         "60% against 40% is no gap to speak of")

    def test_failures_that_are_the_work_itself_or_too_few_say_nothing(self):
        self.assertEqual(suggest.failing(data([row(calls=[maven(10, 9, 600.0, {"test failure": 6, "compile error": 3})])])), [])
        self.assertEqual(suggest.failing(data([row(calls=[maven(10, 2, 600.0, {"no test matched": 2})])])), [], "fewer than three failures of one reason")
        self.assertEqual(suggest.failing(data([row(calls=[maven(4, 4, 600.0, {"no test matched": 4})])])), [], "fewer runs than a rate needs")

    def test_a_slow_first_step_counts_its_time_above_the_floor(self):
        rows = [row(f"2026-01-0{day}T10:00:00Z", after={suggest.START: {"seconds": 80.0, "times": 1}}) for day in range(5, 10)]
        (found,) = suggest.turn_start(data(rows))
        self.assertEqual((found.title, found.saves), ("Shorten what a turn starts with", 5.0))
        self.assertEqual(suggest.value(found.measure(data(rows))), 80.0)
        quick = [row(f"2026-01-0{day}T10:00:00Z", after={suggest.START: {"seconds": 40.0, "times": 1}}) for day in range(5, 10)]
        self.assertEqual(suggest.turn_start(data(quick)), [])
        self.assertEqual(suggest.turn_start(data(rows[:4])), [], "too few turns")

    def test_short_hops_between_two_bytequay_calls_are_what_merging_could_save(self):
        hops = [{"from": activity.BYTEQUAY, "to": activity.BYTEQUAY, "gap": activity.GAPS[1], "times": 20, "seconds": 180.0},
                {"from": activity.BYTEQUAY, "to": activity.BYTEQUAY, "gap": activity.GAPS[2], "times": 5, "seconds": 600.0},
                {"from": activity.SHELL, "to": activity.BYTEQUAY, "gap": activity.GAPS[1], "times": 9, "seconds": 900.0}]
        rows = [row(after={"after " + activity.BYTEQUAY: {"seconds": 250.0, "times": 25}}, hops=hops)]
        (found,) = suggest.after_tools(data(rows))
        self.assertEqual((found.title, found.saves), ("Let one ByteQuay tool call return what the next one fetches", 3.0))
        self.assertIn("20 ByteQuay tool calls within 10 seconds of the last one's result: 3.0 minutes. After a ByteQuay tool result it took 10 seconds", found.seen)
        self.assertEqual(suggest.after_tools(data([row(hops=hops[1:])])), [])

    def test_long_runs_count_their_time_above_the_long_mark_with_advice_for_their_kind(self):
        rows = [row(long_runs=[("maven", 330.0), ("maven", 330.0), ("wait loop", 630.0), ("git", 90.0)])]
        found = suggest.long_runs(data(rows))
        self.assertEqual(titled(found), [("Make long maven runs shorter", 10.0), ("Make long wait loop runs shorter", 10.0)])
        self.assertTrue(found[0].advice.startswith(suggest.LONG_ADVICE["build"]))
        self.assertTrue(found[1].advice.startswith(suggest.LONG_ADVICE["wait loop"]))
        self.assertEqual(suggest.value(found[0].measure(data(rows))), 11 / (10 / 60))
        self.assertEqual(titled(suggest.long_runs(data(rows, last=date(2026, 1, 18)))), [], "two weeks halve the minutes a week, below the limit")
        self.assertEqual(titled(suggest.long_runs(data(rows * 2, last=date(2026, 1, 18)))), [("Make long maven runs shorter", 10.0), ("Make long wait loop runs shorter", 10.0)])

    def test_review_runs_asked_again_count_their_extra_typical_time(self):
        runs = [review(f"2026-01-0{day}T10:00:00Z", 1800.0, True) for day in (5, 6, 7)] + [review(f"2026-01-0{day}T10:00:00Z", 600.0, False) for day in (5, 6, 7, 8)]
        (found,) = suggest.asked_again(data(reviews=runs))
        self.assertEqual((found.title, found.saves), ("Get a complete review report the first time", 60.0))
        self.assertTrue(found.seen.startswith("3 of 7 review runs ended without a complete report, so ByteQuay asked again. Those runs took 30 minutes typically, the others 10."))
        self.assertAlmostEqual(suggest.value(found.measure(data(reviews=runs))), 300 / 7)
        self.assertEqual(suggest.asked_again(data(reviews=runs[1:])), [], "fewer than three asked again")
        self.assertEqual(suggest.asked_again(data()), [], "no reviews saved")


class TestSuggestions(unittest.TestCase):
    def test_the_list_is_ranked_by_what_it_could_save_and_small_ones_are_left_out(self):
        rows = [row(long_runs=[("maven", 330.0), ("maven", 330.0), ("git", 140.0)])]
        asks = [ask("curl x", seconds=300, outcome="expired", stopped=True), ask("./mvnw -o a", seconds=30), ask("./mvnw -o b", seconds=30)]
        self.assertEqual(titled(suggest.suggestions(data(rows, asks))), [("Make long maven runs shorter", 10.0), ("Make sure an ask is seen before it runs out", 5.0)])

    def test_the_number_to_watch_comes_with_its_range_once_enough_tasks_stand_behind_it(self):
        rows = [row(f"2026-01-0{day}T10:00:00Z", task=f"task-{day}-{one}", long_runs=[("maven", 330.0 + 30 * one)]) for day in range(1, 5) for one in range(3)]
        saved = table(*rows)
        window = suggest.window(charts.frames(saved, UTC), charts.call_frames(saved), None, (), date(2026, 1, 1), date(2026, 1, 4), UTC)
        (found,) = suggest.long_runs(window)
        self.assertEqual(suggest.now(found.measure(window)), "36.0 (34.8 to 37.5)",
                         "twelve tasks of 33, 36 and 39 an hour: the range is narrower than one task to the next")

    def test_every_place_named_is_still_in_bytequay(self):
        if not (ROOT / "backend").is_dir():
            self.skipTest("the application's source is not beside the lab")
        places ={place for _, where, _ in suggest.PROVIDERS.values() for place in [where]}
        places |= {(suggest.CODEX_DRIVER, "APPROVAL_TIMEOUT"), (suggest.PERMISSIONS, "APPROVAL_TIMEOUT"), (suggest.INSTRUCTIONS, "compose"), (suggest.CONTEXT, "ContextBuildService"),
                   (suggest.MEMORY, "MemoryDelivery"), (suggest.GUIDELINES, "MAX_TOTAL_BYTES"), (suggest.REVIEW_TOOLS, "ReviewTools"), (suggest.PERMISSIONS, "CALL_TOOL"),
                   (suggest.REVIEW_SERVICE, "repairAssignment")}
        found = suggest.suggestions(data([row(after={suggest.START: {"seconds": 300.0, "times": 1}}, long_runs=[("maven", 900.0)],
                                              hops=[{"from": activity.BYTEQUAY, "to": activity.BYTEQUAY, "gap": activity.GAPS[1], "times": 99, "seconds": 900.0}],
                                              calls=[maven(10, 9, 3000.0, {"no test matched": 9})])] * 5,
                                             [ask("./mvnw -o a", seconds=300), ask("./mvnw -o b", seconds=300), ask("x", seconds=300, outcome="expired", stopped=True)],
                                             [review("2026-01-05T10:00:00Z", 3600.0, True)] * 3 + [review("2026-01-05T10:00:00Z", 60.0, False)]))
        self.assertEqual(len(found), 7, "one of every kind")
        self.assertLessEqual({place for one in found for place in one.where if place[0]}, places)
        for path, name in places:
            with self.subTest(path=path):
                self.assertIn(name, (ROOT / path).read_text())

    def test_the_text_says_what_where_and_what_to_watch_and_names_no_command_argument(self):
        asks = [ask("./mvnw -o -pl secret-module test > /tmp/private.log", seconds=300), ask("./mvnw -o -pl other-module test", seconds=300)]
        made = data(asks=asks)
        (found,) = suggest.suggestions(made)
        self.assertEqual((found.title, found.saves), ("Let Codex run maven without asking", 10.0))
        self.assertIn((suggest.CODEX_DRIVER, "registerApproval"), found.where)
        self.assertEqual((found.watch, suggest.now(found.measure(made))), ("Codex command asks in an agent hour", "12.0"))
        text = " ".join([found.title, found.seen, found.advice, found.watch, *(what for _, what in found.where)])
        for private in ("secret-module", "other-module", "/tmp", "private"):
            self.assertNotIn(private, text)



if __name__ == "__main__":
    unittest.main()
