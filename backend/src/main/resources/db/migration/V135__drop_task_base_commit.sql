-- The pinned cut-time base_commit (V127) is obsolete: a task's diff base is
-- now computed live as the merge-base of HEAD and the branch the worktree was
-- cut from, which tracks the real fork point even after a rebase. The pinned
-- SHA went stale on a rebase and over-counted commits, so the column is gone.
ALTER TABLE tasks DROP COLUMN base_commit;
