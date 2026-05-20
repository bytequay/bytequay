# Task create — fresh worktree follow-up

Status: implemented.

Creating a coding task now attempts to create a dedicated git worktree and
local branch for that task. The agent runs from the worktree path when
creation succeeds, and falls back to the original repo checkout when the
working directory is not usable as a git repo.

## Landed behavior

- Schema: `tasks.worktree_path` and `tasks.local_branch`.
- Backend lifecycle: `TaskService.create` creates a worktree; task delete
  removes the worktree and local branch best-effort.
- Git layout: `<repo>/.bytequay/worktrees/dev/<session_id>-<slug>/`.
- Branch layout: `dev/<session_id>-<slug>`.
- Base ref: repo default branch first, current branch fallback.
- UI: create page shows "Spawned in a fresh worktree"; list/detail/zoom
  surfaces prefer `localBranch` and detail/terminal views show the actual
  agent cwd.

## Remaining polish

- Add copy/open actions for the worktree path and local branch.
- Add task actions for commit, push, and draft PR creation from the worktree.
- Add a disk cleanup view for stale or manually orphaned worktrees.
