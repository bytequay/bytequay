from datetime import date, datetime
import json
from pathlib import Path
import tempfile
import unittest

import approvals
import charts
import record
import suggest
from test_suggest import UTC, row, table

MAVEN = "Make long maven runs shorter"


def two_models():
    """Three tasks a day of model m and, until the 5th, one a day of model n, then three. A turn lasts ten minutes. Model m's
    long Maven runs got far shorter from the 5th on; model n's stayed ten minutes long."""
    rows = []
    for day in range(1, 9):
        for one in range(3):
            rows.append(row(f"2026-01-0{day}T10:00:00Z", task=f"m-{day}-{one}", long_runs=[("maven", (330.0 + 30 * one) if day < 5 else (40.0 + 10 * one))]))
        rows += [{**row(f"2026-01-0{day}T11:00:00Z", task=f"n-{day}-{one}", long_runs=[("maven", 600.0)]), "model": "n"} for one in range(1 if day < 5 else 3)]
    saved = table(*rows)
    return charts.frames(saved, UTC), charts.call_frames(saved)


class TestMarks(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.folder = Path(directory.name)

    def test_one_mark_is_kept_for_each_suggestion_the_newest_winning_and_it_can_be_taken_away(self):
        self.assertEqual(record.marks(self.folder), {})
        record.mark(self.folder, "B", "doing", date(2026, 1, 5))
        record.mark(self.folder, "A", "dropped", date(2026, 1, 6), "  not worth\n it ")
        record.mark(self.folder, "B", "dropped", date(2026, 1, 7), "made elsewhere")
        self.assertEqual(record.marks(self.folder), {"A": ("dropped", date(2026, 1, 6), "not worth it"), "B": ("dropped", date(2026, 1, 7), "made elsewhere")})
        record.mark(self.folder, "A", None, date(2026, 1, 8))
        self.assertEqual(list(record.marks(self.folder)), ["B"])
        (self.folder / record.FILE).write_text(json.dumps({"version": 1, "suggestions": [{"title": "A", "state": "won", "day": "2026-01-01", "reason": ""}]}))
        with self.assertRaises(ValueError):
            record.marks(self.folder)

    def test_not_wanted_wins_then_made_then_being_worked_on(self):
        marked = {"A": ("dropped", date(2026, 1, 1), "no"), "B": ("doing", date(2026, 1, 2), ""), "C": ("doing", date(2026, 1, 2), "")}
        noted = [{"day": date(2026, 1, 3), "what": "A"}, {"day": date(2026, 1, 4), "what": "C"}, {"day": date(2026, 1, 3), "what": "C"}]
        self.assertEqual([record.standing(title, marked, noted) for title in "ABCD"],
                         [("dropped", date(2026, 1, 1), "no"), ("doing", date(2026, 1, 2), ""), ("done", date(2026, 1, 4), ""), ("open", None, "")])


class TestCheck(unittest.TestCase):
    def test_a_window_is_cut_by_model_and_effort_and_each_task_takes_its_asks_along(self):
        made, calls = two_models()
        asked = lambda task: approvals.Ask(task, datetime(2026, 1, 1, 12, tzinfo=UTC).timestamp(), "codex", "command", "ls", "allowed", 60.0, False)
        window = suggest.window(made, calls, None, (asked("m-1-0"), asked("n-1-0"), asked("gone")), date(2026, 1, 1), date(2026, 1, 4), UTC)
        self.assertEqual({setting: (turns, [ask.task for ask in part.asks]) for setting, (turns, part) in record.by_setting(window).items()},
                         {"m · high": (12, ["m-1-0"]), "n · high": (4, ["n-1-0"])})  # an ask of a task with no turn here goes nowhere

    def test_a_change_with_one_task_on_a_side_tells_nothing_yet(self):
        rows = [row(f"2026-01-0{day}T10:00:00Z", long_runs=[("maven", 330.0 if day < 8 else 60.0)]) for day in range(1, 10)]
        saved = table(*rows)
        made, calls = charts.frames(saved, UTC), charts.call_frames(saved)
        (found,) = suggest.long_runs(suggest.window(made, calls, None, (), date(2026, 1, 1), date(2026, 1, 7), UTC))
        before, since, turns, moved = record.check(found, made, calls, None, (), date(2026, 1, 8), date(2026, 1, 9), UTC)
        self.assertEqual((round(before, 6), round(since, 6), turns, moved), (33.0, 6.0, 2, None), "long maven minutes in an agent hour; one task, so no range")
        self.assertEqual(record.verdict(moved), "Too few tasks of one model and effort on both sides to tell yet.")

    def test_a_change_is_judged_within_the_same_models_and_efforts(self):
        made, calls = two_models()
        found = record.then(MAVEN, made, calls, None, (), date(2026, 1, 5), UTC)
        self.assertEqual((found.title, round(found.saves, 1)), (MAVEN, 52.0))  # as it stood in the fourteen days before the change
        before, since, turns, moved = record.check(found, made, calls, None, (), date(2026, 1, 5), date(2026, 1, 8), UTC)
        # Over all turns the number fell from 42 to 32.5 an hour, since more of the turns ran on n; within each model it fell from 45.6 to 27.
        self.assertEqual((round(before, 6), round(since, 6), turns), (42.0, 32.5, 24))
        self.assertTrue(-20 < moved[0] < moved[1] < -17, moved)
        self.assertTrue(record.verdict(moved).startswith("It helped: down by "), record.verdict(moved))
        self.assertTrue(record.verdict((1.0, 2.0)).startswith("It made it worse: up by 1.0 to 2.0"))
        self.assertEqual(record.verdict((-1.0, 2.0)), "No clear change yet: somewhere from -1.0 to +2.0, within the same models and efforts.")


class TestRecord(unittest.TestCase):
    def test_the_record_lists_what_was_marked_or_made_with_what_each_change_did(self):
        made, calls = two_models()
        noted = [{"day": date(2026, 1, 5), "what": MAVEN}, {"day": date(2026, 1, 5), "what": "Bigger pages, in words of my own"}]
        marked = {"Shorten what a turn starts with": ("dropped", date(2026, 1, 6), "the context is needed"), "Let Codex run maven without asking": ("doing", date(2026, 1, 7), "")}
        listed = suggest.suggestions(suggest.window(made, calls, None, (), date(2026, 1, 2), date(2026, 1, 8), UTC))
        rows = record.table(marked, noted, listed, made, calls, None, (), date(2026, 1, 8), UTC)
        # A change noted in words of its own is not a suggestion and is left out.
        self.assertEqual([(row["suggestion"], row["stands"], row["since"], row["what it did"]) for row in rows],
                         [("Let Codex run maven without asking", "being worked on", date(2026, 1, 7), None), (MAVEN, "made", date(2026, 1, 5), "helped"),
                          ("Shorten what a turn starts with", "not wanted: the context is needed", date(2026, 1, 6), None)])
        self.assertEqual({row["from"] for row in rows}, {"lab rules"})
        maven = rows[1]
        self.assertEqual((round(maven["could save then"], 1), maven["could save now"], maven["number to watch"], round(maven["before"], 1), round(maven["after"], 1)),
                         (52.0, next(one.saves for one in listed if one.title == MAVEN), "long maven minutes in an agent hour", 42.0, 32.5))

    def test_a_risk_the_agent_found_shares_the_record_and_a_fix_is_checked_by_the_looks_after_it(self):
        made, calls = two_models()
        heap = {"title": "Give the backend more heap", "risk": "high", "seen": "1,991 of 2,000 MB", "do": "Raise -Xmx.", "where": "dev.sh",
                "watch": "the backend's peak memory", "action": "none"}
        idle = heap | {"title": "Close idle tasks", "watch": "idle open tasks"}
        looks = [{"ended": "2026-01-04T10:00:00Z", "suggestions": [heap, idle]}, {"ended": "2026-01-07T10:00:00Z", "suggestions": [idle]}]
        rows = record.table({idle["title"]: ("doing", date(2026, 1, 6), "")}, [{"day": date(2026, 1, 5), "what": heap["title"]}], [], made, calls, None, (),
                            date(2026, 1, 8), UTC, looks)
        self.assertEqual([(row["suggestion"], row["from"], row["stands"], row["number to watch"], row["could save now"], row["what it did"]) for row in rows],
                         [("Close idle tasks", "risk look", "being worked on", "idle open tasks", None, None),
                          ("Give the backend more heap", "risk look", "made", "the backend's peak memory", None, "gone")])
        # Only a look on a later day than the change counts: one on the same day may have run before it.
        self.assertEqual([record.gone(heap["title"], date(2026, 1, 4), seen, UTC) for seen in (looks[:1], looks)], ["no look since yet", "gone"])
        self.assertEqual(record.gone(idle["title"], date(2026, 1, 5), looks, UTC), "still found")


def asked(text, at):
    return {"type": "user_message", "occurredAt": at, "taskId": "t", "payload": {"text": text, "attachments": [], "deliveryKind": "TURN"}}


def phase(name, at):
    return {"type": "lifecycle", "occurredAt": at, "taskId": "t", "payload": {"phase": name, "message": "Task completed"}}


BRIEF = f"{MAVEN}\n\nWhat the lab saw: a secret path\n\n{suggest.LINE}{MAVEN}  \n"


class TestFixes(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.runtime = Path(directory.name)

    def task(self, name, *events, broken=False):
        log = self.runtime / name / "events.jsonl"
        log.parent.mkdir(parents=True)
        log.write_text(('{"type": "user_message", cut off\n' if broken else "") + "".join(json.dumps(event) + "\n" for event in events))

    def test_a_task_is_known_by_the_last_line_of_its_first_message_and_noted_once_it_is_completed(self):
        self.task("a-started", asked(BRIEF, "2026-01-05T10:00:00Z"), {"type": "assistant_message", "occurredAt": "2026-01-05T10:01:00Z",
                                                                       "payload": {"text": "after TASK_COMPLETED"}}, broken=True)
        self.task("b-completed", asked(BRIEF, "2026-01-06T10:00:00Z"), asked("Also check the tests", "2026-01-06T11:00:00Z"), phase("TASK_COMPLETED", "2026-01-08T09:00:00Z"))
        self.task("c-failed", asked(BRIEF, "2026-01-06T10:00:00Z"), phase("TASK_FAILED", "2026-01-07T09:00:00Z"))
        self.task("d-edited", asked(f"{MAVEN}\n\nmy own words about the {suggest.LINE}{MAVEN}", "2026-01-06T10:00:00Z"), phase("TASK_COMPLETED", "2026-01-07T09:00:00Z"))
        self.task("e-later", asked("Look at the build", "2026-01-06T10:00:00Z"), asked(BRIEF, "2026-01-06T11:00:00Z"), phase("TASK_COMPLETED", "2026-01-07T09:00:00Z"))
        self.task("f-again", asked(BRIEF, "2026-01-09T10:00:00Z"), phase("TASK_COMPLETED", "2026-01-10T09:00:00Z"))
        self.task("g-another", asked(f"x\n{suggest.LINE}Shorten what a turn starts with", "2026-01-04T10:00:00Z"))
        self.task("h-second", asked(BRIEF, "2026-01-07T10:00:00Z"))
        found = record.fixes(self.runtime, UTC)
        self.assertEqual(found, [(MAVEN, date(2026, 1, 5), None), (MAVEN, date(2026, 1, 6), date(2026, 1, 8)), (MAVEN, date(2026, 1, 9), date(2026, 1, 10)),
                                 ("Shorten what a turn starts with", date(2026, 1, 4), None), (MAVEN, date(2026, 1, 7), None)])
        self.assertNotIn("secret", repr(found))
        self.assertEqual(record.on_it(found), {MAVEN: date(2026, 1, 5), "Shorten what a turn starts with": date(2026, 1, 4)})
        self.assertEqual(record.unnoted(found, []), [(MAVEN, date(2026, 1, 8)), (MAVEN, date(2026, 1, 10))])
        self.assertEqual(record.unnoted(found, [{"day": date(2026, 1, 8), "what": MAVEN}]), [(MAVEN, date(2026, 1, 10))])
        self.assertEqual(record.unnoted(found + found, [{"day": date(2026, 1, 8), "what": MAVEN}, {"day": date(2026, 1, 10), "what": MAVEN}]), [])

    def test_no_logs_means_no_fixes(self):
        self.assertEqual(record.fixes(self.runtime / "missing", UTC), [])


if __name__ == "__main__":
    unittest.main()
