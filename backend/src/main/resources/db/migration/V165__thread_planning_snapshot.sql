-- A trunk holds one published source snapshot stable while it researches and
-- plans. Creating a task consumes it; null means the next trunk turn refreshes.
ALTER TABLE threads ADD COLUMN planning_repo_root TEXT;
ALTER TABLE threads ADD COLUMN planning_base_sha TEXT;
