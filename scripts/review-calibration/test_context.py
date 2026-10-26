import json
import logging
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

from streamlit import config
from streamlit.testing.v1 import AppTest

import context
import dashboard
import saved

PAGE = Path(__file__).with_name("pages") / "Context.py"
# As in test_dashboard: outside a running page Streamlit warns on every start that there is no page.
logging.getLogger("streamlit.runtime.scriptrunner_utils.script_run_context").addFilter(lambda record: "missing ScriptRunContext" not in record.getMessage())
OWN, SESSION = context.RUNNING


def at(second):
    return f"2026-10-10T10:{second // 60:02d}:{second % 60:02d}Z"


def usage(turn, sent, wrote, semantics=OWN, thread="t1", second=0):
    return {"type": "usage", "turnId": turn, "occurredAt": at(second), "source": {"providerThreadId": thread, "parentProviderThreadId": None},
            "payload": {"inputTokens": sent, "cachedInputTokens": 0, "outputTokens": wrote, "semantics": semantics}}


def command(line, item, state="COMPLETED", child=False, second=0, turn="a"):
    return {"type": "command", "turnId": turn, "occurredAt": at(second), "source": {"providerItemId": item, "parentProviderThreadId": "parent" if child else None},
            "payload": {"argv": ["/bin/zsh", "-lc", line], "state": state, "output": "secret output"}}


def tool(name, item, state="COMPLETED", second=0):
    return {"type": "tool", "turnId": "a", "occurredAt": at(second), "source": {"providerItemId": item}, "payload": {"name": name, "state": state, "summary": "x"}}


def asked(second=0, turn="a"):
    return {"type": "user_message", "turnId": turn, "occurredAt": at(second), "eventId": f"message-{turn}", "source": {}, "payload": {"text": "secret ask"}}


class TestCalls(unittest.TestCase):
    def test_each_call_is_the_step_of_the_running_total_and_what_the_call_before_it_ran_is_named(self):
        # Codex logs a call's tokens after the commands and tools the call started: what started before them is what it ran.
        made = context.calls([asked(0), command("rg -n secret src", "c1", state="STARTED", second=12), command("rg -n secret src", "c1", second=13),
                              tool("bytequay/review_get_context", "c2", second=13), usage("a", 100, 10, second=14),
                              command("./mvnw -o test", "c3", state="STARTED", second=30), command("sleep 1", "c4", child=True, second=31), usage("a", 260, 30, second=31),
                              usage("a", 260, 30, second=32), command("./mvnw -o test", "c3", second=90), usage("a", 500, 40, second=100),
                              asked(200, "b"), usage("b", 50, 5, second=203)])
        self.assertEqual([(call["turn"], call["call"], call["sent"], call["wrote"], call["added"], call["shrank"], call["between"], call["seconds"]) for call in made],
                         # The first call ran from the ask to its first command.
                         [("a", 1, 100, 10, None, False, [], 12),
                          # 160 sent, less the 100 sent before and the 10 written: 50 came from the search and the tool the first call ran.
                          ("a", 2, 160, 20, 50, False, ["rg", "review_get_context"], 16),
                          # The total sent again changes nothing, a subagent's command fills its own context, and the call began once the build was back.
                          ("a", 3, 240, 10, 60, False, ["maven"], 10),
                          ("b", 1, 50, 5, None, False, [], 3)])

    def test_a_context_that_shrank_was_compacted_and_adds_nothing(self):
        made = context.calls([usage("a", 100, 10), usage("a", 150, 20), usage("a", 300, 25)])
        # The third call is sent 150: 50 sent and 10 written before it, so 90 came in.
        self.assertEqual([(call["sent"], call["added"], call["shrank"]) for call in made], [(100, None, False), (50, None, True), (150, 90, False)])

    def test_a_turn_does_not_take_commands_left_after_another_turns_last_usage(self):
        made = context.calls([asked(0, "a"), usage("a", 100, 10, second=2), command("rg secret src", "c1", second=3, turn="a"),
                              asked(20, "b"), usage("b", 50, 5, second=25), command("git status", "c1", second=27, turn="b"), usage("b", 150, 10, second=30),
                              usage("b", 300, 15, second=35)])
        self.assertEqual([(call["turn"], call["between"], call["seconds"]) for call in made],
                         [("a", [], 2), ("b", [], 5), ("b", [], 2), ("b", ["git"], 5)])

    def test_a_session_total_steps_from_the_turn_before_and_the_database_total_says_where_a_turn_started(self):
        events = [usage("a", 1000, 100, SESSION), usage("a", 1300, 110, SESSION), usage("b", 1700, 130, SESSION)]
        # The log's first session total has nothing to step from, so it is left out.
        self.assertEqual([(call["turn"], call["call"], call["sent"]) for call in context.calls(events)], [("a", 2, 300), ("b", 1, 400)])
        # A turn that logged the session's total under its own name: the database's total for the turn tells its first call.
        events = [usage("x", 39_999_100, 4970), usage("x", 39_999_350, 4980), usage("x", 39_999_700, 5000)]
        made = context.calls(events, {"x": (700, 40)})
        self.assertEqual([(call["call"], call["sent"], call["wrote"], call["added"]) for call in made], [(1, 100, 10, None), (2, 250, 10, 140), (3, 350, 20, 90)])
        # A turn logged on its own reaches the database's total, so it starts at zero.
        self.assertEqual([call["sent"] for call in context.calls([usage("y", 100, 10), usage("y", 250, 20)], {"y": (250, 20)})], [100, 150])


class TestAdders(unittest.TestCase):
    def test_what_came_in_is_shared_out_and_counted_again_at_every_later_call(self):
        made = [{"task": "t", "turn": "a", "call": 1, "added": None, "between": [], "shrank": False},
                {"task": "t", "turn": "a", "call": 2, "added": 100, "between": ["rg", "review_get_context"], "shrank": False},
                {"task": "t", "turn": "a", "call": 3, "added": 300, "between": ["review_get_context"], "shrank": False},
                {"task": "t", "turn": "a", "call": 4, "added": 0, "between": [], "shrank": False}]
        rows = {row["name"]: row for row in context.adders(made)}
        # Added before call 2 of 4, it is sent at calls 2, 3 and 4; added before call 3, at calls 3 and 4.
        self.assertEqual({name: (row["calls"], row["tokens"], round(row["share"], 1), row["caused"], row["alone"]) for name, row in rows.items()},
                         {"review_get_context": (2, 350, 87.5, 750, 300), "rg": (1, 50, 12.5, 150, None)})
        self.assertEqual([row["name"] for row in context.adders(made)], ["review_get_context", "rg"])  # the most input tokens caused first

    def test_attribution_stops_when_compaction_discards_the_known_context(self):
        events = [asked(0), command("rg secret src", "c1", second=1)]
        sent, total = [100, 10110, 100, 110, 120, 130, 140, 150, 160, 170], 0
        for number, amount in enumerate(sent, 1):
            total += amount
            events.append(usage("a", total, number * 10, second=number + 1))
        made = [{"task": "t", **call} for call in context.calls(events)]
        self.assertEqual(sum(call["shrank"] for call in made), 1)
        self.assertEqual(context.adders(made), [{"name": "rg", "calls": 1, "tokens": 10000, "share": 100.0, "caused": 10000, "alone": 10000}])
        self.assertLessEqual(sum(row["caused"] for row in context.adders(made)), sum(sent))


class TestTiming(unittest.TestCase):
    def test_a_calls_time_is_split_into_a_fixed_part_and_parts_for_what_it_is_sent_and_writes(self):
        made = [{"model": "astra", "sent": 50_000 + 1000 * number, "wrote": 100 * (number % 7), "seconds": 2 + 0.5 * (0.5 + 0.01 * number) + 30 * 0.1 * (number % 7)}
                for number in range(40)] + [{"model": "flash", "sent": 1, "wrote": 1, "seconds": 1.0}] * 5 + [{"model": "astra", "sent": 1, "wrote": 1, "seconds": None}]
        rows = context.timing(made)
        self.assertEqual([row["model"] for row in rows], ["astra"])  # five calls are too few to split
        row = rows[0]
        self.assertEqual((row["calls"], round(row["fixed"], 6), round(row["per 100k sent"], 6), round(row["per 1k written"], 6), round(row["explained"], 6)),
                         (40, 2.0, 0.5, 30.0, 1.0))
        self.assertAlmostEqual(row["fixed share"] + row["context share"] + row["writing share"], 100.0)


class TestTable(unittest.TestCase):
    def test_every_log_is_read_and_a_missing_database_is_said(self):
        with tempfile.TemporaryDirectory() as directory:
            runtime = Path(directory) / "agent-runtime"
            (runtime / "task-1").mkdir(parents=True)
            (runtime / "task-1" / "events.jsonl").write_text("\n".join(json.dumps(event) for event in (command("git status", "c1"), usage("a", 100, 10), usage("a", 250, 20))) + "\n")
            made = context.table(runtime, Path(directory) / "missing.db", 1_800_000_000)
        self.assertEqual((made["database"], [(call["task"], call["call"], call["between"], call["model"]) for call in made["calls"]]),
                         ({"problem": "the database is not there"}, [("task-1", 1, [], None), ("task-1", 2, ["git"], None)]))  # the first call ran git
        self.assertNotIn("secret", json.dumps(made))  # names only, never what a command printed


class TestPage(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.folder = Path(directory.name).resolve()
        arguments = mock.patch.object(sys, "argv", ["dashboard.py", str(self.folder)])
        arguments.start()
        self.addCleanup(arguments.stop)
        config.set_option("server.address", dashboard.HOST)
        self.addCleanup(config.set_option, "server.address", None)

    def page(self, calls=None):
        if calls is not None:
            saved.write(self.folder, context.FILE, {"version": 1, "savedAt": "2026-10-16T00:00:00Z", "database": {"problem": None}, "calls": calls})
        return AppTest.from_file(str(PAGE), default_timeout=30).run()

    def call(self, turn, number, sent, added, between, start="2026-10-15T10:00:00Z", model="gpt-6-astra"):
        return {"task": "t", "turn": turn, "start": start, "call": number, "sent": sent, "wrote": 10, "added": added, "shrank": False, "between": between,
                "model": model, "effort": "max"}

    def test_tiles_both_charts_and_the_table_of_what_fills_the_context(self):
        page = self.page([self.call("a", 1, 100_000, None, []), self.call("a", 2, 103_000, 2_990, ["review_get_context"]),
                          self.call("a", 3, 104_000, 990, ["rg"]), self.call("a", 4, 60_000, None, ["sed"]) | {"shrank": True},
                          self.call("b", 1, 90_000, None, [], start="2026-09-01T10:00:00Z")])
        self.assertEqual(list(page.exception), [])
        self.assertEqual([(tile.label, tile.value) for tile in page.metric],
                         [("Model calls", "4"), ("Typical context", "102k"), ("Largest context", "104k"), ("Added per call", "1,990"), ("Compacted", "1")])
        self.assertEqual(len(page.get("plotly_chart")), 2)
        # The compacted call no longer repeats known additions from earlier calls.
        self.assertEqual(page.dataframe[0].value[["what", "calls", "added", "input tokens caused"]].values.tolist(),
                         [["review_get_context", 1, 2990, 5980], ["rg", 1, 990, 990]])
        page.segmented_control(key="span").set_value("All").run()
        self.assertEqual(page.metric[0].value, "5")  # the turn of September too

    def test_nothing_saved_says_how_to_save_it(self):
        self.assertEqual([note.value for note in self.page().info], ["Nothing is saved yet. Save it with Analyze now at the top of the page; the monitor saves it every hour while it runs."])


if __name__ == "__main__":
    unittest.main()
