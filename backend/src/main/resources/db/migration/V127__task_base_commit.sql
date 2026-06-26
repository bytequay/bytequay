-- The commit SHA a task's worktree was cut from, captured at cut time.
-- The task's cumulative diff is exactly base_commit..HEAD; recording it
-- removes the need to re-guess the base branch on every diff request
-- (which mis-resolved for fork tasks cut from upstream/master).
ALTER TABLE tasks ADD COLUMN base_commit TEXT;
