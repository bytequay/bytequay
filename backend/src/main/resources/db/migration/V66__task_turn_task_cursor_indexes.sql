CREATE INDEX idx_task_turns_task_status_created_desc
    ON task_turns(task_id, status, created_at_ms DESC);

CREATE INDEX idx_task_turns_task_created_desc
    ON task_turns(task_id, created_at_ms DESC);
