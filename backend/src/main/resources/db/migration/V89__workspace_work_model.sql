-- Adds a per-workspace work-model default — the agent + model + (for
-- API kinds) account each scope on the work-model cascade carries.
-- Stored as JSON because the shape evolves with the catalog and a
-- relational shape would split a single user-facing pick across
-- three or four tables for no payoff.
--
-- Null means "no override has been set" — the resolver falls back to
-- the global default in that case.
ALTER TABLE workspaces ADD COLUMN work_model_json TEXT NULL;
