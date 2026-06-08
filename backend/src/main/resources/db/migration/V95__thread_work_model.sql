-- Per-thread override on the work-model cascade. Mirrors V89's column
-- on workspaces: stored as JSON because the shape evolves with the
-- catalog and a relational shape would split a single user-facing pick
-- across three or four tables for no payoff.
--
-- Null means "no override has been set on this thread" — the resolver
-- falls back to the workspace pick, then to the global default. The
-- cascade itself lives in the WorkModelResolver, not here.
ALTER TABLE threads ADD COLUMN work_model_json TEXT NULL;
