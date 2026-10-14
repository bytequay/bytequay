import ast
from datetime import date, timedelta, timezone
import json
import logging
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from streamlit import config
from streamlit.testing.v1 import AppTest

import dashboard
import digest
import explain

SCRIPT = Path(__file__).with_name("digest.py")
PAGE = Path(__file__).with_name("pages") / "Report.py"
logging.getLogger("streamlit.runtime.scriptrunner_utils.script_run_context").addFilter(lambda record: "missing ScriptRunContext" not in record.getMessage())
SAVE = "uv run activity.py --save"


def turn(day, seconds, logged=True, task="aaaa-1111", model="gpt-6-astra", effort="max", tokens=None, cost=None, groups=None, after=None, programs=None, long_runs=(), approvals=(),
         notices=None, provider="codex"):
    """One saved turn of January 2026. `tokens` is (in, cached, out); `after` and `programs` map a name to (seconds, times)."""
    read, cached, written = tokens or (None, None, None)
    return {"task": task, "start": f"2026-01-{day:02}T12:00:00Z", "seconds": seconds, "logged": logged, "outcome": "completed", "provider": provider if model else None, "model": model,
            "effort": effort if model else None, "repository": "trinodb/trino" if model else None, "inputTokens": read, "cachedInputTokens": cached, "outputTokens": written, "cost": cost,
            "toolCalls": None, "compactions": None, "groups": groups or {}, "programs": {name: {"seconds": length, "runs": runs} for name, (length, runs) in (programs or {}).items()},
            "after": {name: {"seconds": length, "times": times} for name, (length, times) in (after or {}).items()}, "parallel": 0.0, "mostAtOnce": 0, "unfinished": 0,
            "longRuns": [{"program": program, "seconds": length} for program, length in long_runs],
            "approvals": [{"kind": kind, "outcome": outcome, "seconds": length} for kind, outcome, length in approvals], "notices": notices or {}}


# The week before: one turn with a log and one that only ByteQuay recorded. This week: three turns with a log, one of them unknown to ByteQuay.
TURNS = [
    turn(2, 600, tokens=(1000, 400, 10), groups={"model silent": 400, "tests and builds": 150, "waiting for you": 50},
         after={"at the start of a turn": (100, 1), "after tests and builds": (300, 2)}, programs={"maven": (150, 1)}, long_runs=[("maven", 150)],
         approvals=[("command", "expired", 50)], notices={"retry": 2}),
    turn(3, 300, logged=False, task="cccc-3333", model="opus", effort="high", tokens=(3000, 3000, 30), cost=2.0),
    turn(9, 300, tokens=(2000, 1500, 20), groups={"model silent": 240, "ByteQuay tools": 60}, after={"at the start of a turn": (40, 1), "after ByteQuay tools": (200, 10)}),
    turn(10, 960, tokens=(6000, 4500, 60), groups={"model silent": 480, "other shell commands": 480}, after={"at the start of a turn": (60, 1), "after other shell commands": (420, 4)},
         programs={"git": (480, 12)}, long_runs=[("git", 60), ("git", 40)], notices={"rate limit: allowed warning": 3, "rate limit: rejected": 1, "retry": 1}),
    turn(14, 100, task="bbbb-2222", model=None, groups={"model silent": 90, "reading and searching files": 10}, after={"at the start of a turn": (90, 1)}),
]
TABLE = {"version": 1, "savedAt": "2026-01-15T03:00:00Z", "since": None, "task": None, "database": {"turns": 4, "matched": 3}, "leftOut": {"skippedLines": 0, "unreadLogs": 0}, "turns": TURNS}


def run(day, repaired, model="gpt-6-astra"):
    return {"review": f"review-{day}", "repository": "trinodb/trino", "start": f"2026-01-{day:02}T13:00:00Z", "seconds": 600.0, "state": "accepted", "repaired": repaired, "turns": 1,
            "provider": "codex", "model": model, "effort": "max", "inputTokens": 10, "cachedInputTokens": 5, "outputTokens": 1, "cost": None}


REVIEWS = {"version": 1, "savedAt": "2026-01-15T03:00:00Z", "since": None, "task": None, "runs": [run(3, True), run(9, False), run(10, True)],
           "findings": [{"run": 0, "state": "withdrawn", "severity": None}, {"run": 0, "state": "open", "severity": None}, {"run": 1, "state": "resolved", "severity": "high"},
                        {"run": 1, "state": "resolved", "severity": None}, {"run": 1, "state": "open", "severity": None}]}
NOTED = [{"day": date(2026, 1, 9), "what": "Fewer tool calls"}]


def report(turns=TABLE, reviews=REVIEWS, noted=(), kind="week", **more):
    return digest.report(turns, reviews, list(noted), kind, zone=more.pop("zone", timezone.utc), **more)[0]


def rows(text, heading):
    """The table under a heading as {first cell: the other cells}; the row of column names has an empty first cell."""
    section = text.split(heading + "\n", 1)[1].split("\n#", 1)[0]
    return {cells[0]: cells[1:] for cells in ([cell.strip() for cell in line.strip("|").split("|")] for line in section.splitlines() if line.startswith("| "))}


class TestReport(unittest.TestCase):
    def test_week_report_sets_these_days_against_the_seven_before(self):
        text, last = digest.report(TABLE, REVIEWS, [], "week", zone=timezone.utc)
        self.assertEqual(last, date(2026, 1, 14))  # saved on the 15th, so the 14th is the last day that had ended
        self.assertEqual(text.splitlines()[:5], ["# ByteQuay lab report: 8 Jan to 14 Jan 2026", "",
                                                 "Against the 7 days before. From the tables saved 2026-01-15 03:00. Days are in your own time zone.", "",
                                                 "Before, 1 of 2 turns have a log. How the time was spent, long commands, questions and notices are known for those turns only."])
        self.assertEqual(rows(text, "## What changed"), {
            "": ["Before", "These days", "Change"],
            # how much was done counts every turn, with a log or without
            "Agent time": ["0.2 h", "0.4 h", "+51%"], "Turns": ["2", "3", "+50%"], "Tasks": ["2", "2", "same"], "Typical turn": ["7.5 min", "5.0 min", "-33%"],
            "Turns completed": ["100%", "100%", "same"], "Turns with a log": ["1", "3", "+200%"],
            # how the time was spent is known for the turns with a log, and a share moves in points
            "The model is working": ["67%", "60%", "-7 points"], "Waiting for a tool to finish": ["25%", "40%", "+15 points"], "Waiting for you": ["8%", "0%", "-8 points"],
            "Long single commands": ["1", "2", "+100%"], "Time in long commands": ["2.5 min", "1.7 min", "-33%"],
            "Questions to you": ["1", "0", "-100%"], "Questions that ran out": ["1", "0", "-100%"], "Rate limit notices": ["0", "4", "new"], "Retries": ["2", "1", "-50%"],
            "Tokens in": ["4.0 K", "8.0 K", "+100%"], "From the cache": ["85%", "75%", "-10 points"], "Tokens out": ["40", "80", "+100%"],
            "Recorded cost": ["$2.00", "-", ""],  # no turn of these days records a cost: not known, and not $0.00
            "Review runs": ["1", "2", "+100%"], "Runs asked again": ["1", "1", "same"], "Findings": ["2", "3", "+50%"], "Resolved": ["0", "2", "new"],
            "Withdrawn": ["1", "0", "-100%"], "Still open": ["1", "1", "same"], "Held up": ["0%", "100%", "+100 points"]})
        # The days before are seven and end where these days begin: the turn of the 2nd is in neither week here.
        self.assertEqual(rows(report(ending=date(2026, 1, 16)), "## What changed")["Turns"], ["2", "2", "same"])

    def test_time_is_ranked_by_what_took_the_most_with_its_share_before(self):
        text = report()
        found = rows(text, "## Where the time went")
        self.assertEqual(list(found), ["", "Tool, other shell commands", "Model, after other shell commands", "Model, after ByteQuay tools", "Model, at the start of a turn",
                                       "Tool, ByteQuay tools", "Tool, reading and searching files"])
        self.assertEqual(found[""], ["Time", "Share", "Share before", "Times", "Each"])
        self.assertEqual(found["Tool, other shell commands"], ["8.0 min", "35%", "-", "", ""])  # nothing like it the week before; a tool wait has no count
        self.assertEqual(found["Model, after ByteQuay tools"], ["3.3 min", "15%", "-", "10", "20 s"])
        self.assertEqual(found["Model, at the start of a turn"], ["3.2 min", "14%", "17%", "3", "63 s"])
        self.assertIn("\nShell time by program: git 8 min in 12 runs.\n", text)
        with_question = rows(report(ending=date(2026, 1, 7)), "## Where the time went")
        self.assertEqual(with_question["You, waiting for you"], ["0.8 min", "8%", "-", "1", "50 s"])  # the reader was asked once
        # Eleven parts of 12 down to 2 hours: the eight largest are named and the other three are added up.
        many = {**TABLE, "turns": [turn(9, 3600 * 12, groups={"model silent": 3600 * 12}, after={f"after tool {number}": (3600 * (12 - number), 1) for number in range(11)})]}
        found = rows(report(many), "## Where the time went")
        self.assertEqual((len(found), found["Model, after tool 0"], found["Everything else"]), (10, ["12.0 h", "100%", "-", "1", "43200 s"], ["9.0 h", "75%", "", "", ""]))
        self.assertIn("No turn of these days has a log.", report(ending=date(2026, 1, 30)))

    def test_models_list_every_recorded_turn_of_these_days(self):
        found = rows(report(), "## Models and effort")
        self.assertEqual(found, {"": ["Turns", "Turns before", "Typical turn", "Completed", "Typical tokens in"], "gpt-6-astra · max": ["2", "1", "10.5 min", "100%", "4.0 K"]})
        before = rows(report(ending=date(2026, 1, 7)), "## Models and effort")
        self.assertEqual((before["gpt-6-astra · max"], before["opus · high"]), (["1", "0", "10.0 min", "100%", "1.0 K"], ["1", "0", "5.0 min", "100%", "3.0 K"]))
        self.assertIn("ByteQuay's records hold no turn for these days.", report(ending=date(2026, 1, 30)))

    def test_day_report_sets_one_day_against_the_day_before(self):
        text = report(kind="day", ending=date(2026, 1, 10))
        self.assertEqual(text.splitlines()[:3], ["# ByteQuay lab report: 10 Jan 2026", "", "Against the day before. From the tables saved 2026-01-15 03:00. Days are in your own time zone."])
        found = rows(text, "## What changed")
        self.assertEqual((found["Agent time"], found["Turns"], found["Typical turn"]), (["0.1 h", "0.3 h", "+220%"], ["1", "1", "same"], ["5.0 min", "16.0 min", "+220%"]))

    def test_last_day_is_the_last_one_that_had_ended_in_the_readers_own_zone(self):
        west = timezone(timedelta(hours=-5))  # there the tables were saved at ten in the evening of the 14th
        self.assertEqual(digest.report(TABLE, REVIEWS, [], "week", zone=west)[1], date(2026, 1, 13))
        self.assertIn("From the tables saved 2026-01-14 22:00.", report(zone=west))
        running = report(ending=date(2026, 1, 15))
        self.assertIn(f"\nThe tables were saved before 15 Jan had ended, so the last day is not whole. Save again with `{SAVE}`\n", running)
        self.assertNotIn("is not whole", report())

    def test_notes_say_what_the_saved_tables_leave_out(self):
        self.assertIn("\nOnly turns that started on or after 2026-01-05 were saved.\n", report({**TABLE, "since": "2026-01-05"}))
        self.assertIn("\nOnly the task whose id starts with aaaa was saved.\n", report({**TABLE, "task": "aaaa"}))
        logs_only = {**TABLE, "database": {"problem": "the database is not there"}, "turns": [{**row, "model": None, "effort": None, "inputTokens": None} for row in TURNS if row["logged"]]}
        text = report(logs_only)
        self.assertIn("\nModel, effort, tokens and cost were not saved: the database is not there.\n", text)
        self.assertEqual((rows(text, "## What changed")["Tokens in"], rows(text, "## What changed")["Recorded cost"]), (["-", "-", ""], ["-", "-", ""]))
        self.assertIn("ByteQuay's records hold no turn for these days.", text)
        for reviews, said in [(None, f"No reviews are saved yet. Save them with `{SAVE}`"), ({}, f"The saved reviews could not be read and are left out. Save them again with `{SAVE}`"),
                              ({**REVIEWS, "findings": [{"run": 7, "state": "open", "severity": None}]}, f"The saved reviews could not be read and are left out. Save them again with `{SAVE}`")]:
            text = report(reviews=reviews)
            self.assertIn(f"\n{said}\n", text)
            self.assertEqual((rows(text, "## What changed")["Review runs"], rows(text, "## What changed")["Turns"]), (["-", "-", ""], ["2", "3", "+50%"]))
        apart = report(reviews={**REVIEWS, "savedAt": "2026-01-10T00:00:00Z"})
        self.assertIn(f"\nThe reviews were saved at another time than the turns. Save both again with `{SAVE}`\n", apart)
        self.assertEqual(rows(apart, "## What changed")["Review runs"], ["1", "2", "+100%"])  # they are still listed
        self.assertNotIn("reviews", report().split("## What changed")[0])

    def test_turn_table_in_another_shape_is_refused(self):
        for broken in ({}, {**TABLE, "turns": [{"task": "aaaa"}]}, {**TABLE, "turns": [{**TURNS[0], "groups": {"a part of another version": 1}}]}, {**TABLE, "savedAt": "yesterday"}):
            with self.assertRaises((KeyError, TypeError, ValueError)):
                digest.report(broken, REVIEWS, [], "week", zone=timezone.utc)

    def test_report_names_no_task_and_no_repository(self):
        text = report(noted=NOTED)
        for private in ("aaaa-1111", "bbbb-2222", "cccc-3333", "trinodb", "review-9"):
            self.assertNotIn(private, text)

    def test_why_names_the_models_and_efforts_that_moved_each_total(self):
        # Before: six Codex turns that record no cost and one opus turn that does. These days: two Codex turns, four opus at max and one at high.
        codex = dict(tokens=(1000, 0, 10))
        opus = dict(model="opus", provider="claude", tokens=(3000, 0, 30))
        made = [*(turn(day, 600, task=f"codex-{day}", **codex) for day in range(1, 7)), turn(7, 300, task="opus-7", cost=2.0, **opus),
                *(turn(day, 600, task=f"codex-{day}", **codex) for day in (8, 9)), *(turn(day, 900, task=f"opus-{day}", cost=5.0, **opus) for day in (10, 11, 12, 13)),
                turn(14, 600, task="opus-14", effort="high", **{**opus, "tokens": None})]  # a Claude turn that recorded neither tokens nor a cost
        section = report(turns={**TABLE, "turns": made}, reviews=None).split("## Why it moved\n\n", 1)[1].split("\n\n## ", 1)[0]
        self.assertEqual(section.splitlines(), [
            "The turns ran with other models and efforts than in the days before: gpt-6-astra · max 29% of the turns (86% before), opus · max 57% of the turns "
            "(14% before), opus · high 14% of the turns (0% before).", "",
            # opus at high moved agent time too, but only the two that moved it most are named
            "- Agent time 1.1 h → 1.5 h: opus · max +0.9 h (1 → 4 turns), gpt-6-astra · max -0.7 h (6 → 2 turns).",
            "- Tokens in 9.0 K → 14.0 K: opus · max +9.0 K (1 → 4 turns), gpt-6-astra · max -4.0 K (6 → 2 turns). Tokens are recorded for 7 of 7 turns before and 6 of 7 these days.",
            "- Tokens out 90 → 140: opus · max +90 (1 → 4 turns), gpt-6-astra · max -40 (6 → 2 turns). Tokens are recorded for 7 of 7 turns before and 6 of 7 these days.",
            # Codex turns gave nothing, as their cost is not known rather than zero; Claude records one on most turns, so it is not named
            "- Recorded cost $2.00 → $20.00: opus · max +$18.00 (1 → 4 turns). A cost is recorded for 1 of 7 turns before and 4 of 7 these days; no codex turn records one."])
        self.assertEqual(report().split("## Why it moved\n\n", 1)[1].split("\n", 1)[0], "Fewer than 5 turns on a side, too few to say why the totals moved.")
        few_before = [turn(2, 600, task="x"), *(turn(day, 600, task=f"y-{day}") for day in range(8, 14))]  # one turn before and six these days
        self.assertEqual(report(turns={**TABLE, "turns": few_before}, reviews=None).split("## Why it moved\n\n", 1)[1].split("\n", 1)[0],
                         "Fewer than 5 turns on a side, too few to say why the totals moved.")
        alike = [turn(day, 600, task=f"codex-{day}") for day in (*range(2, 7), *range(9, 14))]
        self.assertEqual(report(turns={**TABLE, "turns": alike}, reviews=None).split("## Why it moved\n\n", 1)[1].split("\n", 1)[0], "No total moved by 25% or more.")


class TestFair(unittest.TestCase):
    def made(self):
        """Two weeks of two models, three tasks of each on each side. An astra turn takes 10 minutes and an opus turn 20 on
        both sides, but the turns moved from astra to opus: astra has 12 turns in all and opus 9. Astra's first model step
        took three times as long these days."""
        rows = []
        for side, days, astra, opus, start in (("before", (2, 3, 4), 3, 1, 30), ("since", (9, 10, 11), 1, 2, 90)):
            for one, day in enumerate(days):
                rows += [turn(day, 600, task=f"astra-{side}-{one}", after={"at the start of a turn": (start, 1)}) for _ in range(astra)]
                rows += [turn(day, 1200, task=f"opus-{side}-{one}", model="opus", provider="claude", after={"at the start of a turn": (30, 1)}) for _ in range(opus)]
        return {**TABLE, "turns": rows}

    def test_a_number_is_judged_within_the_same_models_and_efforts(self):
        text = report(turns=self.made(), reviews=None)
        found = rows(text, "## Better or worse")
        self.assertEqual(found[""], ["Before", "These days", "Change", "Clear?"])
        self.assertEqual(found["Typical turn"], ["10.0 min", "20.0 min", "+100%", "not yet: +0% to +0%"])  # more opus turns, each as long as before
        # Astra's alone tripled: like with like, 30 seconds before and (12 × 90 + 9 × 30) / 21 since.
        self.assertEqual(found["Model time at the start of a turn"], ["30 s", "50 s", "+67%", "worse: +114% to +114%"])
        self.assertEqual(found["Turns completed"], ["100%", "100%", "same", "not yet: +0 to +0 points"])
        self.assertEqual(found["Questions to you in an agent hour"], ["0.0", "0.0", "", "too few tasks"])  # nobody was asked on either side
        self.assertEqual(text.split("## Worth a look\n\n", 1)[1].split("\n\n", 1)[0],
                         "- Model time at the start of a turn got worse within the same models and efforts, by +114% to +114%.")
        noted = report(turns=self.made(), reviews=None, noted=[{"day": date(2026, 1, 9), "what": "Longer instructions"}])
        self.assertIn('\n- Since "Longer instructions" on 9 Jan, Model time at the start of a turn got worse within the same models and efforts, by +114% to +114%.\n', noted)
        self.assertEqual(rows(noted, "### 9 Jan: Longer instructions")["Model time at the start of a turn"][3], "worse: +114% to +114%")
        self.assertIn("\n## Worth a look\n\nNothing got clearly worse, comparing the same models and efforts.\n", report())

    def test_turns_completed_counts_only_the_turns_whose_ending_is_known(self):
        made = [turn(9, 600, task="a"), {**turn(10, 600, task="b"), "outcome": "interrupted"}, {**turn(11, 600, task="c"), "outcome": "no end mark"}]
        self.assertEqual(rows(report(turns={**TABLE, "turns": made}, reviews=None), "## What changed")["Turns completed"][1], "50%")
        self.assertEqual(digest.measures(digest.charts.frames({**TABLE, "turns": made}), None)["Turns completed"].value(), 50.0)

    def test_what_changed_in_bytequay_names_new_setups_and_the_commits_by_where_they_landed(self):
        made = [{**turn(day, 600, task=f"t-{day}"), "setup": setup}
                for day, setup in ((2, "aaaaaaaa-11111111"), (9, "aaaaaaaa-11111111"), (10, "bbbbbbbb-11111111"), (11, "bbbbbbbb-22222222"), (12, None))]
        codex = "backend/agent-cli/src/main/java/com/bytequay/agent/cli/codex/CodexCliDriver.java"
        found = [(0, "Retry rate limits", (codex,)), (0, "Shorter review rules", ("backend/app/src/main/java/com/bytequay/app/review/ReviewTools.java", "README.md", codex)),
                 (0, "Codex timeouts", (codex,))]
        asked = []
        text = report(turns={**TABLE, "turns": made}, reviews=None, commits=lambda first, last: asked.append((first, last)) or found)
        self.assertEqual(asked, [(date(2026, 1, 8), date(2026, 1, 14))])
        self.assertEqual(text.split("## What changed in ByteQuay\n\n", 1)[1].split("\n\n", 1)[0].splitlines(), [
            # each half is set against every setup that ran before, also in the days before these
            "- Setups that first ran these days: `bbbbbbbb-11111111` (new instructions, 1 turn), `bbbbbbbb-22222222` (new tools, 1 turn).",
            "- 3 commits reached the agent code these days:", '  - the Codex lane: 3, the latest "Retry rate limits"', '  - reviews: 1, the latest "Shorter review rules"'])
        self.assertEqual(report().split("## What changed in ByteQuay\n\n", 1)[1].split("\n\n", 1)[0].splitlines(),
                         ["- No saved turn names its setup yet.", "- The commits to the agent code were not read."])
        self.assertIn("\n- No commit reached the agent code these days.\n", report(commits=lambda first, last: []))


class TestVerdict(unittest.TestCase):
    def test_a_clear_change_is_better_or_worse_where_one_way_is_better(self):
        cases = [("Turns completed", (2.0, 9.0)), ("Turns completed", (-9.0, -2.0)), ("Waiting for you", (2.0, 9.0)), ("Model time at the start of a turn", (-9.0, -2.0)),
                 ("Typical turn", (2.0, 9.0)), ("Turns completed", (-2.0, 9.0))]
        self.assertEqual([digest.verdict(name, moved) for name, moved in cases], ["better", "worse", "worse", "better", "yes", "not yet"])


class TestMoved(unittest.TestCase):
    def test_number_moves_by_how_much_it_grew_and_a_share_in_points(self):
        self.assertEqual([digest.moved(before, now, False) for before, now in [(10, 15), (10, 5), (10, 10.04), (200, 0), (0, 3), (0, 0), (None, 3), (3, None)]],
                         ["+50%", "-50%", "same", "-100%", "new", "", "", ""])
        self.assertEqual([digest.moved(before, now, True) for before, now in [(20, 35), (35, 20), (20, 21), (20.2, 19.8), (0, 100), (None, 5)]],
                         ["+15 points", "-15 points", "+1 point", "same", "+100 points", ""])


class TestNotedChanges(unittest.TestCase):
    def test_days_since_a_noted_change_are_set_against_the_seven_before_it(self):
        text = report(noted=NOTED)
        few = "too few tasks"  # two tasks at most on a side
        self.assertEqual(rows(text, "### 9 Jan: Fewer tool calls"), {
            "": ["7 days before (2 turns)", "6 days since (3 turns)", "Change", "Clear?"],
            "Typical turn": ["7.5 min", "5.0 min", "-33%", few], "Turns completed": ["100%", "100%", "same", few],
            "The model is working": ["67%", "60%", "-7 points", few], "Waiting for a tool to finish": ["25%", "40%", "+15 points", few], "Waiting for you": ["8%", "0%", "-8 points", few],
            "Model time after a ByteQuay tool call": ["-", "20 s", "", few], "Model time at the start of a turn": ["100 s", "63 s", "-37%", few],
            "Long command minutes in an agent hour": ["15.0 min", "4.4 min", "-71%", few], "Questions to you in an agent hour": ["6.0", "0.0", "-100%", few],
            "Typical tokens in a turn": ["2.0 K", "4.0 K", "+100%", few], "Share of review runs asked again": ["100%", "50%", "-50 points", few]})
        self.assertNotIn("None in the last", text)
        today = rows(report(noted=[{"day": date(2026, 1, 14), "what": "A change made on the last day"}]), "### 14 Jan: A change made on the last day")
        self.assertEqual(today[""], ["7 days before (2 turns)", "1 day since (1 turn)", "Change", "Clear?"])
        # Seven days and no more: the turn of the 2nd is the eighth day before the 10th.
        self.assertEqual(rows(report(noted=[{"day": date(2026, 1, 10), "what": "Later"}]), "### 10 Jan: Later")[""], ["7 days before (2 turns)", "5 days since (2 turns)", "Change", "Clear?"])

    def test_a_noted_change_says_whether_it_moved_beyond_chance(self):
        # Three tasks on each side: the first step got much shorter, the turns stayed as long and the asks came as often.
        made = [turn(day, 600, task=f"task-{day}-{one}", groups={"model silent": silent, "tests and builds": 600 - silent}, after={"at the start of a turn": (start + 10 * one, 1)},
                     approvals=[("command", "allowed", 5)])
                for day, start, silent in ((6, 90, 400), (7, 90, 400), (10, 15, 500), (11, 15, 500)) for one in range(3)]
        text = report(turns={**TABLE, "turns": made}, reviews=None, noted=[{"day": date(2026, 1, 10), "what": "Shorter memory block"}])
        found = rows(text, "### 10 Jan: Shorter memory block")
        self.assertEqual(found["Model time at the start of a turn"][:3], ["100 s", "25 s", "-75%"])
        self.assertTrue(found["Model time at the start of a turn"][3].startswith("better: -"), found["Model time at the start of a turn"])  # a shorter first step is better
        self.assertEqual(found["Typical turn"], ["10.0 min", "10.0 min", "same", "not yet: +0% to +0%"])
        self.assertEqual(found["The model is working"], ["67%", "83%", "+16 points", "yes: +17 to +17 points"])  # every task moved alike, so the range is one number
        self.assertEqual(found["Share of review runs asked again"], ["-", "-", "", "too few tasks"])
        self.assertIn("Clear? says whether a number moved beyond what other tasks could have given: whole tasks drawn again, 95% of the draws. It compares like with like", text)

    def test_only_changes_of_the_last_four_weeks_up_to_the_last_day_are_listed(self):
        noted = [{"day": date(2025, 12, 17), "what": "Too long ago"}, {"day": date(2025, 12, 18), "what": "Just inside"}, {"day": date(2026, 1, 15), "what": "After the report"}]
        text = report(noted=noted)
        self.assertEqual([line for line in text.splitlines() if line.startswith("### ")], ["### 18 Dec: Just inside"])
        self.assertIn('\nNone in the last 28 days. Note one with `uv run digest.py --note "what you changed"`\n', report())

    def test_noting_keeps_changes_in_the_order_of_their_days(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory) / "lab"
            self.assertEqual(digest.changes(folder), [])
            digest.note(folder, "  Narrower   Maven\ncommands ", date(2026, 1, 9))
            self.assertEqual(digest.note(folder, "Fewer tool calls", date(2026, 1, 2)),
                             [{"day": date(2026, 1, 2), "what": "Fewer tool calls"}, {"day": date(2026, 1, 9), "what": "Narrower Maven commands"}])
            self.assertEqual(json.loads((folder / "changes.json").read_text()), {"version": 1, "changes": [{"day": "2026-01-02", "what": "Fewer tool calls"},
                                                                                                         {"day": "2026-01-09", "what": "Narrower Maven commands"}]})
            by_hand = {"version": 1, "changes": [{"day": "2026-01-09", "what": "Second"}, {"day": "2026-01-02", "what": "First"}]}
            (folder / "changes.json").write_text(json.dumps(by_hand))
            self.assertEqual([one["what"] for one in digest.changes(folder)], ["First", "Second"])  # a file changed by hand is still read in the order of the days
            for broken in ('{"version": 2, "changes": []}', '{"version": 1}', '{"version": 1, "changes": [{"day": "the ninth", "what": "x"}]}', "{"):
                (folder / "changes.json").write_text(broken)
                with self.assertRaises((KeyError, TypeError, ValueError)):
                    digest.changes(folder)


class TestCommandLine(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.folder = Path(directory.name) / "lab"
        self.folder.mkdir()

    def script(self, *arguments):
        # Days are in the reader's own zone, so the reader of these tests lives in UTC.
        return subprocess.run([sys.executable, str(SCRIPT), str(self.folder), *arguments], capture_output=True, text=True, env={**os.environ, "TZ": "UTC"})

    def save(self, turns=TABLE, reviews=REVIEWS):
        (self.folder / "turns.json").write_text(json.dumps(turns))
        if reviews is not None:
            (self.folder / "reviews.json").write_text(json.dumps(reviews))

    def test_report_is_printed_and_kept_as_a_file_of_its_own(self):
        self.save()
        week = self.script()
        self.assertEqual((week.returncode, week.stderr), (0, ""))
        kept = self.folder / "reports" / "week-2026-01-14.md"
        self.assertEqual(week.stdout, kept.read_text() + f"\nSaved to {kept}\n")
        self.assertEqual(kept.read_text(), report(commits=explain.commits))  # with the commits git gives for those days
        day = self.script("--day", "--ending", "2026-01-10")
        self.assertEqual((day.returncode, day.stdout.splitlines()[0]), (0, "# ByteQuay lab report: 10 Jan 2026"))
        self.assertEqual(sorted(path.name for path in (self.folder / "reports").iterdir()), ["day-2026-01-10.md", "week-2026-01-14.md"])  # no half-written file is left
        self.assertEqual(self.script("--week", "--ending", "2026-01-14").stdout, week.stdout)

    def test_noted_change_is_kept_and_shows_in_the_next_report(self):
        self.save()
        noted = self.script("--note", "Fewer tool calls", "--on", "2026-01-09")
        self.assertEqual((noted.returncode, noted.stdout, noted.stderr), (0, "2026-01-09  Fewer tool calls\n", ""))
        self.assertFalse((self.folder / "reports").exists())  # noting a change writes no report
        self.assertIn("### 9 Jan: Fewer tool calls", self.script().stdout)
        today = self.script("--note", "Something today")
        self.assertRegex(today.stdout, r"\A2026-01-09  Fewer tool calls\n\d{4}-\d\d-\d\d  Something today\n\Z")

    def test_missing_or_unreadable_tables_are_named_and_nothing_is_written(self):
        nothing = self.script()
        self.assertEqual((nothing.returncode, nothing.stdout, nothing.stderr), (1, "", f"Nothing saved yet. Save the turn table with `{SAVE}`\n"))
        for broken in ('{"version": 1, "turns": [', json.dumps({**TABLE, "version": 2}), json.dumps({"version": 1})):
            (self.folder / "turns.json").write_text(broken)
            refused = self.script()
            self.assertEqual((refused.returncode, refused.stdout, refused.stderr), (1, "", f"The saved turn table could not be read. Save it again with `{SAVE}`\n"))
        self.assertFalse((self.folder / "reports").exists())
        self.save(reviews=None)
        (self.folder / "reviews.json").write_text("{")
        without = self.script()
        self.assertEqual(without.returncode, 0)
        self.assertIn("The saved reviews could not be read and are left out.", without.stdout)
        (self.folder / "changes.json").write_text("{")
        refused = self.script()
        self.assertEqual((refused.returncode, refused.stdout, refused.stderr), (1, "", f"The noted changes in {self.folder / 'changes.json'} could not be read.\n"))

    def test_wrong_arguments_are_refused_with_a_reason(self):
        self.save()
        for arguments, reason in [(["--on", "2026-01-09"], "--on names the day of a change, so it needs --note"), (["--note", "  "], "--note needs what you changed"),
                                  (["--day", "--week"], "not allowed with argument"), (["--ending", "the ninth"], "invalid fromisoformat value")]:
            refused = self.script(*arguments)
            self.assertEqual((refused.returncode, refused.stdout), (2, ""), arguments)
            self.assertIn(reason, refused.stderr)
        self.assertFalse((self.folder / "changes.json").exists())


class TestPage(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.folder = Path(directory.name)
        arguments = mock.patch.object(sys, "argv", ["dashboard.py", str(self.folder)])
        arguments.start()
        self.addCleanup(arguments.stop)
        config.set_option("server.address", dashboard.HOST)
        self.addCleanup(config.set_option, "server.address", None)

    def page(self, kind=None):
        page = AppTest.from_file(str(PAGE), default_timeout=30).run()
        if kind:
            page.button_group(key="kind").set_value(kind).run()
        return page

    def save(self, name="turns.json", text=None):
        (self.folder / name).write_text(json.dumps(TABLE) if text is None else text)

    def test_page_shows_the_report_of_the_days_picked(self):
        self.save()
        self.save("reviews.json", json.dumps(REVIEWS))
        page = self.page()
        self.assertEqual((list(page.exception), [note.value for note in page.warning]), ([], []))
        self.assertTrue(page.subheader[0].value.startswith("ByteQuay lab report: "), page.subheader[0].value)
        body = page.markdown[0].value
        self.assertIn("Against the 7 days before.", body)
        self.assertIn("\n## Why it moved\n", body)
        self.assertIn("\\$2.00", body)  # a dollar sign is written as such, not as the start of a formula
        self.assertIn("Against the day before.", self.page("Day").markdown[0].value)

    def test_page_says_how_to_save_and_names_a_table_it_cannot_read(self):
        self.assertEqual([note.value for note in self.page().info], ["Nothing saved yet. Save the turn table with `uv run activity.py --save`"])
        for text in ('{"version": 1, "turns": [', json.dumps({**TABLE, "turns": [{"task": "a"}]})):
            with self.subTest(text=text[:30]):
                self.save(text=text)
                self.assertEqual([note.value for note in self.page().warning], ["The saved turn table could not be read. Save it again with `uv run activity.py --save`"])
        self.save()
        self.save("changes.json", "{")
        self.assertIn("The noted changes in `changes.json` could not be read, so they are left out.", [caption.value for caption in self.page().caption])

    def test_page_started_another_way_shows_nothing(self):
        self.save()
        config.set_option("server.address", "0.0.0.0")
        self.assertEqual([note.value for note in self.page().error], ["This page is only shown to this machine. Start it with `uv run dashboard.py`"])

    def test_page_has_no_bare_expression_that_streamlit_would_print(self):
        tree = ast.parse(PAGE.read_text())
        bare = [node.lineno for node in ast.walk(tree) if isinstance(node, ast.Expr) and not isinstance(node.value, ast.Call)
                and not (isinstance(node.value, ast.Constant) and isinstance(node.value.value, str))]
        self.assertEqual(bare, [])


if __name__ == "__main__":
    unittest.main()
