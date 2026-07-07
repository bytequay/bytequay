-- Per-stage override on the work-model cascade — the most-specific scope,
-- one rung above the task override. A stage's agent session is built once
-- and reused across every iteration within it, so this override is read
-- only when a fresh session is built for the stage; changing it mid-stage
-- takes effect the next time this stage key needs a new agent, not on the
-- currently running session.
--
-- Null means "no override has been set on this stage"; the resolver falls
-- back to the task, then thread, then workspace, then global default.
ALTER TABLE task_stage ADD COLUMN work_model_json TEXT NULL;
