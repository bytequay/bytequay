-- Per-task git worktree linkage. When a coding task is created we
-- branch off the repo's default base and check it out into a
-- dedicated worktree under <repo>/.bytequay/worktrees/dev/<slug>/.
-- The agent runs in that worktree, so its commits and uncommitted
-- changes never collide with the user's main checkout or with other
-- parallel tasks against the same repo.
--
--   worktree_path  absolute path to the linked worktree directory
--                  (null for legacy tasks created before this column
--                  existed, or for non-coding tasks where worktree
--                  isolation doesn't apply)
--   local_branch   name of the branch created for this task
--                  (e.g. "dev/<sessionId>-<slug>"). Distinct from
--                  the existing branch_name column, which is sniffed
--                  from the user's main checkout at task-create time.

ALTER TABLE tasks ADD COLUMN worktree_path TEXT;
ALTER TABLE tasks ADD COLUMN local_branch TEXT;
