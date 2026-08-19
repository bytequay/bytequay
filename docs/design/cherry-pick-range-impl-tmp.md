# Cherry-pick Range Implementation Checklist

Status: temporary implementation tracker.

Canonical behavior is defined in [Cherry-pick Range](cherry-pick-range.md).
This file orders implementation work; it must not silently change that design.

## How to use this checklist

- Leave a box unchecked until its code, focused tests, and checkpoint
  verification are complete.
- Stop at every checkpoint gate for review before starting the next
  checkpoint.
- Keep each checkpoint buildable. Do not land half of a durable state change.
- If implementation requires a design change, update the canonical design
  first and record the decision in the checkpoint review.
- Use one logical commit per independently reviewable behavior. Mechanical
  moves must not be mixed with behavior changes.
- This tracker is temporary. Delete it or rename it to a completed
  implementation record after the final checkpoint.

## Fixed implementation rules

- [ ] Keep `CherryPickExecutionService` as the only owner of execution status,
  current step, item result, and pause reason. The cursor is derived, never
  stored.
- [ ] Keep execution status limited to `RUNNING`, `PAUSED`, and `FINISHED`.
- [ ] Keep the current step limited to `APPLYING`, `RESOLVING`, and
  `VALIDATING`.
- [ ] Keep item results limited to `PENDING`, `ALREADY_ON_TARGET`,
  `USER_SKIPPED`, `APPLIED`, and `EMPTY_SKIPPED`.
- [ ] Never use commit titles, pull-request titles, patch similarity, agent
  messages, or `range-diff` output to decide that a commit is already present.
- [ ] Automatically mark already-present only from ancestry or an exact
  standard `-x` source line.
- [ ] Treat patch equivalence from `git cherry` only as a warning.
- [ ] Preserve source commit order. Pull-request grouping changes display and
  skip selection only.
- [ ] Create at most one target commit for one source commit.
- [ ] Let the program, never the agent, run `git cherry-pick --continue` for
  conflict completion. Validation repair uses `git commit --amend --no-edit`.
- [ ] Let only the program skip an empty cherry-pick. The agent must not call
  `git cherry-pick --skip`.
- [ ] Do not push, comment, approve, merge, rerun GitHub Actions, or otherwise
  write to GitHub.
- [ ] Do not add a new Maven module, UI framework, state library, workflow
  framework, event stream, or database migration tool.

## Planned code locations

```text
backend/core/src/main/java/com/bytequay/core/cherrypick/
backend/core/src/test/java/com/bytequay/core/cherrypick/

backend/persistence-sqlite/src/main/java/com/bytequay/persistence/sqlite/
  entity/CherryPickExecutionEntity.java
  entity/CherryPickExecutionJson.java
  repository/CherryPickExecutionJpaRepository.java
  store/SqliteCherryPickExecutionStore.java
backend/persistence-sqlite/src/test/java/com/bytequay/persistence/sqlite/store/

backend/app/src/main/java/com/bytequay/app/cherrypick/
backend/app/src/main/java/com/bytequay/app/web/cherrypick/
backend/app/src/test/java/com/bytequay/app/cherrypick/
backend/app/src/test/java/com/bytequay/app/web/cherrypick/

frontend/src/cherrypick/
frontend/src/css/cherry-pick.css
```

Use records for immutable values, Guava immutable collections for stored
collections, `Optional` for absence, and the existing package conventions.

---

## Checkpoint 0 — Shared safety prerequisites

Goal: make protected local-agent calls and reusable process execution ready
without implementing cherry-pick behavior.

### Desktop session protection

- [x] Generate one cryptographically random desktop session token in the
  Electron main process for each app launch.
- [x] Pass the same token to the packaged backend as
  `BYTEQUAY_DESKTOP_SESSION_TOKEN`.
- [x] Update `dev.sh` so the Maven backend and Electron process inherit the
  same generated development token. Already true before this checkpoint;
  Electron now reuses the inherited value instead of minting a second one.
- [x] Keep the token in the Electron main process; do not expose it through
  `preload.ts` or to the renderer.
- [ ] Add `X-ByteQuay-Session` only inside Electron main-process requests to
  protected backend endpoints. Deferred to checkpoint 4: no main-process
  request reaches a protected endpoint yet, so the header would have no
  caller. `sessionToken()` in `backendProcess.ts` is the accessor it will use.
- [x] Extend `AgentRequestFilter` to protect
  `/api/cherry-pick-ranges`.
- [x] Add HTTP tests for missing, incorrect, correct, and browser-originated
  session requests.

### Reusable Git command runner

- [x] Move `CiGitCommandRunner` to a neutral concrete helper such as
  `com.bytequay.app.git.GitCommandRunner`.
- [x] Update CI Autofix to use the moved helper without changing behavior.
- [x] Keep argv execution, `GIT_TERMINAL_PROMPT=0`, `LC_ALL=C`, bounded
  combined output, timeout, and no shell.
- [x] Allow callers to inspect exit codes `0` and `1` without converting them
  into exceptions. Already true: `Result` carries the exit code and the
  runner never throws.
- [x] Keep one implementation; do not add a Git runner interface.
- [x] Move or update the existing runner tests and keep all CI Autofix tests
  passing.

### Agent permission and readiness gate

- [x] Add `CHERRY_PICK` to `AgentPermissionPolicy`, with a `sandboxed()`
  predicate so every non-`MANUAL` policy shares one isolation path.
- [x] Reuse the existing sandboxed-worktree settings currently used by
  `CI_AUTOFIX`, but do not expose the CI memory tool to cherry-pick tasks.
- [x] Deny network access, `git push`, `gh`, Git credential commands, and reads
  of credential or SSH files.
- [x] Allow edits only inside the leased worktree.
- [x] Advertise `CHERRY_PICK` only from a driver that can enforce those
  restrictions. Claude advertises it; Codex stays `MANUAL`-only.
- [x] Update driver, permission-broker, capability, and task-creation tests.
- [ ] Confirm the provider-readiness release gate from
  `agent-service.md` section 12 is implemented before enabling execution in
  the UI. Confirmed **not** implemented: `AgentDriver.probe()` exists but no
  readiness gate consumes it. Blocks checkpoint 8, not this checkpoint.

### Checkpoint 0 gate

- [x] `mvn verify` passes in `backend/`.
- [x] Existing CI Autofix behavior and tests are unchanged.
- [x] A renderer request cannot read the desktop session token.
- [x] An unsupported provider lane cannot create a `CHERRY_PICK` task.
- [ ] Reviewer approves the shared runner move and permission boundary.

---

## Checkpoint 1 — Durable execution model

Goal: persist the small state model and its invariants before any Git mutation
is implemented.

### Core values

- [ ] Add `CherryPickExecutionId` with strict string parsing and random
  creation.
- [ ] Add `CherryPickExecution` as the complete immutable aggregate.
- [ ] Store repository identity, source and target remote names, local target
  branch, generated work branch, generated worktree path, source base/tip SHA,
  initial/current target SHA, selected lane, agent task/turn IDs, status,
  current step, pause/finish reason, action journal, items, version, and
  timestamps. Do not store a cursor.
- [ ] Add `CherryPickItem` with position, source SHA, source parent SHA, commit
  and pull-request display metadata, confirmed disposition, result, optional
  target SHA, conflict information, validation information, and agent-turn
  count.
- [ ] Add enums for exactly the statuses, steps, and results in the canonical
  design.
- [ ] Add one small `CherryPickActionJournal` containing action type, source
  SHA, expected HEAD, attempt number, idempotency key, and start time.
- [ ] Put all legal transitions on `CherryPickExecution` methods; do not let
  controllers, entities, or stores construct invalid combinations.
- [ ] Enforce that a current step exists only while the status is `RUNNING`.
  Many items are `PENDING` at once, because one sequence covers the whole
  remaining queue; the cursor is computed as the lowest-position `PENDING`
  item and is never persisted.
- [ ] Enforce that `APPLIED` has a target SHA and `EMPTY_SKIPPED` does not.
- [ ] Enforce one-based, unique, increasing item positions and unique source
  SHAs.
- [ ] Defensively copy lists with `ImmutableList.copyOf`.

### Store port and errors

- [ ] Add `CherryPickExecutionStore` in `core` with `createOrGet`, `find`,
  `listRecent`, `listRunning`, and optimistic `save`.
- [ ] Add typed not-found, conflict, invalid-request, and storage exceptions.
- [ ] Limit start idempotency keys to 200 characters.
- [ ] Limit execution list requests to 1–200 rows.

### SQLite implementation

- [ ] Add one `cherry_pick_execution` entity; do not create a table per
  implementation phase.
- [ ] Store frequently queried scalar fields as columns.
- [ ] Store the immutable item list, action journal, bounded diagnostics,
  validation results, and final range review in JSON columns using the
  existing Jackson converter pattern.
- [ ] Add a unique constraint for the start idempotency key.
- [ ] Add `@Version` optimistic locking.
- [ ] Add repository queries for recent and running executions.
- [ ] Make `createOrGet` atomic when two requests use the same idempotency key.
- [ ] Map JPA and SQLite exceptions to core exceptions.
- [ ] Rely on Hibernate schema update; do not add Flyway.

### Tests

- [ ] Test every legal aggregate transition.
- [ ] Test the derived cursor over a partly applied plan, reordered items,
  missing target SHA, unexpected target SHA, and invalid status/step
  combinations.
- [ ] Test JSON round-trip for a 300-item execution.
- [ ] Test idempotent create, optimistic conflict, recent ordering, and running
  selection against a temporary SQLite database.

### Checkpoint 1 gate

- [ ] `mvn verify` passes.
- [ ] No Git command or agent turn can run yet.
- [ ] The persisted model contains only three statuses, three current steps,
  and five item results, and no stored cursor.
- [ ] Reviewer approves the aggregate invariants and SQLite shape.

---

## Checkpoint 2 — Local range preview and presence detection

Goal: produce a stable preview from local Git without mutating the selected
worktree.

### Preview input and cache

- [ ] Add a concrete `CherryPickPreviewService`; do not add an interface for
  its single implementation.
- [ ] Accept the repository clone, source remote, source base/tip refs, and
  target remote and branch. ByteQuay derives the local target branch and the
  work branch; the user names neither.
- [ ] Reject a shallow clone before anything else
  (`git rev-parse --is-shallow-repository`).
- [ ] Fetch the source remote in full, then synchronize the local target branch
  with `git fetch <targetRemote> <targetBranch>:<localTargetBranch>`.
- [ ] Verify the sync by comparing `rev-parse` of the local target branch and
  the remote-tracking ref, not by the fetch exit code: a rejected
  non-fast-forward still advances the remote-tracking ref.
- [ ] Refuse a diverged local target branch with an actionable diagnostic; never
  force-update it.
- [ ] Refuse when the local target branch is checked out anywhere, because Git
  cannot fetch into a checked-out branch.
- [ ] Make this the only step in the workflow that contacts the Git remote.
- [ ] Validate input length and reject NUL/newline characters.
- [ ] Pass refs as argv values, never through a shell.
- [ ] Resolve every ref to a full commit object ID before returning a preview.
- [ ] Require source base to be an ancestor of source tip.
- [ ] Reject merge commits in the source range with the first offending SHA.
- [ ] Keep previews in a bounded in-memory cache with a short fixed lifetime.
- [ ] Treat a backend restart or expired preview as “preview again”; do not
  persist unconfirmed previews.
- [ ] Cap cached previews and evict the oldest entry without adding a cache
  dependency.

### Ordered commit inventory

- [ ] Use `git rev-list --reverse --topo-order base..tip` for source order.
- [ ] Load full SHA, parent SHA, title, author/commit time, and changed paths
  without one process per field.
- [ ] Assign stable one-based positions.
- [ ] Compare each source tree with its parent and flag commits that are
  originally empty for display; keep their confirmed disposition
  `TO_CHERRY_PICK` so execution records `EMPTY_SKIPPED`.

### Already-on-target evidence

- [ ] Read target ancestors once and mark exact source SHAs found there as
  `ALREADY_ON_TARGET` with ancestry evidence.
- [ ] Scan only commits reachable from the resolved target SHA for exact
  standard lines of the form
  `(cherry picked from commit <full source SHA>)`.
- [ ] Require a full 40- or repository-object-format SHA and an exact complete
  line; reject abbreviated or prose matches.
- [ ] Record the target commit containing each accepted `-x` line.
- [ ] Never use a title match as evidence.
- [ ] Run `git cherry <targetSha> <sourceTipSha> <sourceBaseSha>` only for
  advisory patch-equivalence warnings.
- [ ] Ensure patch-equivalent results remain selectable
  `TO_CHERRY_PICK` items.

### Preview HTTP API

- [ ] Add `POST /api/cherry-pick-ranges/previews`.
- [ ] Add `GET /api/cherry-pick-ranges/previews/{previewId}`.
- [ ] Return resolved boundary SHAs, target SHA, ordered commit fields,
  presence evidence, warnings, and preview expiry.
- [ ] Return typed 400, 404, 409, and 422 errors through the existing global
  error mapping style.
- [ ] Confirm both endpoints are protected by the desktop session filter.

### Tests

- [ ] Test stable order with at least ten commits.
- [ ] Test direct ancestry, exact `-x`, abbreviated marker, marker-like prose,
  duplicate titles, and patch-equivalent warning behavior.
- [ ] Test originally empty source commits.
- [ ] Test merge-commit rejection, invalid refs, non-ancestor range, cache
  expiry, and cache capacity.
- [ ] Assert the selected worktree HEAD, index, and files are unchanged by
  every preview test.

### Checkpoint 2 gate

- [ ] `mvn verify` passes.
- [ ] Preview works without GitHub metadata.
- [ ] The selected worktree is never mutated.
- [ ] Only ancestry and exact `-x` evidence produce automatic skips.
- [ ] Reviewer approves preview ordering and evidence output.

---

## Checkpoint 3 — Pull-request grouping and conflict scope

Goal: enrich the preview for selection without letting metadata control
execution.

### Pull-request association

- [ ] Add a read-only GitHub client for
  `GET /repos/{owner}/{repo}/commits/{sha}/pulls` using the existing
  credential and REST support.
- [ ] Request commit associations with bounded parallelism; do not launch 300
  simultaneous requests.
- [ ] Cache associations by repository and source SHA for the preview
  lifetime.
- [ ] Map PR number, title, URL, base ref, and merged time.
- [ ] Prefer one merged same-repository PR whose base ref matches the named
  source branch when that match is available.
- [ ] If the remaining association is not unique, leave the commit ungrouped
  and add an ambiguity warning instead of guessing.
- [ ] Treat GitHub metadata failure as a warning; keep local commits visible
  and selectable.
- [ ] Group commits by the chosen PR number.
- [ ] Order PR groups by their earliest source commit position.
- [ ] Keep commit position as the cherry-pick order; use `mergedAt` only for
  display.
- [ ] Support commits with no PR association.

### Conflict preview

- [ ] Create one temporary detached Git worktree at the resolved target SHA
  under the system temporary directory.
- [ ] Never use the selected execution worktree for simulation.
- [ ] Apply non-present source commits in position order inside the temporary
  worktree.
- [ ] Use temporary local Git identity settings only inside each preview
  command.
- [ ] On clean simulation, keep the temporary commit so the next source commit
  sees earlier simulated changes.
- [ ] At the first conflict, record unmerged paths and stop exact sequential
  simulation.
- [ ] Read unmerged stages with `git ls-files -u`.
- [ ] Classify content, add/add, modify/delete, rename, binary, and submodule
  conflicts when Git supplies enough facts.
- [ ] Derive textual sections and line ranges from conflict/diff output when
  available; do not invent line numbers.
- [ ] Map a path to the nearest ancestor Maven `pom.xml` and expose the module
  path.
- [ ] Mark later conflict information as estimated or `NOT_ANALYZED` after the
  first unresolved conflict.
- [ ] Always abort any open operation and remove only the exact temporary
  worktree in a `finally` path.
- [ ] Bound diagnostics and changed/conflict path counts.

### Tests

- [ ] Test rebase-merged, squash-merged, ungrouped, ambiguous, and unavailable
  PR metadata.
- [ ] Test group ordering by earliest commit position rather than merged time.
- [ ] Test group membership never changes source order.
- [ ] Test clean sequential simulation and first-conflict stopping.
- [ ] Test content, add/add, modify/delete, binary/submodule fallback, module
  mapping, and unavailable line ranges.
- [ ] Test cleanup after success, conflict, timeout, and forced exception.
- [ ] Assert preview makes no GitHub write.

### Checkpoint 3 gate

- [ ] `mvn verify` passes.
- [ ] A 300-commit fixture returns bounded output and bounded GitHub
  concurrency.
- [ ] Metadata failure does not block local preview.
- [ ] Temporary worktree cleanup is proven.
- [ ] Reviewer approves PR grouping and conflict-confidence labels.

---

## Checkpoint 4 — Preview and skip-selection UI

Goal: let the user understand and confirm the exact source SHAs to skip.

### Electron bridge

- [ ] Add typed preview/start/get/list/message/cancel DTOs in
  `frontend/src/types.ts`.
- [ ] Add IPC handlers in `frontend/src/main.ts` that call protected
  cherry-pick endpoints and add the desktop session header.
- [ ] Add the smallest necessary bridge methods in `preload.ts` and `Bridge`.
- [ ] Add a native directory picker in Electron main for selecting the
  repository clone and the parent directory that will hold the generated
  worktree; return only the selected paths to the renderer. The user never
  selects or names the worktree itself.
- [ ] Keep backend URLs and the session token out of renderer code.

### Preview screen

- [ ] Add a `Cherry-pick` sidebar destination and one
  `CherryPickRangeScreen`.
- [ ] Add repository, source remote, source base/tip, and target
  remote/branch inputs. Show the generated work branch name once it exists;
  never let the user type it.
- [ ] Show resolved SHAs after preview so the user can verify the frozen
  boundaries.
- [ ] Render PR groups in earliest-commit-position order.
- [ ] Render commits inside each group in source position order.
- [ ] Use an explicit `Skip` checkbox, unchecked by default.
- [ ] Disable skip controls for `ALREADY_ON_TARGET` items and show their exact
  evidence.
- [ ] Add a tri-state PR checkbox that selects only that PR’s eligible source
  SHAs.
- [ ] Keep ungrouped commits individually selectable.
- [ ] Search by PR number, PR title, commit SHA prefix, and commit title.
- [ ] Ensure filtering does not clear selections or alter commit order.
- [ ] Show commit SHA, title, PR number/title, merged time, position,
  already-on-target evidence, and conflict summary.
- [ ] Allow conflict paths/modules/sections to expand without changing
  selection.
- [ ] Show counts for total, already present, user skipped, and to cherry-pick.
- [ ] Show warnings for patch similarity, ambiguous/missing PRs, and
  not-analyzed conflict scope.

### Confirmation

- [ ] Build confirmation from explicit `userSkippedSourceShas[]`, not group
  checkbox state.
- [ ] Send preview ID, expected source base/tip SHA, expected target SHA,
  selected SHAs, lane, and idempotency key.
- [ ] Show a final confirmation dialog with exact counts and target SHA before
  local mutation.
- [ ] Disable duplicate submission while start is pending.
- [ ] On stale preview, return to preview with selections preserved when the
  same source SHAs still exist.

### Frontend tests

- [ ] Test tri-state group selection.
- [ ] Test mixed already-present and selectable commits in one PR.
- [ ] Test search without selection loss.
- [ ] Test explicit SHA payload order and deduplication.
- [ ] Test stale-preview and backend-error display.
- [ ] Test keyboard labels and checkbox accessibility.

### Checkpoint 4 gate

- [ ] `npm run lint` passes.
- [ ] `npx tsc --noEmit` passes.
- [ ] `npm test` passes.
- [ ] Preview and selection cause no local Git mutation.
- [ ] Reviewer approves the exact confirmation payload and checkbox behavior.

---

## Checkpoint 5 — Clean execution and empty skipping

Goal: execute confirmed commits without an agent when no conflict occurs.

### Start and confirmation

- [ ] Implement `CherryPickExecutionService` and its concrete default service.
- [ ] Re-read the cached preview and reject an unknown or expired preview.
- [ ] Validate every user-skipped SHA belongs to that preview and is eligible.
- [ ] Re-resolve source base/tip and the local target branch and require the
  expected SHAs.
- [ ] Reject another live execution for the same repository and target branch.
- [ ] Generate the work branch name `bump-{from}-{to}-{2 characters from
  0-9a-zA-Z}` and retry with new characters when the branch or path exists.
- [ ] Create branch and worktree in one command at the **frozen** target object
  ID: `git worktree add -b <workBranch> <path> <frozenTargetSha>`.
- [ ] Never check out the local target branch, so later synchronization stays
  possible.
- [ ] Lease the generated worktree through the existing agent workspace
  service.
- [ ] Require `git symbolic-ref --quiet HEAD` to name the work branch.
  A detached HEAD passes a SHA check but keeps every created commit off the
  branch, so reject it instead of finishing a range that moved nothing.
- [ ] Require a clean index/worktree and no cherry-pick, merge, or rebase.
- [ ] Add the shared `GitSequencerDriver` beside `GitCommandRunner` in
  `com.bytequay.app.git`, with `begin`, `observe`, `continueSequence`,
  `skipEmpty`, and `abort`. Concrete and package-private; no interface.
- [ ] Move the worktree-state check out of `CiGitOperations` into the driver
  (`ls-files -u`, the `MERGE_HEAD`/`CHERRY_PICK_HEAD`/`rebase-merge`/
  `rebase-apply`/`sequencer` markers, and `status --porcelain`) rather than
  duplicating it a third time.
- [ ] Return `SequenceState` as `COMPLETED`, `STOPPED_CONFLICT`,
  `STOPPED_RESOLVED`, `STOPPED_EMPTY`, or `UNSAFE`, carrying observed HEAD,
  stopped commit, remaining todo entries, and conflict paths/types.
- [ ] Make every mutating driver call take the HEAD it expects to start from,
  and refuse `begin` when an operation is already open.
- [ ] Create the durable execution before the first cherry-pick.
- [ ] Create one agent task with `CHERRY_PICK` permission while the worktree is
  still clean, even if the clean path may never use a turn.
- [ ] Store the task ID before allowing monitor advancement.
- [ ] Make repeated start idempotency keys return the same execution.

### One-action monitor

- [ ] Add `CherryPickExecutionMonitorJob` using the existing scheduled-job
  pattern.
- [ ] Use an `AtomicBoolean` to prevent overlapping batches.
- [ ] Use a small in-process striped lock per execution and optimistic store
  versions for cross-call/restart safety.
- [ ] Make one `advance(...)` call perform at most one Git action or one state
  transition.
- [ ] Never hold a SQLite transaction while Git runs.

### Concrete Git helper

- [ ] Validate canonical worktree, expected HEAD, clean state, exact source
  commit, and absence of another Git operation before mutation.
- [ ] Begin one sequence with every `TO_CHERRY_PICK` source SHA in plan order:
  `git cherry-pick -x <sha> <sha> ...`. Confirmed skips never enter the list.
- [ ] Never trust the exit code; observe Git after every action.
- [ ] Reconcile a batch: read every commit between the stored expected HEAD and
  current HEAD oldest-first, require an exact `-x` line on each, require those
  source SHAs to equal the still-pending plan items in order with no extras, and
  record each `APPLIED` with its created target SHA.
- [ ] Require a clean worktree and no leftover conflict marker over the whole
  created range, using `git diff --check` with repository whitespace rules
  disabled.
- [ ] Classify `STOPPED_CONFLICT` only when `CHERRY_PICK_HEAD` names the stopped
  commit and unmerged or staged resolution work exists.
- [ ] Classify `STOPPED_EMPTY` only when HEAD is unchanged since the stop,
  `CHERRY_PICK_HEAD` names the stopped commit, there are no unmerged entries,
  `git diff --cached --quiet HEAD` succeeds, and the worktree has no tracked or
  untracked changes. This covers both a source commit that was empty upstream
  and one the target already contains: Git stops on them identically, so do not
  add a tree comparison that bypasses the sequencer.
- [ ] Treat every other combination as `UNSAFE`.

### Empty skip

- [ ] Store an empty-skip action journal before mutation.
- [ ] Recheck expected HEAD, exact `CHERRY_PICK_HEAD`, no unmerged entries,
  empty staged diff, and clean worktree.
- [ ] Run `git cherry-pick --skip`.
- [ ] Verify HEAD is unchanged, `CHERRY_PICK_HEAD` is absent, no Git operation
  remains, and the worktree is clean.
- [ ] Only then record `EMPTY_SKIPPED` and let the sequence resume.
- [ ] Pause on a rejected or uncertain skip; never retry blindly.

### Clean completion

- [ ] Record every `APPLIED` item reconciled from one batch in a single
  optimistic save; a batch may complete many items at once.
- [ ] Derive the cursor as the lowest-position `PENDING` item; do not store a
  second position beside Git's todo file.
- [ ] Skip confirmed `ALREADY_ON_TARGET` and `USER_SKIPPED` items without a
  Git command.
- [ ] Preserve all original item positions and display metadata.

### API and tests

- [ ] Add execution start/list/get/cancel endpoints and small DTOs from the
  canonical design.
- [ ] Test clean apply, direct plan skips, originally empty source, and
  target-redundant empty source in real temporary repositories.
- [ ] Test a batch that records many `APPLIED` items in one advance, asserting
  each recorded target SHA matches its `-x` line one for one.
- [ ] Test a batch that stops on an empty commit mid-run and resumes to
  completion after the guarded skip.
- [ ] Test wrong HEAD, dirty worktree, missing `-x`, extra commit, wrong
  parent, abbreviated source, open unrelated operation, and duplicate monitor
  calls.
- [ ] Test crash after the action journal and crash after Git completes but
  before the result save.
- [ ] Assert every clean-path test makes no GitHub request.

### Checkpoint 5 gate

- [ ] `mvn verify` passes.
- [ ] A clean multi-commit range finishes with exact item results and order.
- [ ] Empty commits continue automatically without a user pause.
- [ ] No agent turn starts on the clean path.
- [ ] Reviewer approves the Git classification matrix and empty-skip guard.

---

## Checkpoint 6 — Agent conflict resolution and validation

Goal: resolve only the current conflicted commit and accept it from program
facts.

### Conflict facts and prompt

- [ ] Persist conflict paths, stage-derived conflict types, module mapping,
  bounded diagnostics, and agent-turn count on the current item.
- [ ] Add one package-private prompt builder using the canonical prompt shape.
- [ ] Include full source SHA, expected target parent, current step, conflict
  paths/types, exact validation argv, latest program diagnostic, and attempt
  number.
- [ ] State that the agent stages resolutions and stops; the program runs
  `git cherry-pick --continue`. Validation repair uses
  `git commit --amend --no-edit`.
- [ ] State that an empty result must be left open for the program.
- [ ] Forbid skip, abort, fixup commit, next cherry-pick, rebase, push, `gh`,
  and GitHub writes.
- [ ] Treat the final agent response as display-only.

### Turn lifecycle

- [ ] Store `RESOLVING` and the turn-start intent before calling
  `AgentTurnService.start(...)`.
- [ ] Derive a stable turn idempotency key from execution ID, item position,
  and attempt number.
- [ ] Store the returned turn ID on the execution.
- [ ] While a turn is accepted/running, return without another action.
- [ ] Inspect Git only after a `COMPLETED` turn.
- [ ] Pause for `FAILED`, `CANCELLED`, or `INTERRUPTED` turns.
- [ ] If the same safe conflict remains, start another turn with actual
  diagnostics.
- [ ] Limit automatic turns to three per source item.
- [ ] Route a conflict resolution that becomes empty through the same guarded
  empty-skip logic from checkpoint 5.

### Validation planning

- [ ] Keep validation planning and running as concrete package-private
  collaborators; do not create `CherryPickValidationService`.
- [ ] Build the authoritative changed-path set from conflict paths plus the
  verified target commit diff.
- [ ] Find the nearest ancestor `pom.xml` for each changed path.
- [ ] If all paths map to Maven modules and a checked-in `mvnw` exists, plan:
  `./mvnw -pl <comma-separated-modules> -am -DskipTests package`.
- [ ] If a root-level change cannot be isolated, plan:
  `./mvnw -DskipTests package`.
- [ ] Store canonical working directory, argv list, fixed timeout, and output
  limit before execution.
- [ ] Reuse `BoundedProcessRunner` with an argv list and no shell.
- [ ] Use a fixed 30-minute timeout and bounded output for the first
  implementation.
- [ ] Pause `VALIDATION_UNAVAILABLE` when no checked-in Maven wrapper or safe
  module/project command can be formed.
- [ ] Do not accept agent claims that compilation passed.

### Validation and repair

- [ ] Set `VALIDATING` only after one structurally correct target commit
  exists.
- [ ] Tie every validation result to the exact inspected target SHA.
- [ ] On failure, start a repair turn with bounded compiler diagnostics.
- [ ] Require repair to amend the same commit with
  `git commit --amend --no-edit`.
- [ ] After an amend, rerun the full stored validation plan.
- [ ] After validation passes, inspect Git again and require the same target
  SHA, expected parent, exact `-x` line, clean state, and the marker-only
  `diff --check`.
- [ ] Record `APPLIED` only after that final inspection.

### Tests

- [ ] Add a scripted agent driver and temporary-repository harness based on
  the existing CI Autofix end-to-end fixtures.
- [ ] Test ordinary text conflict, conflict left open, conflict retry, and
  retry exhaustion.
- [ ] Test agent-created extra commit, wrong parent, missing `-x`, skip,
  abort, rebase, dirty files, and leftover conflict markers.
- [ ] Test validation pass, validation failure followed by amend, validation
  timeout, validation unavailable, and amend that changes another module.
- [ ] Test conflict resolution becoming empty with unchanged HEAD and no extra
  agent turn.
- [ ] Assert one source commit creates at most one target commit.
- [ ] Assert no test path writes to GitHub.

### Checkpoint 6 gate

- [ ] `mvn verify` passes.
- [ ] Conflict resolution cannot advance from agent prose.
- [ ] Validation is independently rerun by ByteQuay.
- [ ] Repair amends rather than adding a fixup commit.
- [ ] Reviewer approves the prompt, permission policy, and validation command.

---

## Checkpoint 7 — Recovery, pause, cancellation, and final review

Goal: make every crash window and terminal outcome explainable from durable
state and Git.

### Restart reconciliation

- [ ] Reconcile every running execution on backend startup or its first
  monitor call.
- [ ] If an intent exists, inspect Git before repeating any action.
- [ ] Recognize an applied candidate created before a crash.
- [ ] Recognize an exact still-open conflict.
- [ ] Recognize an exact still-open empty operation.
- [ ] Recognize an already-completed empty skip when HEAD is unchanged, the
  worktree is clean, and the operation marker is gone.
- [ ] Recognize a completed validation result only for its stored target SHA.
- [ ] Pause when persisted state and Git facts disagree.
- [ ] Restore the existing agent task worktree lease before resuming a turn.
- [ ] Treat active turns lost during restart as interrupted and pause.

### User recovery and cancellation

- [ ] Add `POST .../{executionId}/messages` with an idempotency key.
- [ ] Accept a message only for the current paused `PENDING` item.
- [ ] Reinspect Git before starting the recovery turn.
- [ ] Wrap the user text as an untrusted note inside the fixed prompt.
- [ ] Do not let the message change the plan, target, lane, permissions, order,
  or skip list.
- [ ] Make cancellation idempotent.
- [ ] Cancel an active agent turn when present.
- [ ] Stop future monitor work without resetting, aborting, or pushing.
- [ ] Remove the generated work branch and worktree after recording the final
  result. They are ByteQuay's own, not user work, and the local target branch
  never received a cherry-pick so it needs no repair.
- [ ] Abort any open cherry-pick before removing the worktree, and pause
  instead of removing when the worktree cannot be verified as ByteQuay's own.
- [ ] Record a clear pause/finish reason for every terminal path.

### Final accounting

- [ ] Require every item to have a non-`PENDING` result.
- [ ] Require a clean index/worktree and no Git operation.
- [ ] For each `APPLIED` item, require its target commit in created order, one
  expected parent, and the exact `-x` source line.
- [ ] Require `EMPTY_SKIPPED` items to have no target SHA.
- [ ] Require created target commits to follow confirmed source position.
- [ ] Run `git range-diff sourceBase..sourceTip initialTarget..currentTarget`
  for human review only.
- [ ] Bound and store the range-diff output and truncation flag.
- [ ] Never parse range-diff to decide completion.
- [ ] Complete the existing agent task after final accounting.
- [ ] Mark the execution `FINISHED` with current target SHA and summary.

### Recovery tests

- [ ] Test restart before and after clean cherry-pick.
- [ ] Test restart before and after `cherry-pick --continue`.
- [ ] Test restart before and after empty skip.
- [ ] Test restart before and after validation.
- [ ] Test restart after a batch result save but before the next sequencer
  action.
- [ ] Test paused recovery message idempotency and scope rejection.
- [ ] Test cancellation with no turn, active turn, terminal turn, conflict,
  and uncertain Git state.
- [ ] Test final accounting with missing, extra, reordered, and malformed
  `-x` commits.
- [ ] Test range-diff truncation and prove it cannot alter results.

### Checkpoint 7 gate

- [ ] `mvn verify` passes.
- [ ] Every journal crash point has an end-to-end test.
- [ ] No restart path blindly repeats a Git mutation.
- [ ] Final accounting is based only on plan, stored results, and Git facts.
- [ ] Reviewer approves all pause/finish reasons and recovery behavior.

---

## Checkpoint 8 — Execution UI

Goal: show durable progress and allow only safe user actions.

### Progress view

- [ ] Navigate from successful confirmation to the execution view.
- [ ] Poll the execution endpoint while status is `RUNNING`; do not create a
  second backend event system.
- [ ] Stop polling when the component unmounts or execution is terminal.
- [ ] Show overall status, current step, the current position derived from the
  first `PENDING` item, source SHA, title, PR, and latest bounded diagnostic.
- [ ] Expect several items to complete between polls, because one sequencer
  action can apply many commits.
- [ ] Render each item’s durable result.
- [ ] Show target SHA only for `APPLIED`.
- [ ] Explain `EMPTY_SKIPPED` as “No changes remained on the target.”
- [ ] Preserve PR grouping for display while keeping position as the order.
- [ ] Show agent task/turn links or existing task events without copying them
  into execution state.
- [ ] Show validation command, running state, exit result, and bounded output.
- [ ] Show pause reason with a recovery-message form when recovery is allowed.
- [ ] Add an explicit cancel action with confirmation.
- [ ] Disable all actions after `FINISHED`.

### Final review

- [ ] Show counts for already present, user skipped, empty skipped, and
  applied.
- [ ] Render bounded `range-diff` output in a readable monospaced view.
- [ ] Explain that range-diff is review information, not the completion
  decision.
- [ ] Provide copy actions for source SHA, target SHA, and diagnostics.
- [ ] Do not add push or pull-request write actions.

### Frontend tests

- [ ] Test polling start/stop and stale response protection.
- [ ] Test all status/current-step/item-result labels.
- [ ] Test paused message and cancel confirmation.
- [ ] Test PR folding does not hide the current item unexpectedly.
- [ ] Test final range-diff truncation display.
- [ ] Test keyboard and screen-reader labels.

### Checkpoint 8 gate

- [ ] `npm run lint` passes.
- [ ] `npx tsc --noEmit` passes.
- [ ] `npm test` passes.
- [ ] `npm run package` passes.
- [ ] Reviewer approves progress language, pause recovery, and final review.

---

## Checkpoint 9 — Branch rebase service

Goal: land a completed range on a target branch that moved, with the program
owning the rebase instead of trusting an agent to have done it.

Canonical behavior is defined in [Branch Rebase Service](rebase-service.md).

### Shared helper

- [ ] Reuse `GitSequencerDriver` from checkpoint 5 unchanged; add no
  rebase-specific copy of begin/observe/continue/skip/abort.
- [ ] Do not share the plan layer. Cherry-pick owns a user-confirmed plan with
  durable per-item results; this service owns no plan, because nobody chose the
  commit list.

### Service

- [ ] Add `BranchRebaseService` and its concrete default implementation in
  `com.bytequay.app.rebase`.
- [ ] Store `originalWorkTip` and `baseSha` before the first mutation.
- [ ] Finish immediately when the work branch already descends from the base,
  without starting a rebase.
- [ ] Begin the sequence through the driver with `--no-autosquash` so `fixup!`
  subjects are never silently squashed.
- [ ] Do not maintain a second per-commit result list; Git's sequencer is the
  plan.
- [ ] Accept a clean result only when no operation remains, the worktree is
  clean, and the new tip descends from `baseSha`.
- [ ] Record the conflicted commit from `git rebase --show-current-patch`.
- [ ] Let the agent stage resolutions only; the program calls
  `continueSequence(...)`, the same division cherry-pick uses.
- [ ] Skip a commit that became empty without another agent turn.
- [ ] Limit automatic turns to three per conflicted commit.
- [ ] Validate once against the final tree, not once per replayed commit.
- [ ] Abort and verify restoration to `originalWorkTip` on cancellation; pause
  when restoration cannot be verified.
- [ ] Report `originalWorkTip` in every terminal state.

### Landing flow

- [ ] Make landing a user-started step; never rebase automatically when
  execution finishes.
- [ ] Synchronize the local target branch again before rebasing.
- [ ] Leave push and pull-request creation to the user.

### Tests

- [ ] Test up-to-date branch, clean rebase, conflicted rebase, empty commit,
  retry exhaustion, cancellation restoring the original tip, and restart
  mid-rebase.
- [ ] Test that a `fixup!` subject survives unsquashed.
- [ ] Assert no path fetches, pushes, or writes to GitHub.

### Checkpoint 9 gate

- [ ] `mvn verify` passes.
- [ ] A moved target branch is landed without an agent deciding the outcome.
- [ ] Cancellation provably restores the original branch tip.
- [ ] CI Autofix is unchanged; its migration is a later, separate change.
- [ ] Reviewer approves the rebase boundaries and cancellation behavior.

---

## Checkpoint 10 — End-to-end acceptance and cleanup

Goal: prove the complete flow and remove temporary implementation scaffolding.

### Full journeys

- [ ] Preview and execute a clean multi-PR range.
- [ ] Skip one whole PR and one individual commit through explicit source
  SHAs.
- [ ] Detect one direct ancestor and one exact historical `-x` cherry-pick.
- [ ] Display, but do not skip, one patch-equivalent commit.
- [ ] Resolve one conflict, run module validation, and record one target
  commit.
- [ ] Repair one validation failure by amend.
- [ ] Automatically skip an originally empty commit.
- [ ] Automatically skip a target-redundant commit.
- [ ] Automatically skip a conflict resolution that becomes empty.
- [ ] Pause and recover one interrupted agent turn.
- [ ] Restart the backend during an open execution and finish correctly.
- [ ] Cancel an execution and confirm the generated branch and worktree are
  removed while the local target branch and every other worktree are untouched.
- [ ] Finish with exact accounting and human-readable range-diff.
- [ ] Synchronize a target branch that moved during execution, rebase the work
  branch onto it, and resolve one rebase conflict.
- [ ] Confirm the whole journey contacts the Git remote only when
  synchronizing, and reads GitHub only for pull-request association.

### Security and boundary audit

- [ ] Record every Git command used by the end-to-end harness and confirm the
  only remote contact is the target-branch synchronization, and that no
  `push` or reset of user work occurs. A `rebase --abort` on cancellation
  and a `cherry-pick --skip` on a proven-empty commit are the only
  permitted undo operations, both on ByteQuay's own branch.
- [ ] Record every GitHub test-client call and confirm all are reads.
- [ ] Confirm prompts and diagnostics do not expose credentials or the desktop
  session token.
- [ ] Confirm only the leased canonical worktree and exact temporary preview
  worktree are writable.
- [ ] Confirm output, path lists, prompt text, user messages, and range-diff
  are bounded.
- [ ] Confirm unsupported provider authentication blocks before execution
  creation or Git mutation.

### Full verification

- [ ] Run `mvn verify` in `backend/`.
- [ ] Run `npm run lint` in `frontend/`.
- [ ] Run `npx tsc --noEmit` in `frontend/`.
- [ ] Run `npm test` in `frontend/`.
- [ ] Run `npm run package` in `frontend/`.
- [ ] Review `git diff --check`.
- [ ] Update the canonical design if implementation changed any approved
  contract.
- [ ] Remove obsolete temporary fixtures and compatibility code.
- [ ] Delete this temporary tracker or rename it as an implementation record.

### Final gate

- [ ] Product review accepts the complete local workflow.
- [ ] Security review accepts the no-GitHub-write and worktree boundaries.
- [ ] All automated verification passes from a clean checkout.
- [ ] The release notes clearly label the feature and its supported agent
  lanes.

## Explicitly deferred

These items are not part of the first implementation:

- [ ] Merge commits and mainline-parent selection.
- [ ] Using patch similarity or range-diff for automatic completion.
- [ ] Continuing exact conflict simulation after the first unresolved
  conflict.
- [ ] Generic build-system plugins beyond the checked-in Maven wrapper path.
- [ ] Remote push, pull-request creation, comments, approvals, merges, or CI
  reruns. The rebase that precedes them is in checkpoint 9; only the remote
  write stays out.
- [ ] Migrating CI Autofix onto the branch rebase service.
- [ ] Multiple simultaneous executions in one worktree.
- [ ] A generic workflow engine or a second agent event model.
