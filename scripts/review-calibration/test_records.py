from pathlib import Path
import re
import sqlite3
import tempfile
import unittest
from unittest import mock

import records

BACKEND = Path(__file__).resolve().parents[2] / "backend"
ENTITIES = BACKEND / "persistence-sqlite/src/main/java/com/bytequay/persistence/sqlite/entity"
# What the application's own tables also hold, and the lab must never read.
PRIVATE = {"agent_turn": ("plan_json", "development_result", "terminal_reason"), "agent_task": ("title", "summary_json", "worktree")}
SECRET = "the password is hunter2 in /Users/someone/secret-project"


def database(path, turns=(), tasks=(), without=()):
    """A database with the two tables the lab reads. A turn is (task, id, started, elapsed ms, model, requested model,
    effort, state, tokens in, cached, tokens out, cost, tools, compactions); a task is (id, lane, owner, name)."""
    made = sqlite3.connect(path)
    for table, columns in records.COLUMNS.items():
        made.execute(f"CREATE TABLE {table} ({', '.join(name for name in (*columns, *PRIVATE[table]) if name not in without)})")
    for task, turn, started, elapsed, model, requested, effort, state, tokens_in, cached, tokens_out, cost, tools, compactions in turns:
        made.execute("INSERT INTO agent_turn (id, task_id, started_at, elapsed_millis, model, requested_model, effort, state, input_tokens, cached_input_tokens, output_tokens, cost, "
                     "tool_calls, context_compactions, plan_json, development_result, terminal_reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                     (turn, task, started, elapsed, model, requested, effort, state, tokens_in, cached, tokens_out, cost, tools, compactions, SECRET, SECRET, SECRET))
    for task, lane, owner, name in tasks:
        made.execute("INSERT INTO agent_task (id, lane_id, repository_owner, repository_name, title, summary_json, worktree) VALUES (?, ?, ?, ?, ?, ?, ?)",
                     (task, lane, owner, name, SECRET, SECRET, SECRET))
    made.commit()
    made.close()
    return path


class TestRecords(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / "bytequay with a space.db"

    def test_one_record_for_every_started_turn_under_its_task_and_turn_id(self):
        database(self.path, tasks=[("aaaa", "codex", "trinodb", "trino"), ("bbbb", "claude-account-68c1ba6e-4573", "bytequay", "bytequay")], turns=[
            ("aaaa", "t1", "2026-01-01T00:00:00.500004Z", 600_000, None, "gpt-6-astra", "max", "COMPLETED", 1000, 900, 50, None, 12, 1),
            ("bbbb", "t1", "2026-01-02T08:00:00+08:00", 90_500, "opus-actual", "opus", "Extra", "INTERRUPTED", 2000, 0, 80, 1.25, None, None),
            ("gone", "t7", "2026-01-03T00:00:00Z", None, "", None, None, None, 500, 400, 30, None, None, None),
            ("aaaa", "t2", None, 0, None, "gpt-6-astra", "max", "QUEUED", 0, 0, 0, None, 0, 0)])
        made = records.read(self.path)
        self.assertEqual(sorted(made), [("aaaa", "t1"), ("bbbb", "t1"), ("gone", "t7")])  # a turn that never started has no time to place it at
        self.assertEqual(made[("aaaa", "t1")], {
            "start": 1767225600.500004, "seconds": 600, "provider": "codex", "model": "gpt-6-astra", "effort": "max", "repository": "trinodb/trino", "outcome": "completed",
            "inputTokens": 1000, "cachedInputTokens": 900, "outputTokens": 50, "cost": None, "toolCalls": 12, "compactions": 1})
        second = made[("bbbb", "t1")]
        # The model that ran wins over the one asked for; a lane keeps its provider and drops the account; effort is one spelling.
        self.assertEqual((second["model"], second["provider"], second["effort"], second["outcome"], second["cost"], second["seconds"]),
                         ("opus-actual", "claude", "extra", "interrupted", 1.25, 90.5))
        self.assertEqual(second["start"], 1767312000)  # any offset is read as the same moment
        self.assertEqual((second["toolCalls"], second["compactions"]), (None, None))  # not recorded is not zero
        gone = made[("gone", "t7")]
        self.assertEqual((gone["provider"], gone["model"], gone["effort"], gone["repository"], gone["outcome"], gone["seconds"]), (None, None, None, None, None, 0))
        # Its task is gone, so nobody can say whether these tokens are the turn's own or a running total: none are shown.
        self.assertEqual((gone["inputTokens"], gone["cachedInputTokens"], gone["outputTokens"]), (None, None, None))

    def test_codex_turn_gets_what_the_running_total_grew_by_and_a_claude_turn_keeps_its_own_tokens(self):
        turn = lambda task, name, hour, tokens_in, cached, tokens_out: (task, name, f"2026-01-01T{hour:02}:00:00Z", 1000, None, "m", "max", "COMPLETED", tokens_in, cached, tokens_out,
                                                                        None, 1, 0)
        database(self.path, tasks=[("codex-task", "codex", "o", "n"), ("claude-task", "claude", "o", "n"), ("other-task", "deepseek-key-9621", "o", "n")], turns=[
            turn("codex-task", "c3", 3, 900, 700, 90), turn("codex-task", "c1", 1, 100, 80, 10), turn("codex-task", "c2", 2, 400, 300, 50),
            turn("codex-task", "c4", 4, 250, 200, 20), turn("codex-task", "c5", 5, 250, 200, 20), turn("codex-task", "c6", 6, None, None, None), turn("codex-task", "c7", 7, 300, 240, 26),
            turn("claude-task", "a1", 1, 500, 400, 30), turn("claude-task", "a2", 2, 900, 100, 40), turn("other-task", "d1", 1, 700, 600, 50)])
        made = records.read(self.path)
        tokens = lambda task, name: tuple(made[(task, name)][count] for count in records.TOKENS)
        # In the order the turns started, whatever order the rows are stored in: 100, then up to 400, then up to 900.
        self.assertEqual([tokens("codex-task", name) for name in ("c1", "c2", "c3")], [(100, 80, 10), (300, 220, 40), (500, 400, 40)])
        self.assertEqual(tokens("codex-task", "c4"), (250, 200, 20))  # the total fell: the thread began again, so this is the turn's own
        self.assertEqual(tokens("codex-task", "c5"), (0, 0, 0))  # the total did not move
        self.assertEqual((tokens("codex-task", "c6"), tokens("codex-task", "c7")), ((None, None, None), (50, 40, 6)))  # a turn with nothing recorded is skipped over
        self.assertEqual([tokens("claude-task", name) for name in ("a1", "a2")], [(500, 400, 30), (900, 100, 40)])
        self.assertEqual(tokens("other-task", "d1"), (None, None, None))  # a provider whose way of counting the lab does not know

    def test_application_still_counts_tokens_the_way_the_lab_undoes(self):
        if not BACKEND.is_dir():
            self.skipTest("the application's source is not beside the lab")
        codex = (BACKEND / "agent-cli/src/main/java/com/bytequay/agent/cli/codex/CodexEventMapper.java").read_text()
        claude = (BACKEND / "agent-cli/src/main/java/com/bytequay/agent/cli/claude/ClaudeEventMapper.java").read_text()
        service = (BACKEND / "agent-core/src/main/java/com/bytequay/agent/core/impl/DefaultAgentMetricsService.java").read_text()
        # If one of these fails, the application changed how it stores a turn's tokens: change RUNNING_TOTAL and OWN in records.py to match.
        self.assertIn("ProviderUsageSample.Semantics.CUMULATIVE_FOR_INVOCATION", codex)
        self.assertIn("ProviderUsageSample.Semantics.FINAL_FOR_INVOCATION", claude)
        self.assertRegex(service, r"case CUMULATIVE_FOR_INVOCATION -> cumulativeMetrics\(AgentMetrics\.empty\(\),")
        self.assertRegex(service, r"case DELTA, FINAL_FOR_INVOCATION -> addMetrics\(")

    def test_nothing_but_the_listed_columns_is_asked_for_or_comes_out(self):
        asked = {(table, column) for table, column in re.findall(r"\b(turn|task)\.(\w+)", records.QUERY)}
        listed = {("turn", column) for column in records.COLUMNS["agent_turn"]} | {("task", column) for column in records.COLUMNS["agent_task"]}
        self.assertEqual(asked, listed)
        database(self.path, tasks=[("aaaa", "codex", "o", "n")], turns=[("aaaa", "t1", "2026-01-01T00:00:00Z", 1, None, "m", "max", "COMPLETED", 1, 1, 1, None, 1, 1)])
        text = repr(records.read(self.path))
        for secret in ("hunter2", "secret-project", "someone", "password"):
            self.assertNotIn(secret, text)

    def test_database_is_opened_so_that_it_cannot_be_written(self):
        database(self.path)
        with mock.patch.object(records.sqlite3, "connect", wraps=sqlite3.connect) as opened:
            self.assertEqual(records.read(self.path), {})
        (address,), options = opened.call_args
        self.assertEqual((address.startswith("file:"), address.endswith("?mode=ro"), options["uri"]), (True, True, True))
        self.assertIn("bytequay%20with%20a%20space.db", address)
        with self.assertRaises(sqlite3.OperationalError):
            sqlite3.connect(address, uri=True).execute("DELETE FROM agent_turn")

    def test_missing_database_and_another_shape_fail_and_are_never_an_empty_result(self):
        with self.assertRaises(OSError):
            records.read(self.path)
        self.assertFalse(self.path.exists())  # looking for it must not create it
        database(self.path, without=("requested_model",))
        with self.assertRaisesRegex(sqlite3.Error, "requested_model"):
            records.read(self.path)

    def test_columns_the_lab_reads_exist_in_the_applications_tables(self):
        if not ENTITIES.is_dir():
            self.skipTest("the application's source is not beside the lab")
        for table, columns in records.COLUMNS.items():
            source = (ENTITIES / ("".join(word.title() for word in table.split("_")) + "Entity.java")).read_text()
            self.assertIn(f'@Table(name = "{table}")', source)
            for column in columns:
                field = re.sub(r"_(\w)", lambda found: found.group(1).upper(), column)
                with self.subTest(table=table, column=column):
                    self.assertRegex(source, rf"private [\w<>]+ {field};")


if __name__ == "__main__":
    unittest.main()
