# Task create — "spawned in a fresh worktree"

The new create page subtitle (`docs/mockups/design/tasks/task-create.png`)
says **"spawned in a fresh worktree"**. The intent is that creating
a task should `git worktree add` a fresh checkout instead of running
the AI session in the user's live tree — keeps the model from
stomping on uncommitted work and lets the user keep editing in their
editor while a task runs in the background.

## State today

`TaskService.create` writes the task with `workingDir = the tracked
repo's local clone path` and the agent runs directly in that tree.
There is no worktree machinery anywhere in the backend yet.

## What needs to land for the subtitle to be true

1. **Schema** — add `tasks.worktree_path TEXT` (nullable). When set,
   the agent session uses this path instead of the tracked repo's
   primary checkout.
2. **GitRunner / worktree helper** — `git worktree add <path> <ref>`
   on task create, then `git worktree remove <path>` (or `prune`) on
   task delete. Path lives under
   `~/Library/Application Support/ByteQuay/worktrees/<task-id>/`
   so it's outside the user's repo tree.
3. **Branch policy** — the new worktree probably checks out a fresh
   branch named after the task (e.g. `bytequay/<short-id>`) so the
   user can review the diff against `main` later.
4. **Cleanup on task delete** — `git worktree remove` and rmdir.
   Idempotent — a missing worktree dir is fine (the user may have
   pruned manually).
5. **UI** — once the backend writes `worktree_path`, the detail
   page's sidebar can show a "Working in: …/worktrees/abc1234"
   chip so the user knows it's not their primary tree.

## Open questions to nail down when this lands

- **Base ref** — does the worktree branch off `main`, the repo's
  default branch, or the user's current branch? Probably the default
  branch by default, with an "off [branch]" override on the create
  page.
- **Worktree per task or shared?** — one per task is cleaner but
  uses more disk. Probably one per task; revisit if disk pressure
  becomes a real complaint.
- **Diff viewer ergonomics** — the task's diff (against `main`) is
  what the user usually wants to review. The existing diff panel
  reads the working tree; that still works since the worktree IS
  the task's working tree. Just verify the commit-list view (since
  task start) makes sense in a freshly-branched worktree.

## Effort

~1 day backend (column + helper + lifecycle wiring), ~half-day
frontend (chip in the sidebar + worktree-path display on detail).

## Owner

Open — user flagged it during the 2026-05-18 create-page redesign.
For now the create page just doesn't render the worktree subtitle
("spawned in a fresh worktree") so we don't ship a lie.
