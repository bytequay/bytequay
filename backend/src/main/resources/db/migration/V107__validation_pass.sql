-- Audit log for ValidationPass runs (the VALIDATING phase's bundled
-- tests + checkstyle + repo-rule checks with a bounded auto-fix loop).
-- Cheap and distinct from the task row; one row per run.
CREATE TABLE validation_pass (
    id            INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    task_id       TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    started_at_ms INTEGER NOT NULL,
    ended_at_ms   INTEGER,                  -- null while in flight
    passed        INTEGER,                  -- null until finished; 0 | 1
    fix_rounds    INTEGER NOT NULL DEFAULT 0,
    failures_json TEXT                       -- JSON array of {source, detail}
);
CREATE INDEX validation_pass_task_idx ON validation_pass(task_id, started_at_ms);
