-- TaskQueue: per-thread list of planned future tasks the trunk lines
-- up, plus the QUEUED-phase materialisation surface.
--
-- The queue lives on the thread as a JSON array (entries < 50
-- expected; atomic whole-array updates at the thread level are fine
-- for v1). SQLite has no JSONB type — TEXT holding the JSON array,
-- like every other *_json column in this schema. Shape of each entry:
--   { "position": int,                 -- 1-indexed; order matters
--     "title": string,
--     "branch_base": "main" | "stacked-on-previous",
--     "initial_prompt": string?,
--     "status": "PENDING" | "MATERIALIZED" | "COMPLETED" | "DROPPED",
--     "materialized_task_id": string?, -- task ids are TEXT here
--     "created_at_ms": int }
ALTER TABLE threads ADD COLUMN queue_json TEXT NOT NULL DEFAULT '[]';

-- Sequential v1: the scheduler respects only 1. The column exists now
-- so unlocking parallelism in v2 is a knob flip, not a re-migration.
ALTER TABLE threads ADD COLUMN parallel_slots INTEGER NOT NULL DEFAULT 1;

-- Opening-prompt accumulator on the task. While the task is in the
-- QUEUED phase the composer appends here; the agent reads it as its
-- first-turn input when the slot opens and the phase promotes to
-- IMPLEMENTING. Entity-managed (never mapped by saveTask) so a
-- full-row save can't clobber it, like phase / linked_pr_ref.
ALTER TABLE tasks ADD COLUMN opening_prompt TEXT;
