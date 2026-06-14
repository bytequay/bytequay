-- Per-task "accept edits in worktree" toggle. When on, file-edit tools
-- (Edit / Write / MultiEdit / NotebookEdit) whose target path is inside
-- the task's worktree are auto-approved by WorktreeEditStep — Bash, git
-- push, and any write outside the worktree still go through the normal
-- approval prompt, so the "nothing reaches GitHub without an explicit
-- action" invariant is preserved. Off by default.
ALTER TABLE tasks ADD COLUMN accept_edits INTEGER NOT NULL DEFAULT 0;
