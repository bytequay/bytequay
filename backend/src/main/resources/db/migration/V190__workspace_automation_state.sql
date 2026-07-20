CREATE TABLE workspace_automation_state (
    workspace_id   TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    kind           TEXT NOT NULL,
    cursor         INTEGER,
    last_run_json  TEXT,
    updated_at_ms  INTEGER NOT NULL,
    PRIMARY KEY (workspace_id, kind)
);
