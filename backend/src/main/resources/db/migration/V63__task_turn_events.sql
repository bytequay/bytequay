CREATE TABLE task_turn_events (
    id TEXT PRIMARY KEY,
    turn_id TEXT NOT NULL,
    task_id TEXT NOT NULL,
    event TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    message TEXT,
    FOREIGN KEY (turn_id) REFERENCES task_turns(id) ON DELETE CASCADE,
    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
);

CREATE INDEX idx_task_turn_events_task_created
    ON task_turn_events(task_id, created_at_ms DESC);

CREATE INDEX idx_task_turn_events_turn_created
    ON task_turn_events(turn_id, created_at_ms);
