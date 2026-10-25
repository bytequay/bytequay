#!/usr/bin/env python3
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Compare task-list and pending-event SQL using isolated, in-memory SQLite databases.

Run: python3 scripts/agent-task-query-benchmark.py
Only synthetic rows are used. This measures SQL and row materialization, excluding
Hibernate/domain conversion, transcript reads, HTTP, and provider latency.
The relevant schema/index and queries mirror AgentTurnEntity,
AgentEventOutboxEntity, and their repositories; payload columns approximate JSON size.
"""

import argparse
import sqlite3
import statistics
import time


def positive(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError("must be positive")
    return number


def benchmark(task_count, turns_per_task, payload_bytes, repeats):
    with sqlite3.connect(":memory:") as connection:
        connection.executescript("""
            CREATE TABLE agent_task (id TEXT PRIMARY KEY);
            CREATE TABLE agent_turn (
                id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL REFERENCES agent_task(id),
                created_at INTEGER NOT NULL,
                idempotency_key TEXT NOT NULL,
                state TEXT NOT NULL,
                plan_json TEXT,
                UNIQUE (task_id, idempotency_key)
            );
            CREATE INDEX idx_agent_turn_task_created ON agent_turn(task_id, created_at, id);
        """)
        task_ids = [f"task-{index:06d}" for index in range(task_count)]
        # Include a task with no turns and an unrelated task to check result scope.
        connection.executemany("INSERT INTO agent_task VALUES (?)", [(task,) for task in [*task_ids, "empty", "unrelated"]])
        payload = "x" * payload_bytes
        connection.executemany("INSERT INTO agent_turn VALUES (?, ?, ?, ?, ?, ?)", (
            (f"{task}-turn-{turn:06d}", task, turn, str(turn), "COMPLETED", payload)
            for task in [*task_ids, "unrelated"] for turn in range(turns_per_task)
        ))
        requested = [*task_ids, "empty"]
        placeholders = ",".join("?" for _ in requested)
        latest_sql = f"""
            SELECT turn.* FROM agent_task task JOIN agent_turn turn ON turn.id = (
                SELECT latest.id FROM agent_turn latest WHERE latest.task_id = task.id
                ORDER BY latest.created_at DESC, latest.id DESC LIMIT 1)
            WHERE task.id IN ({placeholders})
        """

        def history():
            latest = {}
            for task in requested:
                rows = connection.execute("SELECT * FROM agent_turn WHERE task_id = ? ORDER BY created_at ASC", (task,)).fetchall()
                if rows:
                    latest[task] = max(rows, key=lambda row: row[2])
            return latest

        def batch():
            return {row[1]: row for row in connection.execute(latest_sql, requested).fetchall()}

        expected = history()
        assert batch() == expected
        assert len(expected) == task_count and "empty" not in expected and "unrelated" not in expected
        assert all(row[2] == turns_per_task - 1 for row in expected.values())
        counts = []
        for operation in (history, batch):
            queries = []
            connection.set_trace_callback(queries.append)
            operation()
            connection.set_trace_callback(None)
            counts.append(len(queries))
        assert counts == [task_count + 1, 1]

        for _ in range(3):
            history()
            batch()
        timings = {history: [], batch: []}
        for iteration in range(repeats):
            for operation in ((history, batch) if iteration % 2 == 0 else (batch, history)):
                started = time.perf_counter_ns()
                result = operation()
                timings[operation].append((time.perf_counter_ns() - started) / 1_000_000)
                assert result == expected
        old_ms, new_ms = (statistics.median(timings[operation]) for operation in (history, batch))
        print(f"{task_count:5d} {turns_per_task:10d} {task_count * turns_per_task:10d} -> {task_count:<6d} "
              f"{counts[0]:4d} -> {counts[1]:<4d} {old_ms:10.3f} {new_ms:10.3f} {old_ms / new_ms:8.1f}x")


def benchmark_outbox(entry_count, payload_bytes, repeats):
    with sqlite3.connect(":memory:") as connection:
        connection.execute("""
            CREATE TABLE agent_event_outbox (
                id INTEGER PRIMARY KEY,
                event_id TEXT NOT NULL UNIQUE,
                task_id TEXT NOT NULL,
                event_sequence INTEGER,
                payload_json TEXT NOT NULL
            )
        """)
        payload = "x" * payload_bytes
        connection.executemany("INSERT INTO agent_event_outbox VALUES (?, ?, ?, ?, ?)", (
            (index, f"event-{index}", f"task-{index % 100}", index + 1, payload) for index in range(entry_count)
        ))
        connection.executemany("INSERT INTO agent_event_outbox VALUES (?, ?, ?, NULL, ?)", (
            (entry_count + index, f"pending-{index}", task, payload)
            for index, task in enumerate(("task-1", "task-2", "task-1"))
        ))
        queries = [
            ("task pending", "SELECT * FROM agent_event_outbox WHERE task_id = ? AND event_sequence IS NULL ORDER BY id LIMIT 100",
             ("task-1",), [entry_count, entry_count + 2], "idx_agent_outbox_task_pending"),
            ("task empty", "SELECT * FROM agent_event_outbox WHERE task_id = ? AND event_sequence IS NULL ORDER BY id LIMIT 100",
             ("task-3",), [], "idx_agent_outbox_task_pending"),
            ("global pending", "SELECT * FROM agent_event_outbox WHERE event_sequence IS NULL ORDER BY id LIMIT 100",
             (), [entry_count, entry_count + 1, entry_count + 2], "idx_agent_outbox_pending"),
        ]

        def measure(sql, parameters, expected):
            samples = []
            for iteration in range(repeats + 3):
                started = time.perf_counter_ns()
                rows = connection.execute(sql, parameters).fetchall()
                elapsed = (time.perf_counter_ns() - started) / 1_000_000
                assert [row[0] for row in rows] == expected
                assert all(row[3] is None for row in rows)
                if iteration >= 3:
                    samples.append(elapsed)
            return statistics.median(samples)

        old = []
        for _, sql, parameters, expected, _ in queries:
            plan = connection.execute("EXPLAIN QUERY PLAN " + sql, parameters).fetchall()
            assert any("SCAN agent_event_outbox" in row[3] for row in plan)
            old.append(measure(sql, parameters, expected))
        connection.executescript("""
            CREATE INDEX idx_agent_outbox_task_pending ON agent_event_outbox(task_id, event_sequence, id);
            CREATE INDEX idx_agent_outbox_pending ON agent_event_outbox(event_sequence, id);
        """)
        print(f"\nOutbox: {entry_count} acknowledged + 3 pending rows; "
              f"median of {repeats} samples per query before/after index creation, 3 warmups each")
        print("Query              Scan ms Indexed ms  Speedup")
        for old_ms, (name, sql, parameters, expected, index) in zip(old, queries):
            plan = connection.execute("EXPLAIN QUERY PLAN " + sql, parameters).fetchall()
            assert any(index in row[3] for row in plan)
            assert not any("SCAN agent_event_outbox" in row[3] or "TEMP B-TREE" in row[3] for row in plan)
            new_ms = measure(sql, parameters, expected)
            print(f"{name:15s} {old_ms:10.3f} {new_ms:10.3f} {old_ms / new_ms:8.1f}x")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tasks", type=positive, default=100)
    parser.add_argument("--turns-per-task", type=positive, nargs="+", default=[10, 100, 1000])
    parser.add_argument("--payload-bytes", type=positive, default=1024)
    parser.add_argument("--repeats", type=positive, default=21)
    parser.add_argument("--outbox-entries", type=positive, default=100000)
    args = parser.parse_args()
    print(f"SQLite {sqlite3.sqlite_version}; synthetic in-memory DB; {args.payload_bytes} payload bytes/turn; "
          f"median of {args.repeats} alternating samples after 3 warmups")
    print("Tasks Turns/task       Rows old -> new     Queries old -> new  History ms   Batch ms  Speedup")
    for turn_count in args.turns_per_task:
        benchmark(args.tasks, turn_count, args.payload_bytes, args.repeats)
    benchmark_outbox(args.outbox_entries, args.payload_bytes, args.repeats)
