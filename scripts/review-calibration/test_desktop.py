from contextlib import closing, redirect_stderr
import io
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
from unittest import mock

import desktop
import app_logs
import saved
from test_activity import event, turn
from test_records import database as turn_database

FILES = ("turns.json", "reviews.json", "tuning.json", "context.json", "memory.json")


def database(path):
    turn_database(path)
    with closing(sqlite3.connect(path)) as connection, connection:
        connection.executescript("""
            ALTER TABLE agent_turn ADD COLUMN development_origin_kind;
            ALTER TABLE agent_turn ADD COLUMN cancellation_requested_at;
            ALTER TABLE agent_turn ADD COLUMN memories_json;
            ALTER TABLE agent_task ADD COLUMN state;
            ALTER TABLE agent_task ADD COLUMN interactive;
            ALTER TABLE agent_review_finding ADD COLUMN id;
            ALTER TABLE agent_review_finding ADD COLUMN memories_json;
            CREATE TABLE repository_memory (id, kind, delivered, cited, created_at);
        """)
    return path


class TestRefresh(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.folder = self.root / "Lab results"
        self.runtime = self.root / "task logs"
        self.database = database(self.root / "ByteQuay data.db")
        self.logs = self.root / "logs"

    def refresh(self):
        return desktop.refresh(self.folder, self.runtime, self.database, self.logs)

    def test_refresh_reads_local_records_without_writing_sources_or_starting_processes(self):
        log = self.runtime / "task-1" / "events.jsonl"
        log.parent.mkdir(parents=True)
        log.write_text("".join(json.dumps(row) + "\n" for row in [
            *turn(0, 10), event(5, "usage", inputTokens=100, cachedInputTokens=0, outputTokens=10, semantics="CUMULATIVE_FOR_INVOCATION")]))
        with closing(sqlite3.connect(self.database)) as connection, connection:
            connection.executescript("""
                INSERT INTO agent_task (id, lane_id, state) VALUES ('task-1', 'codex', 'COMPLETED');
                INSERT INTO repository_memory VALUES ('m1', 'RULE', 1, 0, '2026-01-01T00:00:00Z');
                INSERT INTO agent_turn (id, task_id, started_at, elapsed_millis, state, input_tokens, output_tokens, memories_json, lane_id)
                    VALUES ('t1', 'task-1', '2026-01-01T00:00:00Z', 10000, 'COMPLETED', 100, 10, '[{"id":"m1","line":"private title"}]', 'codex');
                INSERT INTO agent_review_run (id, created_at, state, turn_ids_json)
                    VALUES ('r1', '2026-01-01T00:00:00Z', 'COMPLETED', '["t1"]');
            """)
        before = {path: path.read_bytes() for path in (log, self.database)}
        with mock.patch("subprocess.run", side_effect=AssertionError("must not start a process")), \
                mock.patch("subprocess.Popen", side_effect=AssertionError("must not start a process")):
            self.refresh()
        self.assertEqual({path: path.read_bytes() for path in before}, before)
        self.assertEqual(sorted(path.name for path in self.folder.iterdir()), sorted((*FILES, app_logs.FILE)))
        self.assertEqual(len(saved.read(self.folder, "turns.json")["turns"]), 1)
        self.assertEqual(len(saved.read(self.folder, "reviews.json")["runs"]), 1)
        self.assertEqual(len(saved.read(self.folder, "tuning.json")["turns"]), 1)
        self.assertEqual(len(saved.read(self.folder, "context.json")["calls"]), 1)
        self.assertEqual(len(saved.read(self.folder, "memory.json")["memories"]), 1)
        self.assertNotIn("private title", "".join((self.folder / name).read_text() for name in FILES))

    def test_no_logs_and_empty_database_replace_earlier_statistics(self):
        for name in FILES:
            saved.write(self.folder, name, {"version": 1, "old": "must disappear"})
        saved.write(self.folder, "record.json", {"version": 1, "suggestions": ["keep"]})
        self.refresh()
        for name in FILES:
            self.assertNotIn("old", saved.read(self.folder, name))
        for name, field in zip(FILES, ("turns", "runs", "turns", "calls", "memories")):
            self.assertEqual(saved.read(self.folder, name)[field], [])
        self.assertEqual(saved.read(self.folder, "record.json")["suggestions"], ["keep"])
        self.assertFalse(self.runtime.exists())

    def test_failed_source_read_keeps_all_last_snapshots(self):
        self.refresh()
        before = {name: (self.folder / name).read_bytes() for name in FILES}
        with closing(sqlite3.connect(self.database)) as connection, connection:
            connection.execute("DROP TABLE repository_memory")
        result = self.refresh()
        self.assertIn("could not be refreshed", result["activityProblem"])
        self.assertEqual({name: (self.folder / name).read_bytes() for name in FILES}, before)

    def test_database_failure_hidden_by_a_collector_is_reported(self):
        with mock.patch.object(desktop.tuning, "table", return_value={"database": {"problem": "database locked"}}):
            result = self.refresh()
        self.assertIn("could not be refreshed", result["activityProblem"])
        self.assertEqual([path.name for path in self.folder.iterdir()], [app_logs.FILE])

    def test_command_reports_missing_database_and_never_creates_one(self):
        missing = self.root / "missing.db"
        output = io.StringIO()
        with mock.patch.object(sys, "argv", ["desktop.py", "--folder", str(self.folder), "--runtime", str(self.runtime), "--database", str(missing), "--logs", str(self.logs)]), \
                redirect_stderr(output):
            desktop.main()
        self.assertEqual(output.getvalue(), "")
        self.assertFalse(missing.exists())
        self.assertIsNotNone(saved.read(self.folder, app_logs.FILE)["activityProblem"])


if __name__ == "__main__":
    unittest.main()
