CREATE INDEX idx_task_turns_status_created_id
    ON task_turns(status, created_at_ms, id);
