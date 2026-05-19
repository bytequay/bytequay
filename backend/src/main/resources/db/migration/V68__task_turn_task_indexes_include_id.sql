DROP INDEX IF EXISTS idx_task_turns_task_status_created_desc;
DROP INDEX IF EXISTS idx_task_turns_task_created_desc;

CREATE INDEX idx_task_turns_task_status_created_id_desc
    ON task_turns(task_id, status, created_at_ms DESC, id DESC);

CREATE INDEX idx_task_turns_task_created_id_desc
    ON task_turns(task_id, created_at_ms DESC, id DESC);
