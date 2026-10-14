import ast
import json
import logging
from datetime import datetime, timezone
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import pandas
from streamlit import config
from streamlit.testing.v1 import AppTest

import dashboard
import tuning

PAGE = Path(__file__).with_name("pages") / "Tuning.py"
logging.getLogger("streamlit.runtime.scriptrunner_utils.script_run_context").addFilter(lambda record: "missing ScriptRunContext" not in record.getMessage())


def event(minute, kind, payload, turn, second=0):
    return {"occurredAt": f"2026-10-12T10:{minute:02d}:{second:02d}+00:00", "turnId": turn, "type": kind, "payload": payload}


def call(minute, start, end, kind, payload, turn):
    """A command or tool call that started and ended within one minute."""
    return [event(minute, kind, payload | {"state": "STARTED"}, turn, start), event(minute, kind, payload | {"state": "COMPLETED", "exitCode": 0}, turn, end)]


def phase(minute, name, turn):
    return event(minute, "lifecycle", {"phase": name}, turn)


def message(minute, delivery, turn):
    return event(minute, "user_message", {"text": "never read", "attachments": [], "deliveryKind": delivery}, turn)


# Task a: one turn asked, with an approval allowed, one denied and one left to expire, two rate limits and a retry; a second turn from
# the queue that was steered twice and interrupted. Task b: one message. Task c: two messages, so one more after the ask.
LOGS = {
    "task-a": [message(0, "TURN", "t1"), phase(0, "TURN_STARTED", "t1"), event(1, "approval_requested", {"approvalId": "a1", "approvalKind": "COMMAND"}, "t1"),
               event(2, "approval_resolved", {"approvalId": "a1", "outcome": "ALLOWED"}, "t1"), event(3, "approval_requested", {"approvalId": "a2"}, "t1"),
               event(4, "approval_resolved", {"approvalId": "a2", "outcome": "EXPIRED"}, "t1"), event(4, "approval_requested", {"approvalId": "a3"}, "t1"),
               event(5, "approval_resolved", {"approvalId": "a3", "outcome": "DENIED"}, "t1"), phase(5, "RATE_LIMITED", "t1"), phase(5, "RATE_LIMITED", "t1"), phase(5, "RETRYING", "t1"),
               phase(6, "TURN_COMPLETED", "t1"), message(7, "QUEUED", "t2"), phase(8, "TURN_STARTED", "t2"), message(9, "STEER", "t2"), message(9, "STEER", "t2"), phase(10, "INTERRUPTED", "t2"),
               message(11, "TURN", "t9"),  # a turn that never started is no turn
               *call(0, 10, 12, "tool", {"name": "bytequay/bytequay_list_tools"}, "t1"), *call(0, 13, 14, "tool", {"name": "mcp__bytequay__review_get_context"}, "t1"),
               *call(0, 15, 16, "tool", {"name": "review_get_context"}, "t1"), *call(0, 17, 18, "tool", {"name": "review_get_context"}, "t1"),
               *call(0, 20, 21, "command", {"argv": ["rg", "needle"]}, "t1"), *call(0, 22, 23, "tool", {"name": "Read"}, "t1"),
               *call(0, 24, 25, "tool", {"name": "Automatic approval"}, "t1")],  # ByteQuay's own step, not a call the agent chose
    "task-b": [message(20, "TURN", "t3"), phase(20, "TURN_STARTED", "t3"), phase(25, "TURN_COMPLETED", "t3")],
    # Its first turn runs an edit inside a search: they end in the other order, and the path still goes by when each started.
    "task-c": [message(30, "TURN", "t4"), phase(30, "TURN_STARTED", "t4"), event(30, "command", {"argv": ["rg", "x"], "state": "STARTED"}, "t4", 5),
               *call(30, 7, 8, "tool", {"name": "Edit"}, "t4"), event(30, "command", {"argv": ["rg", "x"], "state": "COMPLETED", "exitCode": 0}, "t4", 9),
               phase(32, "TURN_COMPLETED", "t4"), message(40, "TURN", "t5"), phase(40, "TURN_STARTED", "t5"),
               *(made for second in (5, 7, 9) for made in call(40, second, second + 1, "tool", {"name": "review_get_context"}, "t5")), phase(41, "TURN_COMPLETED", "t5")],
}


class Saved(unittest.TestCase):
    """Task logs for three tasks, and a database that still knows two of them and three of their turns."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name).resolve()
        self.runtime, self.database, self.folder = self.root / "agent-runtime", self.root / "bytequay.db", self.root / "lab"
        for task, events in LOGS.items():
            (self.runtime / task).mkdir(parents=True)
            (self.runtime / task / "events.jsonl").write_text("".join(json.dumps(made) + "\n" for made in events) + "{cut off\n")
        with sqlite3.connect(self.database) as connection:
            connection.executescript("CREATE TABLE agent_turn (id TEXT, development_origin_kind TEXT, cancellation_requested_at TEXT, state TEXT, terminal_reason TEXT);"
                                     "CREATE TABLE agent_task (id TEXT, state TEXT, interactive INTEGER, repository_owner TEXT, repository_name TEXT);")
            connection.executemany("INSERT INTO agent_turn VALUES (?, ?, ?, ?, ?)", [("t1", None, None, "COMPLETED", "free text"), ("t2", None, "2026-10-12", "INTERRUPTED", None),
                                                                                     ("t3", "CREATE_DRAFT_PR", None, "COMPLETED", None)])
            connection.executemany("INSERT INTO agent_task VALUES (?, ?, ?, ?, ?)", [("task-a", "OPEN", 0, "o", "n"), ("task-b", "COMPLETED", 1, None, None)])
        connection.close()

    def made(self, database=None):
        return tuning.table(self.runtime, database or self.database, datetime(2026, 10, 14, tzinfo=timezone.utc))


class TestTable(Saved):
    def test_every_turn_counts_how_messages_came_and_how_approvals_ended(self):
        rows = {row["turn"]: row for row in tuning.turns_of("task-a", LOGS["task-a"])}
        self.assertEqual(list(rows), ["t1", "t2"])
        self.assertEqual({name: rows["t1"][name] for name in ("messages", "queued", "steers", "answered", "unanswered", "rateLimits", "retries", "interruptions", "seconds")},
                         {"messages": 1, "queued": 0, "steers": 0, "answered": 2, "unanswered": 1, "rateLimits": 2, "retries": 1, "interruptions": 0, "seconds": 360})
        self.assertEqual([rows["t2"][name] for name in ("messages", "queued", "steers", "interruptions")], [0, 1, 2, 1])
        # The calls in the order they started, by name without their server; an automatic approval is no call.
        self.assertEqual({name: rows["t1"][name] for name in ("calls", "tools", "paths", "streak", "lookups", "lookupSeconds")},
                         {"calls": 6, "tools": {"bytequay_list_tools": 1, "review_get_context": 3, "rg": 1, "Read": 1},
                          "paths": [["bytequay_list_tools", "review_get_context", 1], ["review_get_context", "review_get_context", 2], ["review_get_context", "rg", 1], ["rg", "Read", 1]],
                          "streak": ["review_get_context", 3], "lookups": 1, "lookupSeconds": 2.0})
        self.assertEqual({name: rows["t2"][name] for name in ("calls", "tools", "paths", "streak", "lookups")}, {"calls": 0, "tools": {}, "paths": [], "streak": None, "lookups": 0})
        first = tuning.turns_of("task-c", LOGS["task-c"])[0]
        self.assertEqual((first["paths"], first["streak"]), ([["rg", "Edit", 1]], ["rg", 1]))  # of two runs as long, the first

    def test_tools_are_listed_by_calls_and_the_chart_counts_which_call_follows_which(self):
        turns, _ = tuning.frames(self.made(), zone=timezone.utc)
        table = tuning.tools_table(turns)
        self.assertEqual(table.values.tolist(), [["review_get_context", 2, 6, 3, 3, 4 / 6], ["rg", 2, 2, 1, 1, 0], ["Edit", 1, 1, 1, 1, 0], ["Read", 1, 1, 1, 1, 0],
                                                 ["bytequay_list_tools", 1, 1, 1, 1, 0]])
        uneven = pandas.DataFrame({"turn": ["a", "b", "c"], "tools": [{"x": 1}, {"x": 1}, {"x": 4}], "paths": [[], [], []]})
        self.assertEqual(tuning.tools_table(uneven)[["per turn", "most in a turn"]].values.tolist(), [[1, 4]])  # the middle turn, not the mean
        self.assertEqual(list(table.columns), ["tool", "turns", "calls", "per turn", "most in a turn", "after itself"])
        heat = tuning.follows_figure(turns, "light").data[0]
        self.assertEqual(list(heat.x), ["review_get_context", "rg", "bytequay_list_tools", "Read", "Edit"])
        cell = lambda before, after: heat.customdata[list(heat.y).index(before)][list(heat.x).index(after)]
        self.assertEqual([cell("review_get_context", "review_get_context"), cell("bytequay_list_tools", "review_get_context"), cell("rg", "Edit"), cell("Edit", "rg")], [4, 1, 1, 0])
        self.assertEqual(list(tuning.follows_figure(turns, "light", top=2).data[0].x), ["review_get_context", "rg"])

    def test_database_adds_how_turns_ended_and_what_tasks_are_without_writing(self):
        before = self.database.stat().st_mtime_ns
        made = self.made()
        self.assertEqual((made["skippedLines"], made["database"], self.database.stat().st_mtime_ns), (3, {"problem": None}, before))
        self.assertEqual([(row["turn"], row["origin"], row["cancelAsked"], row["ended"]) for row in made["turns"]],
                         [("t1", None, False, "COMPLETED"), ("t2", None, True, "INTERRUPTED"), ("t3", "CREATE_DRAFT_PR", False, "COMPLETED"), ("t4", None, None, None), ("t5", None, None, None)])
        self.assertEqual(made["tasks"], [{"task": "task-a", "state": "OPEN", "interactive": False, "repository": "o/n"},
                                         {"task": "task-b", "state": "COMPLETED", "interactive": True, "repository": None},
                                         {"task": "task-c", "state": None, "interactive": None, "repository": None}])  # gone from the database: no state, its turns kept
        self.assertNotIn("free text", json.dumps(made))
        missing = self.made(self.root / "missing.db")
        self.assertTrue(missing["database"]["problem"].startswith("the database could not be read"))
        self.assertEqual(len(missing["turns"]), 5)

    def test_frames_tell_the_ask_from_every_later_message_and_add_up_each_task(self):
        turns, tasks = tuning.frames(self.made(), zone=timezone.utc)
        self.assertEqual(turns["followUps"].tolist(), [0, 0, 0, 0, 1])
        self.assertEqual(turns["week"].astype(str).unique().tolist(), ["2026-10-12"])
        self.assertEqual(tasks[["task", "messages", "followUps", "queued", "steers", "answered", "unanswered", "cancels", "touches"]].values.tolist(),
                         [["task-a", 1, 0, 1, 2, 2, 1, 1, 7], ["task-b", 1, 0, 0, 0, 0, 0, 0, 1], ["task-c", 2, 1, 0, 0, 0, 0, 0, 2]])
        self.assertEqual(tuning.steps_rows(tasks), [("another message", 1, 1 / 3), ("a message queued while it worked", 1, 1 / 3), ("a message into a running turn", 2, 2 / 3),
                                                    ("an approval answered", 2, 2 / 3), ("a turn stopped", 1, 1 / 3)])
        self.assertEqual(tuning.sizes_rows(tasks), [("1", 2), ("2", 1), ("3 to 4", 0), ("5 to 8", 0), ("9 or more", 0)])
        self.assertEqual(tuning.busiest(tasks).values.tolist(), [["task-c", "not known", "not known", 2, 2, 0, 0, 0, 0], ["task-a", "o/n", "open", 2, 1, 1, 2, 2, 1],
                                                                 ["task-b", "not known", "completed", 1, 1, 0, 0, 0, 0]])
        figure = tuning.count_bars(tuning.sizes_rows(tasks), "light", "tasks")
        self.assertEqual((figure.layout.yaxis.type, list(figure.data[0].y)), ("category", ["1", "2", "3 to 4", "5 to 8", "9 or more"]))  # every size, as a name
        drawn = {bar.name: bar for bar in tuning.endings_figure(turns, "light").data}
        self.assertEqual((list(drawn), drawn["not known"].marker.pattern.shape, drawn["completed"].marker.pattern.shape), (["completed", "interrupted", "not known"], "/", None))
        later = datetime(2026, 10, 12, 10, 30, tzinfo=timezone.utc).timestamp()
        turns, tasks = tuning.frames(self.made(), since=later)
        self.assertEqual((tasks["task"].tolist(), tasks["followUps"].tolist()), (["task-c"], [1]))  # the first turn after the moment counts as the ask

    def test_save_writes_the_table_and_an_empty_runtime_saves_nothing(self):
        script = Path(tuning.__file__)
        done = subprocess.run([sys.executable, str(script), "--runtime", str(self.runtime), "--database", str(self.database), "--save", str(self.folder)], capture_output=True, text=True)
        self.assertEqual(done.returncode, 0)
        self.assertEqual(done.stdout.splitlines()[0], "5 turns of 3 tasks; 10 times a person stepped in")
        self.assertEqual(json.loads((self.folder / "tuning.json").read_text())["version"], 1)
        (self.root / "empty").mkdir()
        done = subprocess.run([sys.executable, str(script), "--runtime", str(self.root / "empty"), "--database", str(self.database), "--save", str(self.root / "elsewhere")],
                              capture_output=True, text=True)
        self.assertEqual((done.returncode, (self.root / "elsewhere").exists()), (0, False))


class TestPage(Saved):
    def setUp(self):
        super().setUp()
        arguments = mock.patch.object(sys, "argv", ["dashboard.py", str(self.folder)])
        arguments.start()
        self.addCleanup(arguments.stop)
        config.set_option("server.address", dashboard.HOST)
        self.addCleanup(config.set_option, "server.address", None)

    def page(self):
        return AppTest.from_file(str(PAGE), default_timeout=30).run()

    def save(self, text=None):
        self.folder.mkdir(exist_ok=True)
        (self.folder / "tuning.json").write_text(json.dumps(self.made()) if text is None else text)

    def test_page_shows_a_person_in_the_loop_and_how_turns_ended(self):
        self.save()
        page = self.page()
        self.assertEqual((list(page.exception), [note.value for note in page.warning]), ([], []))
        self.assertEqual([heading.value for heading in page.subheader], ["A person in the loop", "How turns ended", "Tool paths"])
        self.assertEqual([(tile.label, tile.value) for tile in page.metric],
                         [("Tasks", "3"), ("One-ask tasks", "33%"), ("Messages per task", "1"), ("Approvals", "2"), ("Turns stopped", "1"), ("Turns", "5"), ("Completed", "67%"),
                          ("Calls", "11"), ("Calls per turn", "3"), ("Tool lookups", "1"), ("Same call in a row", "3×")])  # per turn: of the turns that made calls
        self.assertIn("For every 100 turns: 40 rate limits, 20 retries, 20 interruptions. Rate limits and retries over time are on the Slow spots tab.",
                      [caption.value for caption in page.caption])
        self.assertEqual(len(page.get("plotly_chart")), 4)
        self.assertEqual(page.dataframe[0].value["task"].tolist(), ["task-c", "task-a", "task-b"])

    def test_page_says_how_to_save_and_names_a_file_it_cannot_read(self):
        page = self.page()
        self.assertEqual(([note.value for note in page.info], len(page.metric)), (["Nothing saved yet. Save the tuning table with `uv run tuning.py --save`"], 0))
        for text in ('{"version": 1, "turns": [', json.dumps({"version": 2}), json.dumps({"version": 1, "turns": [{"task": "a"}], "tasks": []})):
            with self.subTest(text=text[:30]):
                self.save(text)
                page = self.page()
                self.assertEqual(([note.value for note in page.warning], list(page.exception)),
                                 (["The saved tuning table could not be read. Save it again with `uv run tuning.py --save`"], []))

    def test_table_saved_before_tool_paths_were_counted_asks_for_a_new_save_and_shows_the_rest(self):
        made = self.made()
        old = {**made, "turns": [{name: value for name, value in row.items() if name not in ("calls", "tools", "paths", "streak", "lookups", "lookupSeconds")} for row in made["turns"]]}
        self.save(json.dumps(old))
        page = self.page()
        self.assertEqual((list(page.exception), [note.value for note in page.info]),
                         ([], ["This table was saved before tool paths were counted. Save it again with `uv run tuning.py --save`"]))
        self.assertEqual(len(page.get("plotly_chart")), 3)  # the person in the loop and the endings are still drawn

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
