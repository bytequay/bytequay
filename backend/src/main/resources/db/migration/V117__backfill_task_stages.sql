-- Best-effort backfill so existing Tasks (created before the stages
-- schema) carry a stage row and queries never return a null stage
-- reference. Split from V116 so the schema change can stand on its own.
--
-- Timestamps for these historical rows are approximations: SQLite has no
-- per-stage history before now, so opened_at uses the task's created_at
-- and closed_at uses ended_at (falling back to created_at when a closed
-- task somehow has no end timestamp). No second-level precision is
-- promised — no historical metric depends on it. Stage events are NOT
-- backfilled; they only exist from this migration forward.
--
-- Ids are generated with the standard SQLite randomblob UUIDv4 expression
-- (no gen_random_uuid() here); randomblob()/random() evaluate per row, so
-- each synthesized row gets a distinct id. Both inserts are idempotent via
-- NOT EXISTS, so a re-run is a no-op.

-- One DevelopmentStage per existing Task — closed if the task is already
-- COMPLETED, otherwise open.
INSERT INTO task_stage (id, task_id, stage_type, state, opened_at_ms, closed_at_ms, summary_json, metrics_json)
SELECT
    lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4'
        || substr(hex(randomblob(2)), 2) || '-'
        || substr('89ab', abs(random()) % 4 + 1, 1)
        || substr(hex(randomblob(2)), 2) || '-' || hex(randomblob(6))),
    t.id,
    'DEVELOPMENT_STAGE',
    CASE WHEN t.phase = 'COMPLETED' THEN 'CLOSED' ELSE 'OPEN' END,
    t.created_at_ms,
    CASE WHEN t.phase = 'COMPLETED' THEN COALESCE(t.ended_at_ms, t.created_at_ms) ELSE NULL END,
    '{}',
    '{}'
FROM tasks t
WHERE NOT EXISTS (
    SELECT 1 FROM task_stage ts WHERE ts.task_id = t.id
);

-- COMPLETED Tasks also get a closed CleanupStage row.
INSERT INTO task_stage (id, task_id, stage_type, state, opened_at_ms, closed_at_ms, summary_json, metrics_json)
SELECT
    lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4'
        || substr(hex(randomblob(2)), 2) || '-'
        || substr('89ab', abs(random()) % 4 + 1, 1)
        || substr(hex(randomblob(2)), 2) || '-' || hex(randomblob(6))),
    t.id,
    'CLEANUP_STAGE',
    'CLOSED',
    COALESCE(t.ended_at_ms, t.created_at_ms),
    COALESCE(t.ended_at_ms, t.created_at_ms),
    '{}',
    '{}'
FROM tasks t
WHERE t.phase = 'COMPLETED'
  AND NOT EXISTS (
    SELECT 1 FROM task_stage ts WHERE ts.task_id = t.id AND ts.stage_type = 'CLEANUP_STAGE'
);
