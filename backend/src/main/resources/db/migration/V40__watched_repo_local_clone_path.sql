-- Per-watched-repo local clone path. Optional — null = repo is
-- watched but not yet mapped to a local working copy. Populated by
-- the Repos tab's clone-fresh / locate-existing flow. The Repos
-- service derives a state pill (CLEAN / MODIFIED / UNMAPPED) by
-- combining this column with `git status --porcelain` against the
-- referenced directory.
ALTER TABLE watched_repos ADD COLUMN local_clone_path TEXT;
