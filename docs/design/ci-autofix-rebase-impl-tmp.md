# CI Autofix Program-owned Rebase Checklist

Status: implementation complete and verified on 2026-08-20.

Canonical behavior remains in [CI Autofix Service](ci-autofix-service.md) and
[Branch Rebase Service](rebase-service.md). This migration changes only who
synchronizes and rebases the PR branch before CI fix work.

## Fixed rules

- [x] The program fetches the exact GitHub-reported base SHA into a private
  local branch; the agent never synchronizes the target branch.
- [x] `BranchRebaseService` alone starts, continues, skips, and aborts that
  target rebase.
- [x] CI Autofix stores only `currentRebaseId`; Git still owns the replay todo.
- [x] The existing CI attempt, exact-lease push, CI monitor, and final fixup
  checks remain owned by CI Autofix.
- [x] The fix agent may still arrange fixup commits, but its prompt does not ask
  it to rebase onto the target.

## Checkpoint 1 — Exact base synchronization

- [x] Add a small `CiGitService` contract that synchronizes the exact base SHA
  into `bytequay/ci-base/<run-id>`.
- [x] Validate repository, work branch, base branch name, clean Git state, and
  the fetched object before updating the private ref.
- [x] Make synchronization idempotent and add guarded best-effort cleanup.
- [x] Test an already-present base, a fetched base, a mismatched/missing base,
  and cleanup.

## Checkpoint 2 — Durable CI/rebase link

- [x] Add optional `currentRebaseId` to `CiAutofixRun`, SQLite, and the local
  HTTP projection.
- [x] Forbid simultaneous CI fix and rebase links.
- [x] Add transitions to link, complete, pause with, and clear the rebase.
- [x] Render active linked work as `REBASING`.
- [x] Test domain invariants and SQLite round trips.

## Checkpoint 3 — Rebase gate

- [x] Gate the initial fix turn and every completed fix result on the exact
  current base SHA.
- [x] Start the branch rebase with the existing CI agent task and worktree.
- [x] Advance at most one rebase action per CI service call.
- [x] Keep a linked running rebase durable across restart.
- [x] Map a paused or cancelled rebase to a safely paused CI run.
- [x] Resume a paused linked rebase through the existing CI user-message API.
- [x] Cancel a linked rebase before finishing or cancelling CI Autofix.

## Checkpoint 4 — Attempts and prompts

- [x] Record an attempt's input SHA from the prepared local head after rebase.
- [x] Treat a program-rebased local head as publishable even when the fix agent
  creates no additional commit.
- [x] Remove target-rebase ownership from the CI fix prompt.
- [x] Preserve fixup placement, local validation, and no-GitHub-write rules.

## Checkpoint 5 — Tests and verification

- [x] Update focused service, Git, persistence, HTTP, prompt, and domain tests.
- [x] Update the target-moved end-to-end journey so only the program rebases.
- [x] Cover running, completed, paused, resumed, cancelled, and restart-safe
  rebase links.
- [x] Run `mvn verify` in `backend/`.
- [x] Run `git diff --check` and reconcile this checklist.

Final gate:

- [x] CI Autofix no longer asks an agent to synchronize or rebase the target.
