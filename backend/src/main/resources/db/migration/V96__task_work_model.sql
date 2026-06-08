-- Per-task override on the work-model cascade. The most-specific scope
-- on the cascade — workspace → thread → task → seat — and the one a
-- user pins from the task rail when they want this particular task to
-- run on a different model from the rest of the thread.
--
-- Null means "no override has been set on this task"; the resolver
-- falls back to the thread, then workspace, then global default.
ALTER TABLE tasks ADD COLUMN work_model_json TEXT NULL;
