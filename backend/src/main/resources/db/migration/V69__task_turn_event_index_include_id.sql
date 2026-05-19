DROP INDEX IF EXISTS idx_task_turn_events_task_created;

CREATE INDEX idx_task_turn_events_task_created_id_desc
    ON task_turn_events(task_id, created_at_ms DESC, id DESC);
