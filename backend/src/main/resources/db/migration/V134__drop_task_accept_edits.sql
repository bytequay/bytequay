-- The per-task "accept edits in worktree" toggle is obsolete: in-worktree
-- file edits are now always auto-approved while a task is in one of the
-- autonomous work stages (Development, CI-fixing, Addressing-comments,
-- Cleanup), so the column no longer gates anything.
ALTER TABLE tasks DROP COLUMN accept_edits;
