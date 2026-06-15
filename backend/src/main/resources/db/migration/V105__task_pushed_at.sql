-- Records the moment a task's branch first reached the remote. Set when
-- a `push` proposal is approved, and implicitly when an `open_pr`
-- approval pushes the branch before opening its (draft) PR. Null until
-- the branch is on origin — a distinct state from "committed locally"
-- that the task UI surfaces ("on remote") so a parked task no longer
-- looks stuck with no visible progress.
ALTER TABLE tasks ADD COLUMN pushed_at_ms BIGINT;
