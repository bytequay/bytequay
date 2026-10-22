from contextlib import closing
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
PRIVATE = {"agent_turn": ("plan_json", "development_result", "terminal_reason"), "agent_task": ("title", "summary_json", "worktree", "lane_id"),
           "agent_review": ("number", "task_id"), "agent_review_run": ("outcome", "summary_json", "investigation_json"),
           "agent_review_finding": ("title", "body", "state_reason", "evidence_json")}
ALIASES = {"turn": "agent_turn", "task": "agent_task", "review": "agent_review", "run": "agent_review_run", "finding": "agent_review_finding"}
SECRET = "the password is hunter2 in /Users/someone/secret-project"


def database(path, turns=(), tasks=(), without=(), reviews=(), runs=(), findings=()):
    """A database with the tables the lab reads. A turn is (task, id, started, elapsed ms, model, requested model,
    effort, state, tokens in, cached, tokens out, cost, tools, compactions); a task is (id, lane, owner, name); a review is
    (id, repository); a run is (id, review, state, created, ended, repaired, turn ids); a finding is (run, state, severity).
    A turn snapshots its task's lane. Every other column is not read by the lab."""
    made = sqlite3.connect(path)
    for table, columns in records.COLUMNS.items():
        made.execute(f"CREATE TABLE {table} ({', '.join(name for name in (*columns, *PRIVATE[table]) if name not in without)})")
    lanes = {task: lane for task, lane, _, _ in tasks}
    for task, turn, started, elapsed, model, requested, effort, state, tokens_in, cached, tokens_out, cost, tools, compactions in turns:
        made.execute("INSERT INTO agent_turn (id, task_id, started_at, elapsed_millis, model, requested_model, effort, state, input_tokens, cached_input_tokens, output_tokens, cost, "
                     "tool_calls, context_compactions, plan_json, development_result, terminal_reason, lane_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                     (turn, task, started, elapsed, model, requested, effort, state, tokens_in, cached, tokens_out, cost, tools, compactions, SECRET, SECRET, SECRET, lanes.get(task)))
    for task, lane, owner, name in tasks:
        made.execute("INSERT INTO agent_task (id, lane_id, repository_owner, repository_name, title, summary_json, worktree) VALUES (?, ?, ?, ?, ?, ?, ?)",
                     (task, lane, owner, name, SECRET, SECRET, SECRET))
    for review, repository in reviews:
        made.execute("INSERT INTO agent_review (id, repo, number, task_id) VALUES (?, ?, ?, ?)", (review, repository, SECRET, SECRET))
    for run, review, state, created, ended, repaired, turn_ids in runs:
        made.execute("INSERT INTO agent_review_run (id, review_id, state, created_at, ended_at, completion_repair_used, turn_ids_json, outcome, summary_json, investigation_json) "
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", (run, review, state, created, ended, repaired, turn_ids, SECRET, SECRET, SECRET))
    for run, state, severity in findings:
        made.execute("INSERT INTO agent_review_finding (run_id, state, severity, title, body, state_reason, evidence_json) VALUES (?, ?, ?, ?, ?, ?, ?)",
                     (run, state, severity, SECRET, SECRET, SECRET, SECRET))
    made.commit()
    made.close()
    return path


def reviewed(path):
    """Two reviews. The first ran twice on Codex and raised three findings; the second ran once on Claude, is still running and raised one."""
    turn = lambda task, name, hour, model, effort, tokens_in, cost: (task, name, f"2026-01-01T{hour:02}:00:00Z", 60_000, None, model, effort, "COMPLETED", tokens_in,
                                                                    tokens_in // 2, 10, cost, 1, 0)
    return database(path, tasks=[("aaaa-0001", "codex", "trinodb", "trino"), ("bbbb-0002", "claude", "o", "n")],
                    turns=[turn("aaaa-0001", "t1", 1, "gpt-6-astra", "max", 1000, None), turn("aaaa-0001", "t2", 2, "gpt-6-astra", "max", 3000, None),
                           turn("aaaa-0001", "t3", 3, "gpt-6-sol", "high", 3500, None), turn("bbbb-0002", "u1", 4, "opus", "Extra", 800, 2.5)],
                    reviews=[("review-1", "trinodb/trino"), ("review-2", "o/n")],
                    runs=[("run-b", "review-1", "FAILED", "2026-01-01T03:00:00Z", "2026-01-01T03:10:00Z", 1, '["t3", "a turn the database lost"]'),
                          ("run-a", "review-1", "ACCEPTED", "2026-01-01T01:00:00Z", "2026-01-01T01:30:30Z", 0, '["t1", "t2"]'),
                          ("run-c", "review-2", "RUNNING", "2026-01-01T04:00:00Z", None, 0, '["u1"]'), ("run-d", "review-2", "QUEUED", None, None, 0, None)],
                    findings=[("run-a", "RESOLVED", "Medium"), ("run-a", "WITHDRAWN", None), ("run-a", "OPEN", "High"), ("run-c", "OPEN", None), ("run-d", "OPEN", None),
                              ("a run the database lost", "OPEN", None)])


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
            "inputTokens": 1000, "cachedInputTokens": 900, "outputTokens": 50, "cost": None, "toolCalls": 12, "compactions": 1, "setup": None})
        second = made[("bbbb", "t1")]
        # The model that ran wins over the one asked for; a lane keeps its provider and drops the account; effort is one spelling.
        self.assertEqual((second["model"], second["provider"], second["effort"], second["outcome"], second["cost"], second["seconds"]),
                         ("opus-actual", "claude", "extra", "interrupted", 1.25, 90.5))
        self.assertEqual(second["start"], 1767312000)  # any offset is read as the same moment
        self.assertEqual((second["toolCalls"], second["compactions"]), (None, None))  # not recorded is not zero
        gone = made[("gone", "t7")]
        self.assertEqual((gone["provider"], gone["model"], gone["effort"], gone["repository"], gone["outcome"], gone["seconds"]), (None, None, None, None, None, 0))
        # No provider snapshot is present, so nobody can say how these amounts were counted: none are shown.
        self.assertEqual((gone["inputTokens"], gone["cachedInputTokens"], gone["outputTokens"], gone["cost"]), (None, None, None, None))

    def test_each_turn_keeps_its_provider_after_a_task_switch_or_removal(self):
        turn = lambda name, model: ("task", name, "2026-01-01T00:00:00Z", 1000, model, model, "high", "COMPLETED", 100, 80, 10, 0.5, 1, 0)
        database(self.path, tasks=[("task", "claude", "o", "n")], turns=[turn("first", "gpt-6-sol"), turn("second", "opus"), turn("unknown", "m")],
                 reviews=[("review", "o/n")], runs=[("run", "review", "ACCEPTED", "2026-01-01T00:00:00Z", "2026-01-01T00:01:00Z", 0, '["first"]')])
        with closing(sqlite3.connect(self.path)) as made, made:
            made.execute("UPDATE agent_turn SET lane_id = 'codex' WHERE id = 'first'")
            made.execute("UPDATE agent_turn SET lane_id = NULL WHERE id = 'unknown'")
        found = records.read(self.path)
        self.assertEqual([found[("task", name)]["provider"] for name in ("first", "second", "unknown")], ["codex", "claude", None])
        self.assertEqual([found[("task", name)]["inputTokens"] for name in ("first", "second", "unknown")], [100, 100, None])
        self.assertEqual(records.reviews(self.path, found)["runs"][0]["provider"], "codex")
        with closing(sqlite3.connect(self.path)) as made, made:
            made.execute("DELETE FROM agent_task")
        self.assertEqual(records.read(self.path)[("task", "first")]["provider"], "codex")

    def test_turn_keeps_the_setup_it_ran_with_and_a_database_from_before_setups_has_none(self):
        turns = [("aaaa", name, f"2026-01-01T0{hour}:00:00Z", 1000, None, "m", "max", "COMPLETED", 1, 1, 1, None, 1, 0) for hour, name in enumerate(("t1", "t2"))]
        database(self.path, tasks=[("aaaa", "codex", "o", "n")], turns=turns)
        with closing(sqlite3.connect(self.path)) as made:
            made.execute("UPDATE agent_turn SET setup_fingerprint = 'aaaaaaaa-bbbbbbbb' WHERE id = 't1'")
            made.commit()
        setups = lambda path: {key: one["setup"] for key, one in records.read(path).items()}
        self.assertEqual(setups(self.path), {("aaaa", "t1"): "aaaaaaaa-bbbbbbbb", ("aaaa", "t2"): None})
        older = database(self.path.with_name("older.db"), tasks=[("aaaa", "codex", "o", "n")], turns=turns, without=("setup_fingerprint",))
        self.assertEqual(setups(older), {("aaaa", "t1"): None, ("aaaa", "t2"): None})

    def test_codex_and_claude_turns_keep_their_own_tokens_and_another_provider_shows_none(self):
        turn = lambda task, name, hour, tokens_in, cached, tokens_out: (task, name, f"2026-01-01T{hour:02}:00:00Z", 1000, None, "m", "max", "COMPLETED", tokens_in, cached, tokens_out,
                                                                        None, 1, 0)
        database(self.path, tasks=[("codex-task", "codex", "o", "n"), ("claude-task", "claude", "o", "n"), ("other-task", "deepseek-key-9621", "o", "n")], turns=[
            turn("codex-task", "c1", 1, 100, 80, 10), turn("codex-task", "c2", 2, 400, 300, 50), turn("codex-task", "c3", 3, 250, 200, 20),
            turn("claude-task", "a1", 1, 500, 400, 30), turn("claude-task", "a2", 2, 900, 100, 40), turn("other-task", "d1", 1, 700, 600, 50)])
        made = records.read(self.path)
        tokens = lambda task, name: tuple(made[(task, name)][count] for count in records.TOKENS)
        # A later turn of a task is not read against the one before it: each row is that turn's own tokens.
        self.assertEqual([tokens("codex-task", name) for name in ("c1", "c2", "c3")], [(100, 80, 10), (400, 300, 50), (250, 200, 20)])
        self.assertEqual([tokens("claude-task", name) for name in ("a1", "a2")], [(500, 400, 30), (900, 100, 40)])
        self.assertEqual(tokens("other-task", "d1"), (None, None, None))  # a provider whose way of counting the lab does not know

    def test_claude_turn_keeps_its_own_cost_and_another_provider_shows_none(self):
        turn = lambda task, name, hour, cost: (task, name, f"2026-01-01T{hour:02}:00:00Z", 1000, None, "opus", "max", "COMPLETED", 100 * hour, 90 * hour, 10, cost, 1, 0)
        database(self.path, tasks=[("claude-task", "claude", "o", "n"), ("codex-task", "codex", "o", "n"), ("other-task", "deepseek-key-9621", "o", "n")], turns=[
            turn("claude-task", "a2", 2, 15.72), turn("claude-task", "a1", 1, 14.96), turn("claude-task", "a3", 3, None), turn("claude-task", "a4", 4, 1.33),
            turn("codex-task", "c1", 1, 9.0), turn("other-task", "d1", 1, 2.0)])
        made = records.read(self.path)
        # A later turn of a task is not read against the one before it: each stored cost is that turn's own.
        self.assertEqual([made[("claude-task", name)]["cost"] for name in ("a1", "a2", "a3", "a4")], [14.96, 15.72, None, 1.33])
        # A cost on a Codex turn would be a per-turn amount as far as the lab knows; one of an unknown provider is not shown.
        self.assertEqual((made[("codex-task", "c1")]["cost"], made[("other-task", "d1")]["cost"]), (9.0, None))

    def test_application_still_counts_tokens_and_cost_the_way_the_lab_reads_them(self):
        if not BACKEND.is_dir():
            self.skipTest("the application's source is not beside the lab")
        codex = (BACKEND / "agent-cli/src/main/java/com/bytequay/agent/cli/codex/CodexEventMapper.java").read_text()
        claude = (BACKEND / "agent-cli/src/main/java/com/bytequay/agent/cli/claude/ClaudeEventMapper.java").read_text()
        service = (BACKEND / "agent-core/src/main/java/com/bytequay/agent/core/impl/DefaultAgentMetricsService.java").read_text()
        # If one of these fails, the application changed how it stores a turn's tokens or cost: change OWN in records.py to match.
        # Codex reports the running total of a thread, and the application takes out what the thread had before the turn.
        self.assertIn("CodexProtocol.TokenUsageBreakdown own = minus(total, baseline);", codex)
        self.assertRegex(codex, r"Semantics\.CUMULATIVE_FOR_INVOCATION,\s+own\.inputTokens\(\),\s+own\.cachedInputTokens\(\),\s+own\.outputTokens\(\),")
        # Claude reports the running cost of a session, and the application takes out what the task's other turns already hold.
        self.assertIn("sessionCost ? ProviderUsageSample.Semantics.FINAL_WITH_SESSION_COST : ProviderUsageSample.Semantics.FINAL_FOR_INVOCATION", claude)
        self.assertRegex(service, r"case CUMULATIVE_FOR_INVOCATION -> cumulativeMetrics\(AgentMetrics\.empty\(\),")
        self.assertRegex(service, r"case DELTA, FINAL_FOR_INVOCATION -> addMetrics\(")
        self.assertIn("case FINAL_WITH_SESSION_COST -> addMetrics(turn.metrics(), sample, sample.cost().flatMap(sessionCost -> ownCost(taskId, turnId, sessionCost)));", service)

    def test_nothing_but_the_listed_columns_is_asked_for_or_comes_out(self):
        asked = {(ALIASES[alias], column) for query in (records.QUERY, records.RUNS, records.FINDINGS) for alias, column in re.findall(r"\b(\w+)\.(\w+)", query)}
        self.assertEqual(asked, {(table, column) for table, columns in records.COLUMNS.items() for column in columns})
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

    def test_review_run_takes_its_model_tokens_and_cost_from_its_own_turns_and_a_finding_keeps_only_labels(self):
        made = records.reviews(reviewed(self.path), records.read(self.path))
        # In the order the runs started; a run that never started is left out, and so are findings of a run that is not there.
        self.assertEqual([(run["review"], run["start"], run["seconds"], run["state"], run["repaired"], run["turns"]) for run in made["runs"]],
                         [("review-1", "2026-01-01T01:00:00Z", 1830, "accepted", False, 2), ("review-1", "2026-01-01T03:00:00Z", 600, "failed", True, 1),
                          ("review-2", "2026-01-01T04:00:00Z", None, "running", False, 1)])
        first, second, third = made["runs"]
        # A run adds up the tokens of its own turns: 1,000 and 3,000 for the first run.
        self.assertEqual((first["repository"], first["provider"], first["model"], first["effort"], first["inputTokens"], first["cachedInputTokens"], first["outputTokens"], first["cost"]),
                         ("trinodb/trino", "codex", "gpt-6-astra", "max", 4000, 2000, 20, None))
        self.assertEqual((second["model"], second["effort"], second["inputTokens"]), ("gpt-6-sol", "high", 3500))
        self.assertEqual((third["repository"], third["model"], third["effort"], third["inputTokens"], third["cost"]), ("o/n", "opus", "extra", 800, 2.5))
        self.assertEqual(made["findings"], [{"run": 0, "state": "resolved", "severity": "medium"}, {"run": 0, "state": "withdrawn", "severity": None},
                                            {"run": 0, "state": "open", "severity": "high"}, {"run": 2, "state": "open", "severity": None}])
        text = repr(made)
        for secret in ("hunter2", "secret-project", "someone", "password"):
            self.assertNotIn(secret, text)

    def test_review_runs_follow_the_choices_the_turn_table_is_saved_with(self):
        turns = records.read(reviewed(self.path))
        starts = lambda **choices: [run["start"][11:13] for run in records.reviews(self.path, turns, **choices)["runs"]]
        self.assertEqual((starts(), starts(since=records.moment("2026-01-01T03:00:00Z")), starts(task="bbbb-0002"), starts(task="another task")),
                         (["01", "03", "04"], ["03", "04"], ["04"], []))
        chosen = records.reviews(self.path, turns, task="bbbb-0002")
        self.assertEqual([finding["run"] for finding in chosen["findings"]], [0])  # a finding points at its run in the rows that were kept
        # A run whose turns the database no longer holds is still a run. It has no model, no tokens and no cost, and nothing is made up for it.
        bare = records.reviews(self.path, {})["runs"][0]
        self.assertEqual((bare["turns"], bare["model"], bare["inputTokens"], bare["cost"]), (0, None, None, None))

    def test_columns_the_lab_reads_exist_in_the_applications_tables(self):
        if not ENTITIES.is_dir():
            self.skipTest("the application's source is not beside the lab")
        for table, columns in records.COLUMNS.items():
            source = (ENTITIES / ("".join(word.title() for word in table.split("_")) + "Entity.java")).read_text()
            self.assertIn(f'@Table(name = "{table}"', source)
            # The columns of an embedded record are the entity's own, each a component of that record.
            source += "".join((ENTITIES / f"{kind}.java").read_text() for kind in re.findall(r"@Embedded\s+private (\w+) \w+;", source))
            for column in columns:
                field = re.sub(r"_(\w)", lambda found: found.group(1).upper(), column)
                with self.subTest(table=table, column=column):
                    self.assertRegex(source, rf"private [\w<>.]+ {field};|[\w<>.]+ {field}[,)]")


if __name__ == "__main__":
    unittest.main()
