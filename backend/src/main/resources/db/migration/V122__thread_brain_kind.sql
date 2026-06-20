-- Brain threads: a per-task read-only conversational agent thread.
--
-- A brain thread carries kind='BRAIN_AGENT' and parent_task_id = the dev
-- task it answers questions about. The partial unique index enforces one
-- brain thread per task at the schema level. parent_task_id is null for
-- every other (dev / review) thread.
--
-- No FK clause: SQLite's ALTER TABLE ADD COLUMN cannot carry a REFERENCES
-- clause (same constraint as V118's active_write_op_stage_id); the value
-- is always a valid task id set in code.
ALTER TABLE threads ADD COLUMN parent_task_id TEXT;

CREATE UNIQUE INDEX uq_thread_brain_per_task
    ON threads(parent_task_id)
    WHERE kind = 'BRAIN_AGENT';
