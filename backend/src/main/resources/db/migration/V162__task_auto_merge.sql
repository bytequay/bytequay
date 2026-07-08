-- Per-task "auto-merge" mode (default off). On top of auto-approve, this also
-- approves the final merge_pr gate automatically. Only settable while the
-- task's plan reads low-risk/small-effort; not re-validated afterward.
ALTER TABLE tasks ADD COLUMN auto_merge INTEGER NOT NULL DEFAULT 0;
