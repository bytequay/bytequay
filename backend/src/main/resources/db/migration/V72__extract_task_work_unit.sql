-- Split the conversation row (threads) from the work-unit row (tasks).
-- A thread owns a continuous conversation; a task is a single unit of
-- work that owns a branch + worktree + PR + agent run. One thread can
-- have many tasks over its lifetime ("ship & continue" rolls one task
-- into the next), and a thread with zero tasks is now a first-class
-- state (brainstorming / Q&A with no branch).
--
-- This migration:
--   1. creates the new tasks + task_files tables,
--   2. backfills one task per existing thread (every existing row had
--      a working_dir, so each represents a coding session),
--   3. moves the file ledger from thread_files into task_files,
--   4. drops the execution columns from threads.
--
-- The conversation-level fields (agent_session_id, model, cost rollup,
-- title, status, group_id) stay on threads; the conversation persists
-- across task rollovers. The CLI's --resume id is one per thread, not
-- per task, so it lives here.

-- ── Create the work-unit table ───────────────────────────────────────
CREATE TABLE tasks (
    id                  TEXT    PRIMARY KEY,
    thread_id           TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    seq                 INTEGER NOT NULL,            -- 1, 2, 3... within the thread
    status              TEXT    NOT NULL,            -- PENDING|RUNNING|AWAITING|IDLE|COMPLETED|ERRORED

    -- Where the agent runs (per-task; absent for the future 0-Task case).
    branch_name         TEXT,                        -- e.g. dev/<task-id>-<slug>
    worktree_path       TEXT,                        -- <repo>/.worktrees/<task-id>
    base_branch         TEXT,                        -- 'main' / 'upstream/master' / the stacked-on branch
    working_dir         TEXT,                        -- repo root the worktree was cut from

    -- Per-execution state (transient — cleared when the agent stops).
    process_pid         INTEGER,                     -- CLI subprocess pid
    log_path            TEXT,                        -- CLI's JSONL log for THIS spawn

    -- GitHub linkage.
    pr_number           INTEGER,
    pr_state            TEXT,
    ci_state            TEXT,
    task_type           TEXT    NOT NULL DEFAULT 'DEVELOP',
    linked_pr_number    INTEGER,
    linked_issue_number INTEGER,

    -- Per-task spend (threads keep their own running rollup).
    cost_usd_milli      INTEGER NOT NULL DEFAULT 0,
    tokens_in           INTEGER NOT NULL DEFAULT 0,
    tokens_out          INTEGER NOT NULL DEFAULT 0,

    -- Inclusive thread_messages.seq range that this task covers, so the
    -- conversation can be sliced per task in the UI.
    first_msg_seq       INTEGER,
    last_msg_seq        INTEGER,

    created_at_ms       INTEGER NOT NULL,
    ended_at_ms         INTEGER,
    error_message       TEXT,

    UNIQUE (thread_id, seq)
);

CREATE INDEX idx_tasks_thread_seq ON tasks(thread_id, seq);
CREATE INDEX idx_tasks_status     ON tasks(status);
CREATE INDEX idx_tasks_pr_number  ON tasks(pr_number);

-- ── Create per-task file ledger ──────────────────────────────────────
CREATE TABLE task_files (
    task_id         TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    path            TEXT    NOT NULL,
    operation       TEXT    NOT NULL,                -- 'read' | 'write' | 'edit' | 'delete'
    count           INTEGER NOT NULL DEFAULT 1,
    lines_added     INTEGER NOT NULL DEFAULT 0,
    lines_removed   INTEGER NOT NULL DEFAULT 0,
    last_touched_ms INTEGER NOT NULL,
    PRIMARY KEY (task_id, path)
);

-- ── Backfill: every existing thread becomes thread + 1 task ──────────
-- Existing rows always have working_dir set (it was NOT NULL on the
-- original tasks table), so we conservatively treat every legacy row
-- as a coding session and give it a seq=1 task. The new task carries
-- the execution columns we are about to drop from threads.
INSERT INTO tasks (
    id, thread_id, seq, status,
    branch_name, worktree_path, base_branch, working_dir,
    process_pid, log_path,
    task_type, linked_pr_number, linked_issue_number,
    cost_usd_milli, tokens_in, tokens_out,
    first_msg_seq, last_msg_seq,
    created_at_ms, ended_at_ms, error_message
)
SELECT
    lower(hex(randomblob(16))),
    t.id,
    1,
    t.status,
    COALESCE(t.local_branch, t.branch_name),
    t.worktree_path,
    'main',
    t.working_dir,
    t.process_pid,
    t.log_path,
    t.task_type,
    t.linked_pr_number,
    t.linked_issue_number,
    t.cost_usd_milli,
    t.tokens_in,
    t.tokens_out,
    (SELECT MIN(seq) FROM thread_messages WHERE thread_id = t.id),
    (SELECT MAX(seq) FROM thread_messages WHERE thread_id = t.id),
    t.created_at_ms,
    t.ended_at_ms,
    t.error_message
FROM threads t;

-- ── Move the file rows: thread_files → task_files ────────────────────
INSERT INTO task_files (task_id, path, operation, count, lines_added, lines_removed, last_touched_ms)
SELECT k.id, f.path, f.operation, f.count, f.lines_added, f.lines_removed, f.last_touched_ms
FROM thread_files f
JOIN tasks k ON k.thread_id = f.thread_id;

DROP TABLE thread_files;

-- ── Drop indexes that block column drops ─────────────────────────────
-- SQLite's ALTER TABLE DROP COLUMN refuses to drop a column that any
-- index touches, so drop the working_dir index first.
DROP INDEX IF EXISTS idx_threads_working_dir;

-- ── Drop execution columns from threads ──────────────────────────────
ALTER TABLE threads DROP COLUMN branch_name;
ALTER TABLE threads DROP COLUMN local_branch;
ALTER TABLE threads DROP COLUMN worktree_path;
ALTER TABLE threads DROP COLUMN working_dir;
ALTER TABLE threads DROP COLUMN process_pid;
ALTER TABLE threads DROP COLUMN log_path;
ALTER TABLE threads DROP COLUMN task_type;
ALTER TABLE threads DROP COLUMN linked_pr_number;
ALTER TABLE threads DROP COLUMN linked_issue_number;
ALTER TABLE threads DROP COLUMN metadata_json;
-- threads now keeps: id, kind, provider, agent_session_id, title, status,
--                    model, cost_usd_milli (rollup), tokens_in, tokens_out,
--                    created_at_ms, updated_at_ms, ended_at_ms, error_message,
--                    group_id (legacy)
