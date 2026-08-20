# Cherry-pick Range Implementation Checklist

Status: execution and progress UI complete; explicitly deferred work remains
below.

Canonical behavior is locked in
[Cherry-pick Range Design](cherry-pick-range.md). A box is checked only when
the code and focused tests exist. The final gate was checked only after the
full backend verification passed.

## Fixed rules

- [x] `CherryPickExecutionService` is the only owner of execution and item
  results; the current item is derived from the first `PENDING` result.
- [x] Presence detection uses ancestry or an exact standard `-x` line only.
- [x] One Git operation carries one source commit.
- [x] Only the program may run cherry-pick continue, skip, abort, and
  normalization.
- [x] A clean or conflict-resolved `APPLIED` item ends as exactly one final
  `-x` commit.
- [x] An `EMPTY_SKIPPED` item ends with no commit and no target SHA.
- [x] Agent prose and `range-diff` never decide an outcome.
- [x] No path pushes or writes to GitHub.

## Checkpoint 1 — Freeze and synchronize the plan

Goal: never execute a plan against a target the user did not review.

- [x] Fetch source and target for preview.
- [x] Freeze full source base, source tip, and target SHAs.
- [x] Validate user skips as exact eligible source SHAs.
- [x] Synchronize the target again immediately before start.
- [x] Reject a moved target as stale before creating a branch or worktree.
- [x] Create the generated work branch directly at the frozen target SHA.
- [x] Do not run a no-op pre-start rebase.
- [x] Test a target move after preview and prove no execution/worktree is
  created.

Checkpoint gate:

- [x] Focused preview/execution tests pass.

## Checkpoint 2 — Classify clean, empty, and conflict states

Goal: decide from repository facts, not command output.

- [x] Read HEAD, `CHERRY_PICK_HEAD`, other operation markers, unmerged index,
  staged diff, and tracked/untracked status.
- [x] Classify only `COMPLETED`, `STOPPED_CONFLICT`, `STOPPED_RESOLVED`,
  `STOPPED_EMPTY`, or `UNSAFE`.
- [x] Accept a clean apply only as one direct child with the exact `-x` line.
- [x] Guard empty skip with unchanged HEAD, exact source marker, no unmerged or
  staged data, and a clean worktree.
- [x] Verify empty skip leaves HEAD unchanged and the operation closed.
- [x] Test originally empty and target-redundant commits.
- [x] Test wrong expected HEAD, dirty worktree, and an unrelated open operation.

Checkpoint gate:

- [x] `TestGitSequencerDriver` covers the classification matrix.

## Checkpoint 3 — Persist conflict evidence and create `C`

Goal: preserve everything needed to resolve the conflict before closing it.

- [x] Persist conflict paths, types, and module hints.
- [x] Persist every `git ls-files -u` mode/blob/path for stages 1, 2, and 3
  when present.
- [x] Journal `COMMIT_CONFLICT_SNAPSHOT` with source SHA and `H0` before Git.
- [x] Recheck exact conflict state before mutation.
- [x] Stage only the recorded conflict paths with `git add -A -- <paths>`.
- [x] Let the program run `git cherry-pick --continue`.
- [x] Verify `C` is one direct child of `H0` with the exact `-x` source line.
- [x] Keep the item `PENDING` and `RESOLVING`.
- [x] Test that content conflicts store stages 1, 2, and 3.

Checkpoint gate:

- [x] Real-repository conflict snapshot tests pass.

## Checkpoint 4 — Agent creates one fixup `F`

Goal: give the agent one narrow, verifiable job.

- [x] Prompt with source SHA, `H0`, `C`, conflict facts, attempt, and user note.
- [x] Require `git commit --fixup=<full C SHA>`; permit `--allow-empty` only for
  that same fixup shape.
- [x] Ask the agent to run the project's existing local checks when possible.
- [x] Treat the final message as user-facing text only.
- [x] Disable automatic Bash approval for Claude cherry-pick tasks.
- [x] Allow sandboxed read/build/stage commands.
- [x] Deny cherry-pick continue/skip/abort, rebase, reset, ordinary commit,
  push, and other history mutations.
- [x] Verify the exact expected fixup SHA in the execution service, not in the
  permission broker.
- [x] Accept only one direct child with exact `fixup!` subject and no `-x` line.
- [x] Require a closed operation, clean worktree, and marker-free final tree.
- [x] Retry no-fixup results up to three turns.
- [x] Repair a structurally valid dirty or marker-bearing `F` by requiring
  `git commit --amend --no-edit`; reject a third commit.
- [x] Pause unexpected history.
- [x] Test successful fixup, no fixup/retry exhaustion, failed turn, invalid
  history guards, marker repair by amendment, and command permission rules.

Checkpoint gate:

- [x] Focused service and Claude permission tests pass.

## Checkpoint 5 — Validate and normalize before the next source

Goal: expose one final commit per applied source and no temporary history.

- [x] Call the private ByteQuay validation hook with `F`.
- [x] Keep that hook as an explicit successful no-op until a user-approved
  project validation profile exists.
- [x] If `tree(F)==tree(H0)`, move the generated branch to `H0`, record
  `EMPTY_SKIPPED`, and create no final commit.
- [x] Otherwise soft-reset to `H0` and commit with `-C C`.
- [x] Verify the normalized commit is one child of `H0`, preserves the `-x`
  message, and has exactly `tree(F)`.
- [x] Record only the normalized SHA as the item's `targetSha`.
- [x] Start the next source only after normalization.
- [x] Test nonempty squash and empty pair removal with real Git repositories.
- [x] Test the final execution contains one commit for a conflicted applied
  source, not `C/F`.

Checkpoint gate:

- [x] Focused driver and execution tests pass.

## Checkpoint 6 — Recovery and final accounting

Goal: repeat no uncertain mutation after a crash.

- [x] Recover an open conflict before `C`.
- [x] Adopt a valid `C` created before the result save.
- [x] Adopt a valid `F` created before the result save.
- [x] Resume normalization when HEAD is `F`.
- [x] Resume after the soft reset when HEAD is `H0` and the staged tree equals
  `tree(F)`.
- [x] Adopt a completed normalized commit with the expected parent/tree.
- [x] Adopt an already-completed empty removal.
- [x] Pause every mismatched parent, origin, subject, tree, or operation state.
- [x] Final accounting requires one ordered recorded `-x` commit per `APPLIED`
  item and none per `EMPTY_SKIPPED` item.
- [x] Keep `range-diff` bounded and human-only.
- [x] Test soft-reset recovery and completed-normalization adoption.
- [x] Test restart adoption for ordinary clean commits.
- [x] Test restart adoption after a guarded empty skip completed before its
  result save.
- [x] Simulate crashes after snapshot creation, fixup creation, normalization
  soft reset, normalization commit, and empty removal, then prove restart
  adopts the Git result without repeating the mutation.

Checkpoint gate:

- [x] Every durable mutation boundary has a service-level recovery test.

## Checkpoint 7 — Full backend verification

- [x] Run `mvn verify` in `backend/`.
- [x] Run `git diff --check`.
- [x] Confirm Checkstyle and all module tests pass.
- [x] Reconcile every checked box with the final implementation.
- [x] Review the final diff for accidental GitHub writes or changes outside the
  cherry-pick/permission/design boundary.

Final gate:

- [x] The locked design, code, persistence, API, and tests describe the same
  one-commit-or-empty result for every source item.

## Checkpoint 8 — Confirm and observe from the UI

Goal: let the user start and follow the durable execution without moving
transition ownership into the renderer.

- [x] Expose provider status and execution start/get/message/cancel through the
  preload bridge.
- [x] Start only after the user confirms the exact preview, skipped SHAs,
  worktree parent, and available conflict resolver.
- [x] Reuse one idempotency key when a start request is retried.
- [x] Poll only `RUNNING` executions and render backend-owned per-item results.
- [x] Restore the progress screen from the execution ID and local clone path.
- [x] Stop polling at `PAUSED`, show the backend explanation, and require a
  bounded user note for an explicit retry.
- [x] Make cancellation an explicit confirmed action while running or paused.
- [x] Show final accounting and allow the user to start another range.
- [x] Test progress counts and user-facing state labels as pure renderer logic.
- [x] Run the full frontend lint, type, test, and package gates.

Checkpoint gate:

- [x] The renderer observes durable state only and the packaged app builds.

## Deferred work

- [ ] User-selected project validation profiles.
- [ ] Program-owned Branch Rebase Service.
- [ ] CI Autofix migration to Branch Rebase Service.
- [ ] Push and pull-request creation for the generated work branch.
