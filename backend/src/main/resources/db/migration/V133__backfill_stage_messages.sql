-- Phase 6 of the stage-isolation work: consolidate STAGE-scoped transcripts out
-- of the shared thread_messages log into the per-stage stage_messages table, so
-- there is a single source for a stage's conversation and the thread log holds
-- only TRUNK/TASK rows (which auto-scopes the conversation index to those).
--
-- DESTRUCTIVE: copies the STAGE rows, then deletes them from thread_messages.
-- Safe by construction:
--   * Collision-safe — each backfilled row's per-stage seq is offset ABOVE any
--     seq already present for that stage (the agents may have written some
--     stage_messages already), so it can't violate UNIQUE(stage_id, seq). The
--     offset is taken from a MATERIALIZED snapshot so the running MAX can't
--     shift mid-INSERT. Ordering within a stage is by timestamp at read time,
--     so a non-contiguous seq is fine.
--   * FK-safe — only rows whose stage/task/thread parents still exist are
--     moved, and the DELETE matches that exact filter (orphan STAGE rows, whose
--     stage was already deleted, are left untouched rather than lost).
-- No-op on a fresh database (no STAGE rows yet), so it is inert under tests.

WITH base AS MATERIALIZED (
    SELECT stage_id, COALESCE(MAX(seq) + 1, 0) AS seq_offset
    FROM stage_messages
    GROUP BY stage_id
)
INSERT INTO stage_messages (
    id, stage_id, task_id, thread_id, seq, role, type, content_json,
    duration_ms, tokens_in, tokens_out, cost_usd_milli, ts_ms)
SELECT
    tm.id, tm.stage_id, tm.task_id, tm.thread_id,
    (ROW_NUMBER() OVER (PARTITION BY tm.stage_id ORDER BY tm.seq, tm.ts_ms) - 1)
        + COALESCE((SELECT b.seq_offset FROM base b WHERE b.stage_id = tm.stage_id), 0),
    tm.role, tm.type, tm.content_json,
    tm.duration_ms, tm.tokens_in, tm.tokens_out, tm.cost_usd_milli, tm.ts_ms
FROM thread_messages tm
WHERE tm.scope = 'STAGE'
  AND tm.stage_id IS NOT NULL
  AND tm.stage_id IN (SELECT id FROM task_stage)
  AND tm.task_id  IN (SELECT id FROM tasks)
  AND tm.thread_id IN (SELECT id FROM threads);

DELETE FROM thread_messages
WHERE scope = 'STAGE'
  AND stage_id IS NOT NULL
  AND stage_id IN (SELECT id FROM task_stage)
  AND task_id  IN (SELECT id FROM tasks)
  AND thread_id IN (SELECT id FROM threads);
