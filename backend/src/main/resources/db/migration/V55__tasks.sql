-- AI coding-task management surface. Stores both kinds of tasks under
-- a unified shape (see docs/mockups/tasks-design.md for the full
-- architecture):
--
--   * cli_agent  — wraps an external CLI such as `claude code`; each
--                  task has a live OS pid (process_pid) while running
--                  and an on-disk JSONL log (log_path) that's the
--                  canonical event source. SQLite mirrors it for
--                  cheap querying.
--   * logic_loop — runs an in-JVM agent loop calling a model API
--                  directly (planned for the in-house PR review
--                  loop). No subprocess, no on-disk JSONL: SQLite
--                  IS the canonical source for this kind, so
--                  process_pid and log_path are NULL.
--
-- Both kinds share the same tables, the same StreamEvent shapes, and
-- the same renderer; the `kind` column is what tells the spawn /
-- resume / stop paths which behaviour to apply.
--
-- Conventions match the rest of the ByteQuay schema:
--   * IDs are UUIDv4 stored as TEXT
--   * Timestamps are epoch milliseconds in INTEGER columns
--   * Cost is stored × 1000 (cost_usd_milli) so we don't fight
--     SQLite's lack of fixed-precision decimals; divide on read
--   * Enums (kind, status, role, type, operation) are stored as
--     uppercase TEXT matching the Java enum name

CREATE TABLE tasks (
    id                TEXT    PRIMARY KEY,
    kind              TEXT    NOT NULL,             -- 'CLI_AGENT' | 'LOGIC_LOOP'
    provider          TEXT    NOT NULL,             -- 'claude-code' | 'codex' | 'deepseek-review' | ...
    agent_session_id  TEXT,                         -- cli_agent: CLI's session id for --resume; logic_loop: NULL
    title             TEXT    NOT NULL,             -- first user prompt, truncated for the list view
    status            TEXT    NOT NULL,             -- 'PENDING' | 'RUNNING' | 'AWAITING' | 'IDLE' | 'COMPLETED' | 'ERRORED'
    working_dir       TEXT    NOT NULL,
    branch_name       TEXT,
    model             TEXT    NOT NULL,             -- e.g. 'claude-sonnet-4.6'
    cost_usd_milli    INTEGER NOT NULL DEFAULT 0,   -- USD × 1000
    tokens_in         INTEGER NOT NULL DEFAULT 0,
    tokens_out        INTEGER NOT NULL DEFAULT 0,
    process_pid       INTEGER,                      -- cli_agent only; NULL when paused/exited or for logic_loop
    log_path          TEXT,                         -- cli_agent only; absolute path to the CLI's JSONL log
    created_at_ms     INTEGER NOT NULL,
    updated_at_ms     INTEGER NOT NULL,
    ended_at_ms       INTEGER,
    error_message     TEXT,
    metadata_json     TEXT    NOT NULL DEFAULT '{}' -- JSON1; arbitrary back-references (e.g. originating PR)
);

-- Drives the left-rail status sections on the list page.
CREATE INDEX idx_tasks_status_updated ON tasks(status, updated_at_ms DESC);

-- Drives "all tasks for this repo" queries from the per-repo surfaces.
CREATE INDEX idx_tasks_working_dir ON tasks(working_dir);

CREATE TABLE task_messages (
    id              TEXT    PRIMARY KEY,
    task_id         TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    seq             INTEGER NOT NULL,             -- monotonically increasing within a task; lets us ORDER BY without timestamps
    role            TEXT    NOT NULL,             -- 'user' | 'assistant' | 'tool' | 'system'
    type            TEXT    NOT NULL,             -- 'text' | 'tool_call' | 'tool_result' | 'thinking' | 'permission_request' | 'error'
    content_json    TEXT    NOT NULL,             -- shape varies by type
    duration_ms     INTEGER,                      -- present for tool_call / tool_result rows
    tokens_in       INTEGER,
    tokens_out      INTEGER,
    cost_usd_milli  INTEGER,
    ts_ms           INTEGER NOT NULL,
    UNIQUE(task_id, seq)
);

-- The conversation pane reads in seq order; the same index serves
-- the "stage card" tail-of-stream query.
CREATE INDEX idx_task_messages_task_seq ON task_messages(task_id, seq);

CREATE TABLE task_files (
    task_id          TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    path             TEXT    NOT NULL,
    operation        TEXT    NOT NULL,            -- 'read' | 'write' | 'edit' | 'delete' — most recent op wins
    count            INTEGER NOT NULL DEFAULT 1,
    lines_added      INTEGER NOT NULL DEFAULT 0,
    lines_removed    INTEGER NOT NULL DEFAULT 0,
    last_touched_ms  INTEGER NOT NULL,
    PRIMARY KEY (task_id, path)
);
