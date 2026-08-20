# Branch Rebase Service Implementation Checklist

Status: implemented and verified; caller migrations remain separate work.

Canonical behavior is locked in [Branch Rebase Service](rebase-service.md).
This service is built and verified before any explicit UI or CI Autofix caller
is migrated to it.

## Fixed rules

- [x] `BranchRebaseService` is the only transition owner.
- [x] The caller owns one live agent task and its worktree lease.
- [x] Git owns the replay todo; ByteQuay stores no duplicate per-commit cursor.
- [x] The program alone runs rebase start, continue, skip, and abort.
- [x] Agent prose never decides a state.
- [x] No path fetches, pushes, or writes to GitHub.

## Checkpoint 1 — Durable state and persistence

- [x] Add the small `BranchRebase` aggregate, ID, action journal, and store
  contract in `core`.
- [x] Enforce status/step/turn/result invariants in the aggregate.
- [x] Add SQLite entity, repository, journal mapping, and optimistic store.
- [x] Test creation idempotency, optimistic save, and durable round trips.

Checkpoint gate:

- [x] Core and persistence tests pass.

## Checkpoint 2 — Rebase-aware Git driver

- [x] Reuse the existing sequence-state vocabulary without changing
  cherry-pick commands.
- [x] Observe `rebase-merge` or `rebase-apply` plus exact `REBASE_HEAD`.
- [x] Distinguish conflict, fully staged resolution, proven empty, completed,
  and unsafe partial/untracked state.
- [x] Guard start at the expected HEAD and run `--no-autosquash`.
- [x] Guard continue and suppress the editor explicitly.
- [x] Guard empty skip and abort-to-original-tip.
- [x] Test clean, conflict, resolved, empty, continue-to-next-conflict,
  no-autosquash, editor suppression, and abort with real Git repositories.

Checkpoint gate:

- [x] The real-Git classification and mutation matrix passes.

## Checkpoint 3 — Start, clean completion, and recovery

- [x] Validate the caller's live agent task, repository, worktree, and branch.
- [x] Resolve and freeze the local base SHA and original work tip.
- [x] Reject detached HEAD, dirty/open state, missing common history, same
  branch, remote-tracking input, and merge commits in the replay range.
- [x] Finish the descendant fast path without rewriting history.
- [x] Persist `START` before Git and recover both its before and after states.
- [x] Accept completion only on the exact work branch, clean, with the frozen
  base as an ancestor.
- [x] Call the explicit successful no-op validation hook once at final HEAD.
- [x] Test fast path, clean rebase, already-upstream work, invalid inputs, and
  crash recovery after Git completes but before the result save.

Checkpoint gate:

- [x] A clean rebase is idempotent and recoverable.

## Checkpoint 4 — Conflict turns and program-owned continuation

- [x] Persist exact `REBASE_HEAD`, expected HEAD, and attempt before starting a
  turn; read conflict paths from the still-open rebase for each prompt.
- [x] Build the narrow edit-and-stage-only prompt with a bounded user note.
- [x] Reuse the caller-owned agent task and a stable per-attempt turn key.
- [x] Retry the same stopped commit at most three times.
- [x] Accept an agent result only when HEAD and `REBASE_HEAD` did not move.
- [x] Journal and run guarded continue or proven-empty skip in the program.
- [x] Pause agent-run continue/skip/abort/commit and every unexpected state.
- [x] Test completed/failed turns, staged resolution, empty resolution,
  repeated conflict, retry exhaustion, and forbidden history movement.

Checkpoint gate:

- [x] Only the program can advance the rebase sequence.

## Checkpoint 5 — Pause, resume, cancel, and monitor

- [x] Resume a paused current conflict with one bounded untrusted user note.
- [x] Cancel an active turn before changing the rebase.
- [x] Journal abort, verify restoration to `originalWorkTip`, and recover an
  abort completed before its result save.
- [x] Pause instead of resetting any dirty, wrong-branch, or mismatched state.
- [x] Add a monitor that selects running records and delegates one bounded
  action to the service.
- [x] Test pause/resume, cancel, abort mismatch, monitor isolation, and restart.

Checkpoint gate:

- [x] Every external mutation boundary has a recovery test.

## Checkpoint 6 — Full verification

- [x] Run `mvn verify` in `backend/`.
- [x] Run `git diff --check`.
- [x] Reconcile every checked box with the implementation.
- [x] Confirm no explicit UI or CI Autofix caller changed in this checkpoint.

Final gate:

- [x] The service is safe to expose to a separately approved caller migration.
