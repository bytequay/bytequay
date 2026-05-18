CREATE TABLE task_turns (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL,
    lane TEXT NOT NULL,
    status TEXT NOT NULL,
    input TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    started_at_ms INTEGER,
    finished_at_ms INTEGER,
    error_message TEXT,
    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
);

CREATE INDEX idx_task_turns_status_created
    ON task_turns(status, created_at_ms);

CREATE INDEX idx_task_turns_task_id
    ON task_turns(task_id);
