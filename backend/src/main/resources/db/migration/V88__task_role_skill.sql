-- Per-task role skill text. Frozen at task creation from the
-- task-role template + the task's repo / branch / base / id so the
-- system role block stays byte-stable for the lifetime of the task —
-- a stable prefix is what the provider cache needs to stay warm
-- across turns. Legacy rows leave the column null; the agent falls
-- back to no role block when null.
ALTER TABLE tasks ADD COLUMN role_skill TEXT;
