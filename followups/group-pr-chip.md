# Group zoom-modal PR chip — wait for PR linking

The new tasks-group zoom modal mockup
(`docs/mockups/design/tasks/tasks-group-zoom.png`) shows a `⊜ PR
#5681` chip in the modal toolbar, next to the `⎇ branch` chip.

Today there is no `tasks.pr_id` column and no UI for linking a task
to a PR — both are captured in `followups/tasks-checkpoints-and-context.md`
under "Smaller bits the refactor left as TODOs".

## What we shipped now

The zoom modal toolbar renders the branch chip only. The PR chip is
omitted entirely (not stubbed with "+ Link PR") so the toolbar stays
clean until the underlying feature exists.

## Plan, when we pick this up

1. Add `pr_id` (nullable INTEGER) to `tasks` via Flyway.
2. Extend `TaskDto` with `linkedPrId: number | null` + the resolved
   `repoOwner/repoName` so the modal can render the chip text without
   a second fetch.
3. Wire a "+ Link PR" picker into the per-task header (search by repo,
   filter by author). Land first in the full detail page, then mirror
   into the zoom modal.
4. Once the chip can resolve to a real row, render `⊜ PR #N` in the
   modal toolbar (and the regular task header) — clicking it navigates
   to the PR detail screen.

Effort: ~30 min backend (column + DTO), ~1 hr frontend (picker UI),
trivial to surface in the modal once the data flows.
