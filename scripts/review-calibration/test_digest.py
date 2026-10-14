from datetime import date, timedelta, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import digest

SCRIPT = Path(__file__).with_name("digest.py")
SAVE = "uv run activity.py --save"


def turn(day, seconds, logged=True, task="aaaa-1111", model="gpt-6-astra", effort="max", tokens=None, cost=None, groups=None, after=None, programs=None, long_runs=(), approvals=(),
         notices=None):
    """One saved turn of January 2026. `tokens` is (in, cached, out); `after` and `programs` map a name to (seconds, times)."""
    read, cached, written = tokens or (None, None, None)
    return {"task": task, "start": f"2026-01-{day:02}T12:00:00Z", "seconds": seconds, "logged": logged, "outcome": "completed", "provider": "codex" if model else None, "model": model,
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
            "Turns with a log": ["1", "3", "+200%"],
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
            "Typical turn": ["7.5 min", "5.0 min", "-33%", few],
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
        self.assertTrue(found["Model time at the start of a turn"][3].startswith("yes: -"), found["Model time at the start of a turn"])
        self.assertEqual(found["Typical turn"], ["10.0 min", "10.0 min", "same", "not yet: +0% to +0%"])
        self.assertEqual(found["The model is working"], ["67%", "83%", "+16 points", "yes: +17 to +17 points"])  # every task moved alike, so the range is one number
        self.assertEqual(found["Share of review runs asked again"], ["-", "-", "", "too few tasks"])
        self.assertIn("Clear? says whether the change is beyond what other tasks could have given: whole tasks drawn again, 95% of the draws.", text)

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
        self.assertEqual(kept.read_text(), report())
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


if __name__ == "__main__":
    unittest.main()
