-- Rename the legacy "task" tables to "thread" tables. The conversation
-- window is what the rest of the codebase will call a "Thread" from
-- here on; the word "task" is freed up for a later migration that
-- extracts a new, narrower meaning (a unit of work = branch + worktree
-- + PR). This migration is purely mechanical — no columns are added
-- or dropped, no rows move, no application behaviour changes.
--
-- Order matters: rename tables first, then rename FK columns, then
-- rebuild the indexes under their new names. SQLite carries indexes
-- across an `ALTER TABLE ... RENAME TO`, but it doesn't rename the
-- indexes themselves; doing it explicitly keeps the schema readable
-- and avoids surprises when a future DBA inspects sqlite_schema.

-- ── Tables ───────────────────────────────────────────────────────────
ALTER TABLE tasks               RENAME TO threads;
ALTER TABLE task_messages       RENAME TO thread_messages;
ALTER TABLE task_files          RENAME TO thread_files;
ALTER TABLE task_groups         RENAME TO thread_groups;
ALTER TABLE task_group_members  RENAME TO thread_group_members;
ALTER TABLE task_turns          RENAME TO thread_turns;
ALTER TABLE task_turn_events    RENAME TO thread_turn_events;
ALTER TABLE task_checkpoints    RENAME TO thread_checkpoints;

-- ── FK columns ───────────────────────────────────────────────────────
ALTER TABLE thread_messages       RENAME COLUMN task_id TO thread_id;
ALTER TABLE thread_files          RENAME COLUMN task_id TO thread_id;
ALTER TABLE thread_group_members  RENAME COLUMN task_id TO thread_id;
ALTER TABLE thread_turns          RENAME COLUMN task_id TO thread_id;
ALTER TABLE thread_turn_events    RENAME COLUMN task_id TO thread_id;
ALTER TABLE thread_checkpoints    RENAME COLUMN task_id TO thread_id;

-- ── Indexes ──────────────────────────────────────────────────────────
-- Drop every idx_task_* index and recreate with idx_thread_* names
-- pointed at the renamed tables/columns. The active set is what V55
-- through V69 left behind.
DROP INDEX IF EXISTS idx_tasks_status_updated;
DROP INDEX IF EXISTS idx_tasks_working_dir;
DROP INDEX IF EXISTS idx_tasks_group_id;
DROP INDEX IF EXISTS idx_task_messages_task_seq;
DROP INDEX IF EXISTS idx_task_group_members_group_id;
DROP INDEX IF EXISTS idx_task_group_members_task_id;
DROP INDEX IF EXISTS idx_task_turns_status_created_id;
DROP INDEX IF EXISTS idx_task_turns_task_status_created_id_desc;
DROP INDEX IF EXISTS idx_task_turns_task_created_id_desc;
DROP INDEX IF EXISTS idx_task_turn_events_turn_created;
DROP INDEX IF EXISTS idx_task_turn_events_task_created_id_desc;
DROP INDEX IF EXISTS idx_task_checkpoints_task_seq;
DROP INDEX IF EXISTS idx_task_checkpoints_task_active;

CREATE INDEX idx_threads_status_updated
    ON threads(status, updated_at_ms DESC);
CREATE INDEX idx_threads_working_dir
    ON threads(working_dir);
CREATE INDEX idx_threads_group_id
    ON threads(group_id);
CREATE INDEX idx_thread_messages_thread_seq
    ON thread_messages(thread_id, seq);
CREATE INDEX idx_thread_group_members_group_id
    ON thread_group_members(group_id);
CREATE INDEX idx_thread_group_members_thread_id
    ON thread_group_members(thread_id);
CREATE INDEX idx_thread_turns_status_created_id
    ON thread_turns(status, created_at_ms, id);
CREATE INDEX idx_thread_turns_thread_status_created_id_desc
    ON thread_turns(thread_id, status, created_at_ms DESC, id DESC);
CREATE INDEX idx_thread_turns_thread_created_id_desc
    ON thread_turns(thread_id, created_at_ms DESC, id DESC);
CREATE INDEX idx_thread_turn_events_turn_created
    ON thread_turn_events(turn_id, created_at_ms);
CREATE INDEX idx_thread_turn_events_thread_created_id_desc
    ON thread_turn_events(thread_id, created_at_ms DESC, id DESC);
CREATE INDEX idx_thread_checkpoints_thread_seq
    ON thread_checkpoints(thread_id, is_overall DESC, seq DESC);
CREATE INDEX idx_thread_checkpoints_thread_active
    ON thread_checkpoints(thread_id, superseded_at_ms);
